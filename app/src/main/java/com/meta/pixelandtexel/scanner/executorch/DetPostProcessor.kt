package com.meta.pixelandtexel.scanner.executorch

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.min

class DetPostProcessor {
    private val THRESH = 0.7
    private val UNCLIP_RATIO = 1.5
    private val MIN_SIZE = 3.0

    fun process(mapData: FloatArray, width: Int, height: Int): List<MatOfPoint> {
        val predMat = Mat(height, width, CvType.CV_32F)
        predMat.put(0, 0, mapData)

        val binaryMat = Mat()
        Imgproc.threshold(predMat, binaryMat, THRESH, 1.0, Imgproc.THRESH_BINARY)
        val binaryUint8 = Mat()
        binaryMat.convertTo(binaryUint8, CvType.CV_8U, 255.0)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binaryUint8, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val boxes = ArrayList<MatOfPoint>()
        for (contour in contours) {
            if (Imgproc.contourArea(contour) < 10) continue

            val minRect = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
            if (min(minRect.size.width, minRect.size.height) < MIN_SIZE) continue

            val expandedRect = unclip(minRect, UNCLIP_RATIO)
            if (min(expandedRect.size.width, expandedRect.size.height) < MIN_SIZE + 2) continue

            val points = arrayOfNulls<Point>(4)
            expandedRect.points(points)
            val boxParams = MatOfPoint()
            boxParams.fromArray(*points)
            boxes.add(boxParams)
        }

        predMat.release(); binaryMat.release(); binaryUint8.release()
        return boxes
    }

    private fun unclip(rect: RotatedRect, ratio: Double): RotatedRect {
        val w = rect.size.width
        val h = rect.size.height
        val area = w * h
        val perimeter = 2 * (w + h)
        if (perimeter == 0.0) return rect
        val distance = area * ratio / perimeter
        return RotatedRect(rect.center, Size(w + 2 * distance, h + 2 * distance), rect.angle)
    }
}