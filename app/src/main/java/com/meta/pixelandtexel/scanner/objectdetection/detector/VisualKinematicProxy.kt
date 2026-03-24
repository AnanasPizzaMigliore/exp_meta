package com.meta.pixelandtexel.scanner.objectdetection.detector

import android.graphics.Bitmap
import kotlin.math.abs

class VisualKinematicProxy(
    private val motionThreshold: Float = 5.0f, // Average pixel change threshold
    private val blurThreshold: Double = 100.0 // Variance of Laplacian threshold
) {
    private var lastFrameGrayscale: IntArray? = null
    private var width: Int = 0
    private var height: Int = 0

    /**
     * Returns true if the frame is BOTH still and sharp.
     */
    fun isIntentDetected(bitmap: Bitmap): Boolean {
        if (width != bitmap.width || height != bitmap.height) {
            width = bitmap.width
            height = bitmap.height
            lastFrameGrayscale = null
        }

        val currentGrayscale = convertToGrayscale(bitmap)

        // 1. Motion Check
        val motion = calculateMotion(currentGrayscale)
        val isStill = motion < motionThreshold

        // 2. Blur Check (Only check blur if the frame is still to save CPU)
        val isSharp = if (isStill) calculateSharpness(currentGrayscale) > blurThreshold else false

        lastFrameGrayscale = currentGrayscale

        return isStill && isSharp
    }

    private fun convertToGrayscale(bitmap: Bitmap): IntArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            gray[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        }
        return gray
    }

    private fun calculateMotion(current: IntArray): Float {
        val last = lastFrameGrayscale ?: return Float.MAX_VALUE
        var totalDiff = 0L
        // Subsample by 4 to keep CPU usage ultra-low
        for (i in 0 until current.size step 4) {
            totalDiff += abs(current[i] - last[i])
        }
        return totalDiff.toFloat() / (current.size / 4)
    }

    /**
     * Variance of Laplacian: High variance = sharp edges. Low variance = blur.
     */
    private fun calculateSharpness(gray: IntArray): Double {
        val laplacian = IntArray(gray.size)
        // Simple 3x3 Laplacian Kernel calculation
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val center = gray[i]
                val sum = (gray[i-1] + gray[i+1] + gray[i-width] + gray[i+width]) - (4 * center)
                laplacian[i] = sum
            }
        }

        // Calculate Variance
        val mean = laplacian.average()
        return laplacian.fold(0.0) { acc, i -> acc + (i - mean) * (i - mean) } / laplacian.size
    }
}