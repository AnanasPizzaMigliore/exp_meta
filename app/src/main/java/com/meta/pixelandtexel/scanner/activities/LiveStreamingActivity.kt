package com.meta.pixelandtexel.scanner.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import java.util.concurrent.Executor

class LiveStreamingActivity : ComponentActivity() {

    private lateinit var h264Encoder: H264SurfaceEncoder
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize the Hardware Encoder
        h264Encoder = H264SurfaceEncoder(width = 1280, height = 720) { h264Packet ->
            // TODO: Pass this ByteArray to your UDP socket, WebRTC, or RTSP server!
            // videoServer.broadcast(h264Packet)
        }

        // 2. Check Permissions & Start
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startStreamingPipeline()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1000)
        }
    }

    private fun startStreamingPipeline() {
        h264Encoder.start()
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("StreamingActivity", "Failed to open camera", e)
        }
    }

    private fun createCameraCaptureSession() {
        val encoderSurface = h264Encoder.inputSurface ?: return
        val camera = cameraDevice ?: return

        try {
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(encoderSurface)

            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    // Start the continuous zero-copy stream
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("StreamingActivity", "Failed to configure capture session")
                }
            }

            // Modern API (Android 9.0 / API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfig = OutputConfiguration(encoderSurface)
                val executor = Executor { command -> backgroundHandler.post(command) }

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    executor,
                    stateCallback
                )

                camera.createCaptureSession(sessionConfig)
            } else {
                // Fallback for older legacy devices
                @Suppress("DEPRECATION")
                camera.createCaptureSession(
                    listOf(encoderSurface),
                    stateCallback,
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Log.e("StreamingActivity", "Error creating capture session", e)
        }
    }

    override fun onDestroy() {
        captureSession?.close()
        cameraDevice?.close()
        h264Encoder.stop()
        backgroundThread.quitSafely()
        super.onDestroy()
    }
}