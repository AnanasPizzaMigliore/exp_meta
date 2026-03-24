package com.meta.pixelandtexel.scanner.executorch

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.Arrays
import android.graphics.Bitmap
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set

class RecPreProcessor {

    fun saveDebugBitmap(inputData: FloatArray, width: Int, height: Int, name: String) {
        try {
            val bitmap = createBitmap(width, height)
            val planeSize = width * height

            for (i in 0 until planeSize) {
                // Un-normalize: (-1.0 to 1.0) -> (0 to 255)
                // We read just one channel (Blue) to verify geometry
                val pixelVal = inputData[i] // Plane 0
                val colorVal = ((pixelVal * 0.5f + 0.5f) * 255).toInt().coerceIn(0, 255)

                // Create grayscale pixel
                val color = (0xFF shl 24) or (colorVal shl 16) or (colorVal shl 8) or colorVal

                val row = i / width
                val col = i % width
                bitmap[col, row] = color
            }

            val path = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "debug_$name.png")
            FileOutputStream(path).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            android.util.Log.d("OpenOCR", "Saved debug image to: ${path.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("OpenOCR", "Failed to save debug image", e)
        }
    }
    private val MEAN = 0.5f
    private val STD = 0.5f
    private val TARGET_H = 48
    private val TARGET_W = 320

    fun process(srcMat: Mat): FloatArray {
        val h = srcMat.rows()
        val w = srcMat.cols()

        // Resize logic
        val scale = TARGET_H.toFloat() / h
        var newW = (w * scale).toInt()
        if (newW > TARGET_W) newW = TARGET_W

        val resizedMat = Mat()
        if (newW > 0) {
            Imgproc.resize(srcMat, resizedMat, Size(newW.toDouble(), TARGET_H.toDouble()))
        }

        val floatMat = Mat()
        resizedMat.convertTo(floatMat, CvType.CV_32F, 1.0 / 255.0)

        // FIX 2: Initialize with -1.0f (Black)
        // 0.0f is Gray, which causes garbage output
        val inputData = FloatArray(3 * TARGET_H * TARGET_W)
        Arrays.fill(inputData, -1.0f)

        val floatBuffer = FloatArray(newW * TARGET_H * 3)
        floatMat.get(0, 0, floatBuffer)

        val planeSize = TARGET_H * TARGET_W

        for (i in 0 until newW * TARGET_H) {
            // FIX 3: Keep BGR order (Blue=Plane0)
            val b = floatBuffer[i * 3]
            val g = floatBuffer[i * 3 + 1]
            val r = floatBuffer[i * 3 + 2]

            // Normalize
            val nB = (b - MEAN) / STD
            val nG = (g - MEAN) / STD
            val nR = (r - MEAN) / STD

            val row = i / newW
            val col = i % newW
            val outIdx = (row * TARGET_W) + col

            inputData[outIdx] = nB               // Plane 0 = Blue
            inputData[outIdx + planeSize] = nG   // Plane 1 = Green
            inputData[outIdx + 2 * planeSize] = nR // Plane 2 = Red
        }

        resizedMat.release(); floatMat.release()
        return inputData
    }
}