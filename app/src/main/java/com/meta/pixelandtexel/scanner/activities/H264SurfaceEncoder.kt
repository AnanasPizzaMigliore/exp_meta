package com.meta.pixelandtexel.scanner.activities

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class H264SurfaceEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
    private val bitRate: Int = 2_000_000, // 2 Mbps
    private val onNetworkPacketReady: (ByteArray) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    fun start() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1-second keyframe interval
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            mediaCodec?.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {} // Ignored for Surface input

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        // Extract the encoded H.264 NAL unit
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buffer.get(data)

                        // Pass to the network layer
                        onNetworkPacketReady(data)
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e("H264Encoder", "Encoder Error", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.d("H264Encoder", "Output format changed: $format")
                }
            })

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

        } catch (e: Exception) {
            Log.e("H264Encoder", "Failed to start encoder", e)
        }
    }

    fun stop() {
        mediaCodec?.stop()
        mediaCodec?.release()
        inputSurface?.release()
        mediaCodec = null
        inputSurface = null
    }
}