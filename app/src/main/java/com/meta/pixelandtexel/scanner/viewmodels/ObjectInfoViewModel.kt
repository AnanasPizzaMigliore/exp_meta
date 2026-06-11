// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.pixelandtexel.scanner.viewmodels

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.pixelandtexel.scanner.executorch.OCRManager
import com.meta.pixelandtexel.scanner.executorch.DateParser
import com.meta.pixelandtexel.scanner.models.ObjectInfoRequest
import com.meta.pixelandtexel.scanner.services.llama.IQueryLlamaServiceHandler
import com.meta.pixelandtexel.scanner.services.llama.QueryLlamaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max

class ObjectInfoViewModel(
    private val infoRequest: ObjectInfoRequest,
    private val queryTemplate: String,
    private val isBenchmark: Boolean = false,
    private val benchmarkFolderUri: Uri? = null,
    private val application: Application? = null
) : ViewModel() {
    companion object {
        private const val TAG = "ObjectInfoViewModel"
    }

    private val _resultMessage = mutableStateOf("")
    private val _title = mutableStateOf(infoRequest.name.replaceFirstChar { it.uppercaseChar() })

    // Create a 1x1 placeholder bitmap to avoid null errors in the Screen
    private val _image = mutableStateOf(infoRequest.image ?: createBitmap(
        1,
        1,
        Bitmap.Config.ALPHA_8
    ))

    val title: State<String> = _title
    val resultMessage: State<String> = _resultMessage
    val image: State<Bitmap> = _image

    private var ocrManager: OCRManager? = null

    init {
        application?.let {
            ocrManager = OCRManager(it)
        }
    }

    fun queryLlama() {
        viewModelScope.launch {
            Log.d(TAG, "queryLlama called. isBenchmark: $isBenchmark")
            if (isBenchmark) {
                runBatchBenchmark()
                return@launch
            }

            val query = queryTemplate.replace("{{object_name}}", infoRequest.name)
            val imageValue = infoRequest.image

            if (imageValue != null) {
                QueryLlamaService.submitQuery(
                    query,
                    imageValue,
                    handler = object : IQueryLlamaServiceHandler {
                        override fun onStreamStart() {}
                        override fun onPartial(partial: String) {
                            _resultMessage.value = partial.trim('\n', '\r')
                        }
                        override fun onFinished(answer: String) {
                            _resultMessage.value = answer.trim('\n', '\r')
                        }
                        override fun onError(reason: String) {
                            _resultMessage.value = reason
                        }
                    }
                )
            } else {
                QueryLlamaService.submitQuery(
                    query,
                    handler = object : IQueryLlamaServiceHandler {
                        override fun onStreamStart() {}
                        override fun onPartial(partial: String) {
                            _resultMessage.value = partial.trim('\n', '\r')
                        }
                        override fun onFinished(answer: String) {
                            _resultMessage.value = answer.trim('\n', '\r')
                        }
                        override fun onError(reason: String) {
                            _resultMessage.value = reason
                        }
                    }
                )
            }
        }
    }

    private fun decodeScaledBitmap(app: Application, uri: Uri, targetSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        val width = options.outWidth
        val height = options.outHeight
        var calculatedInSampleSize = 1

        if (width > targetSize || height > targetSize) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / calculatedInSampleSize >= targetSize && halfWidth / calculatedInSampleSize >= targetSize) {
                calculatedInSampleSize *= 2
            }
        }

        return BitmapFactory.Options().apply {
            inSampleSize = calculatedInSampleSize
            // Forcing ARGB_8888 as required by Utils.bitmapToMat in some cases
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }.let { opt ->
            app.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opt)
            }
        }
    }

    private suspend fun runBatchBenchmark() {
        val app = application ?: return
        val uri = benchmarkFolderUri ?: return
        val manager = ocrManager ?: return

        val directory = DocumentFile.fromTreeUri(app, uri) ?: return
        val imageFiles = directory.listFiles().filter {
            it.isFile && (it.name?.lowercase()?.endsWith(".jpg") == true ||
                          it.name?.lowercase()?.endsWith(".png") == true ||
                          it.name?.lowercase()?.endsWith(".jpeg") == true)
        }

        if (imageFiles.isEmpty()) {
            _resultMessage.value = "⚠️ No images found in folder for batch benchmark."
            return
        }

        _resultMessage.value = "🔍 Benchmarking ${imageFiles.size} images..."

        val rows = mutableListOf<String>()
        val header = "filename,date,inference_time_ms,energy_joules,temperature_c,voltage_mv,flow_ua,remain_energy_uah,ocr_result,parsed_date\n"

        for ((index, file) in imageFiles.withIndex()) {
            // Reduced delay but kept some for UI breathing room
            delay(500)

            // Update UI
            val bitmap = withContext(Dispatchers.IO) {
                // Downscale to max 960 to match DET_SIZE and save memory
                decodeScaledBitmap(app, file.uri, 960)
            } ?: continue

            _image.value = bitmap
            _title.value = "Benchmarking: ${index + 1}/${imageFiles.size}"
            _resultMessage.value = "Processing ${file.name}..."

            // Real Inference
            val startTime = System.currentTimeMillis()
            var rawOcrText = ""
            var parsedDate = ""
            try {
                withContext(Dispatchers.Default) {
                    val rgbaMat = Mat()
                    Utils.bitmapToMat(bitmap, rgbaMat)

                    val bgrMat = Mat()
                    Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)

                    Log.d(TAG, "Starting inference for ${file.name} (${bitmap.width}x${bitmap.height}, channels=${bgrMat.channels()})")
                    val ocrResults = manager.predict(bgrMat)

                    rawOcrText = ocrResults.joinToString(" ") { it.first }.replace(",", " ").replace("\n", " ")
                    parsedDate = DateParser.parse(rawOcrText) ?: "NONE"

                    rgbaMat.release()
                    bgrMat.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference crashed for ${file.name}", e)
                rawOcrText = "CRASH: ${e.message}"
            }
            val endTime = System.currentTimeMillis()
            val inferenceTimeMs = endTime - startTime

            // Stats
            val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val temperature = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
            val batteryManager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            
            // Get instantaneous current with fallback to average
            var flowUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (flowUa == 0L) {
                flowUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            }

            val remainUah = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

            // Joule calculation: V * I * t
            val voltageV = voltageMv / 1000.0
            val flowA = Math.abs(flowUa) / 1000000.0
            val timeS = inferenceTimeMs / 1000.0
            val energyJoules = voltageV * flowA * timeS

            val outputDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val row = "${file.name},$outputDate,$inferenceTimeMs,$energyJoules,$temperature,$voltageMv,$flowUa,$remainUah,$rawOcrText,$parsedDate\n"
            rows.add(row)
            Log.d(TAG, "Completed inference for ${file.name} in $inferenceTimeMs ms. Result: $parsedDate")

            // Force manual GC and finalizer run between images to clean up native objects
            System.runFinalization()
            System.gc()
        }

        // Save CSV
        withContext(Dispatchers.IO) {
            try {
                val csvName = "batch_benchmark_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".csv"
                val csvFile = directory.createFile("text/comma-separated-values", csvName)
                if (csvFile != null) {
                    app.contentResolver.openOutputStream(csvFile.uri)?.use {
                        OutputStreamWriter(it).use { writer ->
                            writer.write(header)
                            rows.forEach { row -> writer.write(row) }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        _resultMessage.value = "✅ Batch benchmark complete.\nProcessed ${imageFiles.size} images.\nResults saved to $csvName"
                        _title.value = "Benchmark Complete"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch benchmark save failed", e)
            }
        }
    }
}
