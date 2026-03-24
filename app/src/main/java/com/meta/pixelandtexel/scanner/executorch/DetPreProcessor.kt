package com.meta.pixelandtexel.scanner.executorch

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Data class to hold tensor + scale info
data class DetResult(val tensor: FloatArray, val scaleW: Float, val scaleH: Float) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DetResult

        if (scaleW != other.scaleW) return false
        if (scaleH != other.scaleH) return false
        if (!tensor.contentEquals(other.tensor)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = scaleW.hashCode()
        result = 31 * result + scaleH.hashCode()
        result = 31 * result + tensor.contentHashCode()
        return result
    }
}

class DetPreProcessor {
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    private val TARGET_SIZE = 960

    fun process(srcMat: Mat): DetResult {
        val h = srcMat.rows()
        val w = srcMat.cols()

        // Resize Logic
        val ratio = min(1.0f, TARGET_SIZE.toFloat() / max(h, w))
        var resizeH = (h * ratio).toInt()
        var resizeW = (w * ratio).toInt()

        resizeH = max((resizeH / 32.0).roundToInt() * 32, 32)
        resizeW = max((resizeW / 32.0).roundToInt() * 32, 32)

        resizeH = min(resizeH, TARGET_SIZE)
        resizeW = min(resizeW, TARGET_SIZE)

        val resizedMat = Mat()
        Imgproc.resize(srcMat, resizedMat, Size(resizeW.toDouble(), resizeH.toDouble()))

        val floatMat = Mat()
        resizedMat.convertTo(floatMat, CvType.CV_32F, 1.0 / 255.0)

        // Pad to 1280x1280
        val inputData = FloatArray(3 * TARGET_SIZE * TARGET_SIZE)
        val floatBuffer = FloatArray(resizeW * resizeH * 3)
        floatMat.get(0, 0, floatBuffer)

        val planeSize = TARGET_SIZE * TARGET_SIZE

        for (i in 0 until resizeW * resizeH) {
            val b = floatBuffer[i * 3]
            val g = floatBuffer[i * 3 + 1]
            val r = floatBuffer[i * 3 + 2]

            val nB = (b - MEAN[2]) / STD[2]
            val nG = (g - MEAN[1]) / STD[1]
            val nR = (r - MEAN[0]) / STD[0]

            val row = i / resizeW
            val col = i % resizeW
            val outIdx = (row * TARGET_SIZE) + col

            inputData[outIdx] = nB
            inputData[outIdx + planeSize] = nG
            inputData[outIdx + 2 * planeSize] = nR
        }

        resizedMat.release()
        floatMat.release()

        return DetResult(inputData, resizeW.toFloat() / w, resizeH.toFloat() / h)
    }
}