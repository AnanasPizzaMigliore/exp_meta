package com.meta.pixelandtexel.scanner.objectdetection.detector

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VoiceActivator(private val context: Context, private val onScanCommand: () -> Unit) {
    private val TAG = "VoiceActivator"

    private var kws: KeywordSpotter? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listeningJob: Job? = null

    private val modelMutex = Mutex()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = 1600

    init {
        initModel()
    }

    private fun initModel() {
        coroutineScope.launch {
            modelMutex.withLock {
                if (kws != null) return@withLock

                try {
                    val featConfig = getFeatureConfig(sampleRate = sampleRate, featureDim = 80)

                    val modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "models/kws/encoder.onnx",
                            decoder = "models/kws/decoder.onnx",
                            joiner = "models/kws/joiner.onnx"
                        ),
                        tokens = "models/kws/tokens.txt",
                        numThreads = 1,
                        debug = true,
                        // FIX 1: Tell Sherpa exactly what model it is loading to skip the 3-second delay
                        modelType = "zipformer2"
                    )

                    val config = KeywordSpotterConfig(
                        featConfig = featConfig,
                        modelConfig = modelConfig,
                        keywordsFile = "models/kws/keywords.txt"
                    )

                    Log.d(TAG, "Attempting to load Sherpa-ONNX model...")
                    kws = KeywordSpotter(assetManager = context.assets, config = config)
                    Log.d(TAG, "SUCCESS: Sherpa-ONNX Keyword Spotter initialized.")
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Failed to load Keyword Spotter", e)
                }
            }
        }
    }

    // REMOVED @SuppressLint. You MUST ensure Manifest.permission.RECORD_AUDIO
    // is requested at runtime via your Activity!
    fun startListening() {
        Log.d(TAG, "Starting VoiceActivator...")

        listeningJob?.cancel()

        listeningJob = coroutineScope.launch {
            var localAudioRecord: AudioRecord? = null
            var localStream: OnlineStream? = null

            try {
                modelMutex.withLock {
                    if (kws == null) {
                        Log.w(TAG, "Model not initialized yet. Aborting start.")
                        return@launch
                    }
                    localStream = kws?.createStream()
                }

                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                // FIX 2: Use VOICE_RECOGNITION for un-gated VR microphone access
                try {
                    localAudioRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        maxOf(minBufferSize * 2, bufferSize * 2)
                    )
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException: Missing RECORD_AUDIO permission", e)
                    return@launch
                }

                if (localAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize. Do you have RECORD_AUDIO permission?")
                    return@launch
                }

                localAudioRecord.startRecording()
                Log.d(TAG, "Microphone is HOT. Listening for 'Scan'...")

                val shortBuffer = ShortArray(bufferSize)
                val floatBuffer = FloatArray(bufferSize)

                while (isActive) {
                    val readCount = localAudioRecord.read(shortBuffer, 0, shortBuffer.size)

                    if (readCount > 0 && isActive) {
                        for (i in 0 until readCount) {
                            floatBuffer[i] = shortBuffer[i] / 32768.0f
                        }

                        val audioData = if (readCount == bufferSize) floatBuffer else floatBuffer.sliceArray(0 until readCount)

                        modelMutex.withLock {
                            localStream?.let { stream ->
                                stream.acceptWaveform(audioData, sampleRate = sampleRate)

                                while (kws?.isReady(stream) == true) {
                                    kws?.decode(stream)
                                }

                                val result = kws?.getResult(stream)
                                if (result != null && result.keyword.isNotBlank()) {
                                    // [LATENCY_BENCHMARK] T0: STT Complete. The moment the SpeechRecognizer recognizes the wake-word
                                    Log.d("LATENCY_BENCHMARK", "T0: STT Complete (Wake-word recognized: ${result.keyword})")

                                    stream.release()
                                    localStream = kws?.createStream()

                                    withContext(Dispatchers.Main) {
                                        onScanCommand()
                                    }
                                }
                            }
                        }
                    } else if (readCount < 0) {
                        // FIX 3: Expose the silent killer!
                        Log.e(TAG, "AudioRecord read error: $readCount. Check permissions!")
                    }
                }
            } catch (_: CancellationException) {
                Log.d(TAG, "VoiceActivator coroutine was cleanly cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "Exception in audio loop", e)
            } finally {
                withContext(NonCancellable) {
                    localAudioRecord?.let {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            try { it.stop() } catch (_: Exception) {}
                        }
                        it.release()
                    }

                    modelMutex.withLock {
                        localStream?.release()
                    }
                    Log.d(TAG, "Microphone and stream gracefully shut down.")
                }
            }
        }
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
    }

    fun destroy() {
        stopListening()
        coroutineScope.launch {
            withContext(NonCancellable) {
                modelMutex.withLock {
                    kws?.release()
                    kws = null
                }
                coroutineScope.cancel()
            }
        }
    }
}
