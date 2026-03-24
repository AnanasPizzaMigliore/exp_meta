// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.pixelandtexel.scanner.objectdetection

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Bitmap
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.View.GONE
import android.widget.TextView
import com.meta.pixelandtexel.scanner.BuildConfig
import com.meta.pixelandtexel.scanner.R
import com.meta.pixelandtexel.scanner.ViewLocked
import com.meta.pixelandtexel.scanner.objectdetection.camera.CameraController
import com.meta.pixelandtexel.scanner.objectdetection.camera.enums.CameraStatus
import com.meta.pixelandtexel.scanner.objectdetection.camera.models.CameraProperties
import com.meta.pixelandtexel.scanner.objectdetection.detector.ExecutorchOcrDetector
import com.meta.pixelandtexel.scanner.objectdetection.detector.IObjectDetectorHelper
import com.meta.pixelandtexel.scanner.objectdetection.detector.IObjectsDetectedListener
import com.meta.pixelandtexel.scanner.objectdetection.detector.VisualKinematicProxy
import com.meta.pixelandtexel.scanner.objectdetection.detector.models.DetectedObjectsResult
import com.meta.pixelandtexel.scanner.objectdetection.utils.NumberSmoother
import com.meta.pixelandtexel.scanner.objectdetection.views.android.CameraPreview
import com.meta.pixelandtexel.scanner.objectdetection.views.android.GraphicOverlay
import com.meta.pixelandtexel.scanner.objectdetection.views.android.ISurfaceProvider
import com.meta.spatial.core.ComponentRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SendRate
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.createPanelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.graphics.createBitmap

