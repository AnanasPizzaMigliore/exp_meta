package com.meta.pixelandtexel.scanner.executorch

import android.content.Context
import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// Made context a class property by adding 'private val'
class OCRManager(private val context: Context) {
    companion object {
        private const val TAG = "OCRManager"
    }

    // Helper function to extract assets to physical storage
    private fun ensureAssetExists(assetPath: String): String {
        val outFile = File(context.filesDir, assetPath)

        // Only copy if it doesn't already exist to save startup time
        if (!outFile.exists()) {
            // Ensure the directory structure (models/executorch/) exists
            outFile.parentFile?.mkdirs()

            // Copy the file from the APK assets to the internal storage
            context.assets.open(assetPath).use { inputStream ->
                FileOutputStream(outFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return assetPath
    }

    // Changed to 'by lazy' to ensure the assets are copied BEFORE the models initialize
    private val detModel by lazy { ExecutorchModel(context, ensureAssetExists("models/executorch/repvit_det_cpu_int8.pte")) }
    private val recModel by lazy { ExecutorchModel(context, ensureAssetExists("models/executorch/repvit_rec_cpu_int8.pte")) }

    // Also ensuring the keys txt file is copied, just in case RecPostProcessor needs a physical path too
    private val recPost by lazy { RecPostProcessor(context, ensureAssetExists("models/executorch/ppocr_keys_v1.txt"), true) }

    private val detPre = DetPreProcessor()
    private val detPost = DetPostProcessor()
    private val recPre = RecPreProcessor()

    // Using 960 to match your DetPreProcessor TARGET_SIZE
    private val DET_SIZE = 960
    private val REC_W = 320
    private val REC_H = 48
    private val CROP_PADDING = 5.0

    fun predict(srcMat: Mat): List<Pair<String, List<Point>>> {
        Log.d(TAG, "Starting OCR predict on image ${srcMat.cols()}x${srcMat.rows()}")
        // 1. Detection
        val detRes = detPre.process(srcMat)
        // Pass DET_SIZE (960) since DetPreProcessor pads to this size
        val detOut = detModel.forward(detRes.tensor, DET_SIZE, DET_SIZE) ?: run {
            Log.e(TAG, "Detection model forward returned null")
            return emptyList()
        }
        Log.d(TAG, "Detection output shape: ${detOut.shape.joinToString()}")
        val boxes = detPost.process(detOut.data, DET_SIZE, DET_SIZE)

        // [LATENCY_BENCHMARK] T2: Detection Complete. INT8 RepViT-DBNet model finishes
        Log.d("LATENCY_BENCHMARK", "T2: Detection Complete (RepViT-DBNet finished). Found ${boxes.size} text boxes")

        val results = ArrayList<Pair<String, List<Point>>>()

        // 2. Recognition Loop
        for ((i, box) in boxes.withIndex()) {
            val points = box.toArray()

            val originalPoints = points.map {
                Point(it.x / detRes.scaleW, it.y / detRes.scaleH)
            }.toTypedArray()

            val expandedPoints = expandBox(originalPoints, srcMat.cols(), srcMat.rows())

            // A. Get Original Crop
            val crop0 = getStraightTextCrop(srcMat, expandedPoints)

            // B. Run Inference (0 degrees)
            val (text0, score0) = runRec(crop0)
            Log.d(TAG, "Box $i: text='$text0', score=$score0")

            // C. Run Inference (180 degrees)
            val crop180 = Mat()
            Core.rotate(crop0, crop180, Core.ROTATE_180)
            val (text180, score180) = runRec(crop180)
            Log.d(TAG, "Box $i rotated: text='$text180', score=$score180")

            // D. Compare & Select Winner
            var bestText = text0
            var bestScore = score0

            if (score180 > score0) {
                bestText = text180
                bestScore = score180
            }

            if (bestText.isNotEmpty() && bestScore > 0.1f) {
                results.add(Pair(bestText, originalPoints.toList()))
                Log.d(TAG, "Accepted: '$bestText' with score $bestScore")
            } else {
                Log.d(TAG, "Rejected: '$bestText' with score $bestScore")
            }

            crop0.release()
            crop180.release()
        }

        // [LATENCY_BENCHMARK] T3: Recognition Complete. INT8 RepSVTR model finishes transcribing
        Log.d("LATENCY_BENCHMARK", "T3: Recognition Complete (RepSVTR finished). Total results: ${results.size}")
        
        return results
    }

    /**
     * Helper to run recognition and return Text + Score
     */
    private fun runRec(crop: Mat): Pair<String, Float> {
        val recInput = recPre.process(crop)
        val recOutputObj = recModel.forward(recInput, REC_W, REC_H)

        if (recOutputObj != null) {
            val dims = recOutputObj.shape
            val numClasses = dims[dims.size - 1].toInt()
            val seqLen = dims[dims.size - 2].toInt()

            return recPost.decode(recOutputObj.data, seqLen, numClasses)
        }
        return Pair("", 0.0f)
    }

    private fun expandBox(box: Array<Point>, imgW: Int, imgH: Int): Array<Point> {
        if (CROP_PADDING <= 0) return box

        var sumX = 0.0
        var sumY = 0.0
        for (p in box) {
            sumX += p.x
            sumY += p.y
        }
        val centerX = sumX / 4.0
        val centerY = sumY / 4.0

        val newBox = Array(4) { Point() }

        for (i in box.indices) {
            val p = box[i]
            val vecX = p.x - centerX
            val vecY = p.y - centerY
            val length = sqrt(vecX * vecX + vecY * vecY)

            if (length > 0) {
                val unitX = vecX / length
                val unitY = vecY / length

                var newX = p.x + (unitX * CROP_PADDING)
                var newY = p.y + (unitY * CROP_PADDING)

                newX = max(0.0, min(newX, (imgW - 1).toDouble()))
                newY = max(0.0, min(newY, (imgH - 1).toDouble()))

                newBox[i] = Point(newX, newY)
            } else {
                newBox[i] = p
            }
        }
        return newBox
    }

    private fun getStraightTextCrop(src: Mat, box: Array<Point>): Mat {
        val pts = sortPoints(box)
        val w = max(dist(pts[0], pts[1]), dist(pts[2], pts[3])).toInt()
        val h = max(dist(pts[0], pts[3]), dist(pts[1], pts[2])).toInt()

        val safeW = max(w, 8)
        val safeH = max(h, 8)

        val dstPts = arrayOf(
            Point(0.0, 0.0),
            Point(safeW.toDouble(), 0.0),
            Point(safeW.toDouble(), safeH.toDouble()),
            Point(0.0, safeH.toDouble())
        )

        val trans = Imgproc.getPerspectiveTransform(MatOfPoint2f(*pts), MatOfPoint2f(*dstPts))
        val out = Mat()
        Imgproc.warpPerspective(src, out, trans, Size(safeW.toDouble(), safeH.toDouble()))
        trans.release()
        return out
    }

    private fun sortPoints(box: Array<Point>): Array<Point> {
        val byX = box.sortedBy { it.x }
        val left = byX.take(2).sortedBy { it.y }
        val right = byX.takeLast(2).sortedBy { it.y }
        return arrayOf(left[0], right[0], right[1], left[1])
    }

    private fun dist(p1: Point, p2: Point) = sqrt((p1.x-p2.x).pow(2)+(p1.y-p2.y).pow(2))
}
