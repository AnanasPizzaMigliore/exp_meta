package com.meta.pixelandtexel.scanner.executorch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import org.opencv.core.Point as OpenCvPoint
import kotlin.math.max

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private var results: List<Pair<String, List<OpenCvPoint>>> = emptyList()
    private var imgW = 1
    private var imgH = 1
    private val matrix = Matrix()

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    // Set this to TRUE if you are using PreviewView.ScaleType.FILL_CENTER (Default)
    // Set to FALSE if you changed it to FIT_CENTER
    private var isFillScale = true

    fun setResults(ocrResults: List<Pair<String, List<OpenCvPoint>>>, imageWidth: Int, imageHeight: Int) {
        this.results = ocrResults
        this.imgW = imageWidth
        this.imgH = imageHeight
        calculateMatrix()
        invalidate()
    }

    private fun calculateMatrix() {
        if (imgW == 0 || imgH == 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        val scale: Float
        val dx: Float
        val dy: Float

        val viewRatio = viewW / viewH
        val imgRatio = imgW.toFloat() / imgH.toFloat()

        if (isFillScale) {
            // Logic for SCALE_TYPE_FILL_CENTER (CameraX Default)
            // It zooms until the image FILLS the screen, cropping excess.
            if (viewRatio > imgRatio) {
                // View is wider -> Scale by Width, crop height
                scale = viewW / imgW
                dx = 0f
                dy = (viewH - imgH * scale) / 2f
            } else {
                // View is taller -> Scale by Height, crop width (Most phones)
                scale = viewH / imgH
                dx = (viewW - imgW * scale) / 2f
                dy = 0f
            }
        } else {
            // Logic for SCALE_TYPE_FIT_CENTER (Static Images)
            // It scales until image FITS inside, adding black bars.
            if (viewRatio > imgRatio) {
                scale = viewH / imgH
                dx = (viewW - imgW * scale) / 2f
                dy = 0f
            } else {
                scale = viewW / imgW
                dx = 0f
                dy = (viewH - imgH * scale) / 2f
            }
        }

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
    }

    // Call this when switching between Camera (Fill) and Gallery (Fit)
    fun setScaleType(isFill: Boolean) {
        this.isFillScale = isFill
        calculateMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((_, points) in results) {
            if (points.size == 4) {
                val path = Path()
                val p0 = mapPoint(points[0])
                val p1 = mapPoint(points[1])
                val p2 = mapPoint(points[2])
                val p3 = mapPoint(points[3])

                path.moveTo(p0.x, p0.y)
                path.lineTo(p1.x, p1.y)
                path.lineTo(p2.x, p2.y)
                path.lineTo(p3.x, p3.y)
                path.close()

                canvas.drawPath(path, boxPaint)
            }
        }
    }

    private fun mapPoint(p: OpenCvPoint): PointF {
        val pts = floatArrayOf(p.x.toFloat(), p.y.toFloat())
        matrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }
}