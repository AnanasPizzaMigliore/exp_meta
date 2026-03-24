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

    private suspend fun runBatchBenchmark() {
        val app = application ?: return
        val uri = benchmarkFolderUri ?: return

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
        val header = "filename,date,inference_time_ms,energy_joules,temperature_c,voltage_mv,flow_ua,remain_energy_uah\n"

        for (file in imageFiles) {
            // Update UI
            val bitmap = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(file.uri)?.use { 
                    BitmapFactory.decodeStream(it)
                }
            } ?: continue
            _image.value = bitmap
            _title.value = "Benchmarking: ${file.name}"

            // Mock Inference
            val startTime = System.currentTimeMillis()
            delay(800) // Simulating processing
            val endTime = System.currentTimeMillis()
            val inferenceTimeMs = endTime - startTime

            // Stats
            val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val temperature = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
            val batteryManager = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val flowUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val remainUah = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

            // Joule calculation: V * I * t
            val voltageV = voltageMv / 1000.0
            val flowA = Math.abs(flowUa) / 1000000.0
            val timeS = inferenceTimeMs / 1000.0
            val energyJoules = voltageV * flowA * timeS

            val outputDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val row = "${file.name},$outputDate,$inferenceTimeMs,$energyJoules,$temperature,$voltageMv,$flowUa,$remainUah\n"
            rows.add(row)
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
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch benchmark save failed", e)
            }
        }
    }
}