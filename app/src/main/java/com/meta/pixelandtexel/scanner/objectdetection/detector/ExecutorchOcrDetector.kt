package com.meta.pixelandtexel.scanner.objectdetection.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.meta.pixelandtexel.scanner.executorch.DateParser
import com.meta.pixelandtexel.scanner.executorch.OCRManager
import com.meta.pixelandtexel.scanner.objectdetection.detector.models.DetectedObject
import com.meta.pixelandtexel.scanner.objectdetection.detector.models.DetectedObjectsResult
import com.meta.pixelandtexel.scanner.objectdetection.detector.models.JavaCamera2Frame
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import android.media.Image
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.createBitmap

class ExecutorchOcrDetector(private val context: Context) : IObjectDetectorHelper {

    private val detectorJob = SupervisorJob()
    private val detectorScope = CoroutineScope(Dispatchers.IO + detectorJob)

    private var piperJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private val audioLock = Any()

    private var ttsEngine: OfflineTts? = null
    private var isPiperReady = false
    private var lastSpokenText = ""

    // --- ADDED: Callback to notify when TTS finishes playing ---
    var onAudioFinishedPlaying: (() -> Unit)? = null

    companion object {
        private const val TAG = "ExecutorchOcrDetector"
        init {
            if (!OpenCVLoader.initLocal()) {
                Log.e(TAG, "CRITICAL: Unable to load OpenCV native libraries!")
            } else {
                Log.d(TAG, "SUCCESS: OpenCV native libraries loaded.")
            }
        }
    }

    private val ocrManager = OCRManager(context)
    private var resultsListener: IObjectsDetectedListener? = null
    private var finishedDetectingCallback = AtomicReference<(() -> Unit)?>()

    init {
        detectorScope.launch {
            setupPiperModel()
        }
    }

    private fun copyAssetsToFilesDir(assetPath: String, targetDir: File) {
        val assets = context.assets
        try {
            val items = assets.list(assetPath) ?: return
            if (items.isEmpty()) {
                val targetFile = File(targetDir, assetPath.substringAfterLast("/"))
                if (!targetFile.exists()) {
                    assets.open(assetPath).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } else {
                val newDir = File(targetDir, assetPath.substringAfterLast("/"))
                if (!newDir.exists()) newDir.mkdirs()
                for (item in items) {
                    copyAssetsToFilesDir("$assetPath/$item", newDir)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetPath", e)
        }
    }

    private fun setupPiperModel() {
        try {
            val espeakTargetDir = File(context.filesDir, "espeak-ng-data")
            if (!espeakTargetDir.exists() || (espeakTargetDir.list()?.isEmpty() == true)) {
                Log.d(TAG, "Extracting espeak-ng-data to internal storage...")
                copyAssetsToFilesDir("models/tts_model/espeak-ng-data", context.filesDir)
            }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "models/tts_model/en_US-lessac-medium.onnx",
                        tokens = "models/tts_model/tokens.txt",
                        dataDir = espeakTargetDir.absolutePath
                    ),
                    numThreads = 2,
                    debug = true
                )
            )

            ttsEngine = OfflineTts(assetManager = context.assets, config = config)
            isPiperReady = true
            Log.d(TAG, "SUCCESS: Piper TTS Engine initialized via Sherpa-ONNX.")

        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to initialize Sherpa-ONNX TTS", e)
        }
    }

    private fun speakWithPiper(text: String) {
        piperJob?.cancel()

        piperJob = detectorScope.launch {
            try {
                Log.d(TAG, "Generating audio for: $text")

                val audio = ttsEngine?.generate(text)
                if (audio == null || audio.samples.isEmpty()) {
                    Log.e(TAG, "TTS ERROR: Generated audio is empty.")
                    // Make sure we unblock MainActivity if TTS fails!
                    onAudioFinishedPlaying?.invoke()
                    return@launch
                }

                playAudioInferred(audio.samples, audio.sampleRate)

            } catch (e: Exception) {
                Log.e(TAG, "Piper TTS generation failed", e)
                onAudioFinishedPlaying?.invoke()
            }
        }
    }

    private fun playAudioInferred(samples: FloatArray, sampleRate: Int) {
        synchronized(audioLock) {
            audioTrack?.release()

            val shortArray = ShortArray(samples.size)
            for (i in samples.indices) {
                var value = (samples[i] * 32767).toInt()
                value = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                shortArray[i] = value.toShort()
            }

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(shortArray.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(shortArray, 0, shortArray.size)

            // --- THE MAGIC: Wait for the exact number of frames to play ---
            audioTrack?.setNotificationMarkerPosition(shortArray.size)
            audioTrack?.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack) {
                        Log.d(TAG, "TTS Audio finished playing! Firing callback.")
                        onAudioFinishedPlaying?.invoke()
                    }

                    override fun onPeriodicNotification(track: AudioTrack) {}
                },
                Handler(Looper.getMainLooper())
            )
            // ----------------------------------------------------------------