class ObjectDetectionFeature(
  private val activity: AppSystemActivity,
  private val onStatusChanged: ((CameraStatus) -> Unit)? = null,
  // --- UPDATED: Passing Bitmap for benchmarking/saving ---
  private val onDetectedObjects: ((DetectedObjectsResult, Bitmap?) -> Unit)? = null,
  private val onScanCompleted: (() -> Unit)? = null,
  private val spawnCameraViewPanel: Boolean = false,
) : SpatialFeature, IObjectsDetectedListener, CameraController.ImageAvailableListener {

  companion object {
    private const val TAG = "ObjectDetectionFeature"
  }

  // our core services
  private val cameraController: CameraController = CameraController(activity)

  // Notice we explicitly cast this to ExecutorchOcrDetector so we can access the callback
  private val objectDetector: ExecutorchOcrDetector

  // --- NEW: THE VISUAL KINEMATIC PROXY GATE ---
  private val visualProxy = VisualKinematicProxy(
    motionThreshold = 6.0f,
    blurThreshold = 100.0
  )

  // systems
  private lateinit var viewLockedSystem: ViewLockedSystem

  // status ui
  private var cameraStatusRootView: View? = null
  private var cameraStatusText: TextView? = null
  private var _cameraStatus: CameraStatus = CameraStatus.PAUSED
  val status: CameraStatus
    get() = _cameraStatus

  private lateinit var cameraStatusEntity: Entity

  // debug ui
  private var cameraPreviewView: CameraPreview? = null
  private var graphicOverlayView: GraphicOverlay? = null
  private val smoothedInferenceTime = NumberSmoother()
  private lateinit var cameraViewEntity: Entity

  init {
    cameraController.onCameraPropertiesChanged += ::onCameraPropertiesChanged

    objectDetector = ExecutorchOcrDetector(activity)
    objectDetector.setObjectDetectedListener(this)

    // --- WIRE THE AUDIO CALLBACK HERE ---
    objectDetector.onAudioFinishedPlaying = {
      Log.d(TAG, "Audio finished playing. Notifying MainActivity...")
      onScanCompleted?.invoke()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    activity.registerPanel(
      PanelRegistration(R.layout.ui_camera_view) {
        val cameraOutputSize = cameraController.cameraOutputSize

        config {
          themeResourceId = R.style.PanelAppThemeTransparent
          includeGlass = false
          layoutWidthInPx = cameraOutputSize.width
          layoutHeightInPx = cameraOutputSize.height
          width = cameraOutputSize.width / (PanelConfigOptions.EYEBUFFER_WIDTH * 0.5f)
          height = cameraOutputSize.height / (PanelConfigOptions.EYEBUFFER_HEIGHT * 0.5f)
          layerConfig = LayerConfig()
          layerBlendType = PanelShapeLayerBlendType.MASKED
          enableLayerFeatheredEdge = true
        }
        panel {
          cameraPreviewView = rootView?.findViewById(R.id.preview_view)
          graphicOverlayView = rootView?.findViewById(R.id.graphic_overlay)

          if (cameraPreviewView?.visibility == GONE) {
            cameraPreviewView = null
          }

          this@ObjectDetectionFeature.scan()
        }
      }
    )

    activity.registerPanel(
      PanelRegistration(R.layout.ui_camera_status_view) {
        config {
          themeResourceId = R.style.PanelAppThemeTransparent
          includeGlass = false
          layoutWidthInDp = 100f
          width = 0.1f
          height = 0.05f
          layerConfig = LayerConfig()
          layerBlendType = PanelShapeLayerBlendType.MASKED
          enableLayerFeatheredEdge = true
        }
        panel {
          cameraStatusRootView = rootView
          cameraStatusText =
            rootView?.findViewById(R.id.camera_status)
              ?: throw RuntimeException("Missing camera status text view")
        }
      }
    )
  }

  override fun systemsToRegister(): List<SystemBase> {
    val systems = mutableListOf<SystemBase>()

    viewLockedSystem = ViewLockedSystem()
    cameraController.onCameraPropertiesChanged += viewLockedSystem::onCameraPropertiesChanged
    systems.add(viewLockedSystem)

    return systems
  }

  override fun componentsToRegister(): List<ComponentRegistration> {
    return listOf(
      ComponentRegistration.createConfig<ViewLocked>(ViewLocked.Companion, SendRate.DEFAULT)
    )
  }

  override fun onSceneReady() {
    cameraStatusEntity =
      Entity.createPanelEntity(
        R.layout.ui_camera_status_view,
        Transform(),
        Hittable(MeshCollision.NoCollision),
        ViewLocked(Vector3(-0.16f, 0.15f, 0.7f), Vector3(0f), false),
      )
  }

  fun scan() {
    if (cameraController.isInitialized) {
      cameraController.start(
        surfaceProviders = listOfNotNull(cameraPreviewView as? ISurfaceProvider),
        imageAvailableListener = this,
      )
      updateCameraStatus(CameraStatus.SCANNING)
      return
    }

    cameraController.initialize()
  }

  fun pause(immediate: Boolean = false) {
    if (!cameraController.isInitialized || !cameraController.isRunning) {
      return
    }

    cameraController.stop()
    updateCameraStatus(CameraStatus.PAUSED)

    if (immediate) {
      graphicOverlayView?.clear()
    } else {
      CoroutineScope(Dispatchers.Main).launch {
        delay(100L)
        graphicOverlayView?.clear()
      }
    }
  }

  private fun onCameraPropertiesChanged(properties: CameraProperties) {
    if (!spawnCameraViewPanel) {
      scan()
      return
    }

    if (::cameraViewEntity.isInitialized) {
      return
    }

    val offsetPose = properties.getHeadToCameraPose()
    cameraViewEntity =
      Entity.createPanelEntity(
        R.layout.ui_camera_view,
        Transform(),
        Hittable(MeshCollision.NoCollision),
        ViewLocked(offsetPose.t, offsetPose.q.toEuler(), true),
      )
  }

  private fun updateCameraStatus(newStatus: CameraStatus) {
    if (_cameraStatus == newStatus) {
      return
    }

    // FIX: Wrap UI updates in runOnUiThread to avoid CalledFromWrongThreadException
    activity.runOnUiThread {
      when (newStatus) {
        CameraStatus.PAUSED -> {
          cameraStatusText?.setText(R.string.camera_status_off)
        }

        CameraStatus.SCANNING -> {
          cameraStatusText?.setText(R.string.camera_status_on)
        }
      }

      cameraStatusRootView?.let {
        val durationMs = 250L
        val kf0 = Keyframe.ofFloat(0f, 1f)
        val kf1 = Keyframe.ofFloat(0.5f, 1.5f)
        val kf2 = Keyframe.ofFloat(1f, 1f)
        val pvhScaleX = PropertyValuesHolder.ofKeyframe("scaleX", kf0, kf1, kf2)
        val pvhScaleY = PropertyValuesHolder.ofKeyframe("scaleY", kf0, kf1, kf2)
        ObjectAnimator.ofPropertyValuesHolder(it, pvhScaleX, pvhScaleY).apply {
          duration = durationMs
          start()
        }
      }
    }

    _cameraStatus = newStatus
    onStatusChanged?.invoke(newStatus)
  }

  private fun extractGrayscaleBitmap(image: Image): Bitmap? {
    return try {
      val planes = image.planes
      if (planes == null || planes.isEmpty()) {
        return null
      }
      val yBuffer = planes[0].buffer
      val ySize = yBuffer.remaining()
      if (ySize == 0) {
        return null
      }
      
      val yArray = ByteArray(ySize)
      yBuffer.get(yArray)

      val width = image.width
      val height = image.height
      val bitmap = createBitmap(width, height)
      val pixels = IntArray(width * height)

      // Use the actual stride information if available for robustness
      val rowStride = planes[0].rowStride
      val pixelStride = planes[0].pixelStride

      for (row in 0 until height) {
        for (col in 0 until width) {
          val index = row * rowStride + col * pixelStride
          if (index < yArray.size) {
            val y = yArray[index].toInt() and 0xff
            pixels[row * width + col] = 0xff000000.toInt() or (y shl 16) or (y shl 8) or y
          }
        }
      }

      bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
      bitmap
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract grayscale bitmap", e)
      null
    }
  }

  override fun onNewImage(image: Image, width: Int, height: Int, finally: () -> Unit) {
    val proxyBitmap = extractGrayscaleBitmap(image)

    if (proxyBitmap == null || !visualProxy.isIntentDetected(proxyBitmap)) {
      proxyBitmap?.recycle()

      // --- ADD THIS LOG ---
      Log.d(TAG, "Frame rejected by Visual Proxy (Motion or Blur).")

      finally()
      return
    }

    proxyBitmap.recycle()
    // [LATENCY_BENCHMARK] T1: Frame Stabilized. The moment your Software IMU confirms a sharp, stable frame
    Log.d("LATENCY_BENCHMARK", "T1: Frame Stabilized (Visual Proxy passed)")
    Log.d(TAG, "🎯 VISUAL PROXY PASSED: Scene stable. Waking up ExecuTorch...")

    objectDetector.detect(image, width, height, finally)
  }

  override fun onObjectsDetected(result: DetectedObjectsResult, image: Image) {
    if (spawnCameraViewPanel) {
      // OPTIMIZATION: Skipped drawing text bounding boxes as requested to avoid crashes.
      /*
      graphicOverlayView?.drawResults(
        result.objects,
        result.inputImageWidth,
        result.inputImageHeight,
      )
      */
    }

    if (BuildConfig.DEBUG) {
      smoothedInferenceTime.update(result.inferenceTime)
      val smoothedTime = smoothedInferenceTime.getSmoothedNumber().toInt()
      graphicOverlayView?.drawStats("inference time: $smoothedTime ms")
    }

    // Extract bitmap for listener (e.g. for benchmarking/saving)
    val bitmap = extractGrayscaleBitmap(image)
    onDetectedObjects?.invoke(result, bitmap)
  }

  override fun onPauseActivity() {
    pause()
    super.onPauseActivity()
  }

  override fun onDestroy() {
    pause(true)
    cameraController.dispose()

    if (::cameraViewEntity.isInitialized) {
      cameraViewEntity.destroy()
    }
    if (::cameraStatusEntity.isInitialized) {
      cameraStatusEntity.destroy()
    }
    super.onDestroy()
  }
}
