package com.meta.pixelandtexel.scanner.executorch

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class RecPostProcessor(
    context: Context,
    keysPath: String,
    private val useSpace: Boolean = true
) {
    private val charList: MutableList<String> = ArrayList()

    init {
        // 1. Add "blank" character at index 0 (Standard for CTC)
        charList.add("blank")

        // 2. Load the dictionary file
        try {
            val assetManager = context.assets
            val reader = BufferedReader(InputStreamReader(assetManager.open(keysPath)))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // If the line is empty (rare in some dicts), treat as space
                if (line!!.isEmpty()) {
                    charList.add(" ")
                } else {
                    charList.add(line)
                }
            }
            reader.close()

            // 3. Add a space char at the end (Common for PP-OCR)
            if (useSpace) {
                charList.add(" ")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Decodes the raw float array from the model into Text and a Confidence Score.
     * * @param outputData Flat array of probabilities (size = seqLen * numClasses)
     * @param seqLen The time steps (e.g., 40 or 80)
     * @param numClasses The size of the dictionary + blank
     * @return Pair(Decoded Text, Average Confidence Score)
     */
    fun decode(outputData: FloatArray, seqLen: Int, numClasses: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var lastIndex = -1

        var totalScore = 0.0f
        var validCharCount = 0

        // Iterate over time steps (e.g., 0..40)
        for (t in 0 until seqLen) {

            // 1. Find the ArgMax (Class with highest probability at this step)
            var maxIdx = -1
            var maxVal = -Float.MAX_VALUE

            val offset = t * numClasses
            for (c in 0 until numClasses) {
                val value = outputData[offset + c]
                if (value > maxVal) {
                    maxVal = value
                    maxIdx = c
                }
            }

            // 2. CTC Decoding Logic
            // - Ignore "Blank" (Index 0)
            // - Ignore duplicate repeated characters (e.g., "AA" -> "A")
            if (maxIdx != 0 && maxIdx != lastIndex) {
                if (maxIdx < charList.size) {
                    sb.append(charList[maxIdx])

                    // Accumulate score to calculate average later
                    totalScore += maxVal
                    validCharCount++
                }
            }

            lastIndex = maxIdx
        }

        // 3. Calculate Average Confidence
        val avgScore = if (validCharCount > 0) {
            totalScore / validCharCount
        } else {
            0.0f
        }

        return Pair(sb.toString(), avgScore)
    }
}