            // [LATENCY_BENCHMARK] T5: TTS Playback Starts. The exact moment the TextToSpeech engine begins routing audio
            Log.d("LATENCY_BENCHMARK", "T5: TTS Playback Starts")
            audioTrack?.play()
        }
    }

    override fun setObjectDetectedListener(listener: IObjectsDetectedListener) {
        resultsListener = listener
    }

    override fun detect(image: Image, width: Int, height: Int, finally: () -> Unit) {
        if (!finishedDetectingCallback.compareAndSet(null, finally)) {
            finally()
            return
        }

        val startTime = SystemClock.uptimeMillis()

        detectorScope.launch {
            var cvFrame: JavaCamera2Frame? = null
            var rgbaMat: Mat? = null
            var bgrMat: Mat? = null

            try {
                cvFrame = JavaCamera2Frame(image)
                rgbaMat = cvFrame.rgba()
                bgrMat = Mat()
                Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)

                val ocrResults = ocrManager.predict(bgrMat)

                val rawDetectedObjects = ocrResults.mapIndexed { index, pair ->
                    val text = pair.first
                    val points = pair.second

                    val left = points.minOf { it.x }.toInt()
                    val top = points.minOf { it.y }.toInt()
                    val right = points.maxOf { it.x }.toInt()
                    val bottom = points.maxOf { it.y }.toInt()

                    val boundsRect = Rect(left, top, right, bottom)
                    DetectedObject(
                        id = index,
                        label = text,
                        bounds = boundsRect,
                        point = PointF(boundsRect.exactCenterX(), boundsRect.exactCenterY()),
                        confidence = 1.0f
                    )
                }

                var displayObjects = emptyList<DetectedObject>()

                if (rawDetectedObjects.isNotEmpty()) {
                    val rawText = rawDetectedObjects.joinToString(" ") { it.label }
                    val cleanText = DateParser.parse(rawText) ?: ""

                    if (cleanText.isNotBlank()) {
                        // [LATENCY_BENCHMARK] T4: Parsing Complete. The moment your local logic calculates the date difference
                        Log.d("LATENCY_BENCHMARK", "T4: Parsing Complete (Date difference: $cleanText)")
                        Log.d(TAG, "parsed date: $cleanText")

                        if (isPiperReady && cleanText != lastSpokenText) {
                            lastSpokenText = cleanText
                            val spokenDate = DateParser.formatForSpeech(cleanText)
                            speakWithPiper(spokenDate)
                        } else if (cleanText == lastSpokenText) {
                            // If it scanned the same thing, just fire the callback instantly
                            // to free up the state machine.
                            Log.d(TAG, "Date already spoken. Skipping TTS.")
                            onAudioFinishedPlaying?.invoke()
                        }

                        val combinedRect = Rect(rawDetectedObjects.first().bounds)
                        rawDetectedObjects.forEach { combinedRect.union(it.bounds) }

                        displayObjects = listOf(
                            DetectedObject(
                                id = 0,
                                label = cleanText,
                                bounds = combinedRect,
                                point = PointF(combinedRect.exactCenterX(), combinedRect.exactCenterY()),
                                confidence = 1.0f
                            )
                        )
                    } else {
                        // OPTIMIZATION: Do NOT call onAudioFinishedPlaying here.
                        // We want to keep scanning frames for a valid date until timeout.
                    }
                } else {
                    // OPTIMIZATION: Do NOT call onAudioFinishedPlaying here.
                    // We want to keep scanning frames until text is found or timeout.
                }

                val inferenceTime = SystemClock.uptimeMillis() - startTime
                
                val result = DetectedObjectsResult(
                    objects = displayObjects,
                    inferenceTime = inferenceTime,
                    inputImageWidth = width,
                    inputImageHeight = height
                )

                // OPTIMIZATION: Skipped bitmap extraction as requested to avoid crashes and save performance.
                resultsListener?.onObjectsDetected(result, image)

            } catch (e: Exception) {
                Log.e(TAG, "Error during OCR detection", e)
                // We only call finish on error to unblock the state machine if something breaks
                onAudioFinishedPlaying?.invoke()
            } finally {
                bgrMat?.release()
                rgbaMat?.release()
                cvFrame?.release()
                finishedDetectingCallback.getAndSet(null)?.invoke()
            }
        }
    }

    fun stop() {
        piperJob?.cancel()
        detectorScope.cancel()

        synchronized(audioLock) {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        }

        ttsEngine?.release()
        ttsEngine = null
    }
}
