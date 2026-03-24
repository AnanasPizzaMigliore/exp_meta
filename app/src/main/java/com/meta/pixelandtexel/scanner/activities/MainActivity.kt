// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.pixelandtexel.scanner.activities

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.pixelandtexel.scanner.Outlined
import com.meta.pixelandtexel.scanner.R
import com.meta.pixelandtexel.scanner.WristAttached
import com.meta.pixelandtexel.scanner.ecs.OutlinedSystem
import com.meta.pixelandtexel.scanner.ecs.WristAttachedSystem
import com.meta.pixelandtexel.scanner.models.ObjectInfoRequest
import com.meta.pixelandtexel.scanner.objectdetection.ObjectDetectionFeature
import com.meta.pixelandtexel.scanner.objectdetection.camera.enums.CameraStatus
import com.meta.pixelandtexel.scanner.objectdetection.detector.KinematicTrigger
import com.meta.pixelandtexel.scanner.objectdetection.detector.VoiceActivator
import com.meta.pixelandtexel.scanner.objectdetection.detector.models.DetectedObjectsResult
import com.meta.pixelandtexel.scanner.services.TipManager
import com.meta.pixelandtexel.scanner.services.UserEvent
import com.meta.pixelandtexel.scanner.services.settings.SettingsService
import com.meta.pixelandtexel.scanner.viewmodels.ObjectInfoViewModel
import com.meta.pixelandtexel.scanner.views.objectinfo.ObjectInfoScreen
import com.meta.pixelandtexel.scanner.views.welcome.WelcomeScreen
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.composePanel
import com.meta.spatial.compose.panelViewLifecycleOwner
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SendRate
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ActivityCompat.OnRequestPermissionsResultCallback, AppSystemActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE = 1000
        private const val BENCHMARK_FOLDER_REQUEST_CODE = 2000

        private val PERMISSIONS_REQUIRED = arrayOf(
            "horizonos.permission.HEADSET_CAMERA",
            "horizonos.permission.HAND_TRACKING",
            "horizonos.permission.USE_ANCHOR_API",
            "horizonos.permission.USE_SCENE",
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private var gltfxEntity: Entity? = null
    private val activityScope = CoroutineScope(Dispatchers.Main)
    private var cameraControlsBtn: ImageButton? = null
    private var welcomePanelEntity: Entity? = null
    private var infoPanelEntity: Entity? = null
    private var pendingInfoRequest: ObjectInfoRequest? = null
    private var pendingIsBenchmark = false
    private var selectedBenchmarkFolderUri: Uri? = null

    private lateinit var objectDetectionFeature: ObjectDetectionFeature
    private lateinit var tipManager: TipManager
    private lateinit var batteryManager: BatteryManager

    // Hardware Sensors & Audio
    private var voiceActivator: VoiceActivator? = null
    private lateinit var kinematicTrigger: KinematicTrigger

    // State Guards & Jobs
    private var scanTimeoutJob: Job? = null
    private var isScanInProgress = false

    private val debugToneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
    private var isHandRaised = false

    override fun registerFeatures(): List<SpatialFeature> {
        objectDetectionFeature =
            ObjectDetectionFeature(
                this,
                onStatusChanged = ::onObjectDetectionFeatureStatusChanged,
                onDetectedObjects = { result, bitmap -> onObjectsDetected(result, bitmap) },
                onScanCompleted = {
                    activityScope.launch {
                        Log.d(TAG, "Scan cycle complete. Returning to Roaming Mode.")
                        enterRoamingMode()
                    }
                }
            )

        return listOf(VRFeature(this), ComposeFeature(), objectDetectionFeature)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMissingPermissions()

        SettingsService.initialize(this)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager

        NetworkedAssetLoader.init(File(applicationContext.cacheDir.canonicalPath), OkHttpAssetFetcher())

        tipManager = TipManager(this) {
            enterRoamingMode()
        }

        // --- LEVEL 0: KINEMATIC AWARE POWER OPTIMIZATION (WRIST DWELL) ---
        kinematicTrigger = KinematicTrigger(activityScope, { getScannerWristPosition() }) {
            activityScope.launch {
                if (objectDetectionFeature.status == CameraStatus.PAUSED && !isScanInProgress) {
                    if (hasCameraPermission()) {
                        Log.d(TAG, "Kinematic dwell detected! Entering scanning phase.")
                        startScanning()
                    } else {
                        requestMissingPermissions()
                    }
                }
            }
        }

        // --- LEVEL 0: VOICE ACTIVATOR CALLBACK ---
        voiceActivator = VoiceActivator(this) {
            activityScope.launch {
                if (objectDetectionFeature.status == CameraStatus.PAUSED && !isScanInProgress) {
                    if (hasCameraPermission()) {
                        Log.d(TAG, "Voice command received! Entering scanning phase.")
                        startScanning()
                    } else {
                        Log.w(TAG, "Voice command ignored: Missing Camera Permission.")
                        requestMissingPermissions()
                    }
                }
            }
        }

        systemManager.unregisterSystem<LocomotionSystem>()
        componentManager.registerComponent<WristAttached>(WristAttached.Companion, SendRate.DEFAULT)
        systemManager.registerSystem(WristAttachedSystem())
        componentManager.registerComponent<Outlined>(Outlined.Companion, SendRate.DEFAULT)
        systemManager.registerSystem(OutlinedSystem(this))

        loadGLXF().invokeOnCompletion {
            val composition = glXFManager.getGLXFInfo("scanner_app_main_scene")
            welcomePanelEntity = composition.getNodeByName("WelcomePanel").entity
        }
    }

    private fun requestMissingPermissions() {
        val missingPermissions = PERMISSIONS_REQUIRED.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions, PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (hasAudioPermission() && objectDetectionFeature.status == CameraStatus.PAUSED) {
                Log.d(TAG, "Audio permission granted! Booting up VoiceActivator.")
                voiceActivator?.startListening()
            }
        }
    }

    private fun getScannerWristPosition(): Vector3? {
        // Query the player body directly to get raw hand positions
        val playerBody = Query.where { has(AvatarBody.id) }
            .eval()
            .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
            ?.getComponent<AvatarBody>() ?: return null

        var highestY = -Float.MAX_VALUE
        var highestPos: Vector3? = null

        // Check Left Hand
        if (playerBody.leftHand.hasComponent<Transform>()) {
            val pos = playerBody.leftHand.getComponent<Transform>().transform.t
            if (pos.y > highestY) {
                highestY = pos.y
                highestPos = pos
            }
        }

        // Check Right Hand
        if (playerBody.rightHand.hasComponent<Transform>()) {
            val pos = playerBody.rightHand.getComponent<Transform>().transform.t
            if (pos.y > highestY) {
                highestY = pos.y
                highestPos = pos
            }
        }

        highestPos?.let { pos ->
            if (pos.y > 0.85f && !isHandRaised) {
                isHandRaised = true
                debugToneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                Log.d(TAG, "HAND RAISED! Y-Height is: ${pos.y}")
            } else if (pos.y <= 0.85f) {
                isHandRaised = false
            }
        }

        return highestPos
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, "horizonos.permission.HEADSET_CAMERA") == PackageManager.PERMISSION_GRANTED
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.setLightingEnvironment(
            ambientColor = Vector3(0f),
            sunColor = Vector3(0f),
            sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
            environmentIntensity = 0.2f,
        )
        scene.updateIBLEnvironment("museum_lobby.env")
        scene.setViewOrigin(0.0f, 0.0f, 0.0f, 180.0f)
        scene.enablePassthrough(true)

        // OPTIMIZATION: Stagger trigger initialization to avoid CPU spike during scene load
        activityScope.launch {
            delay(2000L)
            if (confirmTrackedObjectSelected()) {
                enterRoamingMode()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BENCHMARK_FOLDER_REQUEST_CODE && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                selectedBenchmarkFolderUri = uri
                pendingIsBenchmark = true
                Log.d(TAG, "Benchmark folder selected. URI: $uri")

                // BUG FIX: Removed startScanning() from here to prevent the state machine
                // from instantly destroying the benchmark panel.
                showInfoPanelForObject("Benchmark Mode")
            }
        }
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            PanelRegistration(R.integer.welcome_panel_id) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    layoutWidthInDp = 368f
                    width = 0.368f
                    height = 0.404f
                    layerConfig = LayerConfig()
                    layerBlendType = PanelShapeLayerBlendType.MASKED
                    enableLayerFeatheredEdge = true
                }
                composePanel {
                    setContent {
                        CompositionLocalProvider(
                            LocalOnBackPressedDispatcherOwner provides
                                    object : OnBackPressedDispatcherOwner {
                                        override val lifecycle: Lifecycle
                                            get() = this@MainActivity.panelViewLifecycleOwner.lifecycle
                                        override val onBackPressedDispatcher: OnBackPressedDispatcher
                                            get() = OnBackPressedDispatcher()
                                    }
                        ) {
                            WelcomeScreen(
                                onLinkClicked = {
                                    val uri = it.toUri()
                                    val browserIntent = Intent(Intent.ACTION_VIEW, uri)
                                    startActivity(browserIntent)
                                }
                            ) {
                                startScanning()
                            }
                        }
                    }
                }
            },
            PanelRegistration(R.layout.ui_help_button_view) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    layoutWidthInDp = 80f
                    width = 0.04f
                    height = 0.04f
                    layerConfig = LayerConfig()
                    layerBlendType = PanelShapeLayerBlendType.MASKED
                    enableLayerFeatheredEdge = true
                }
                panel {
                    val helpBtn = rootView?.findViewById<ImageButton>(R.id.help_btn)
                        ?: throw RuntimeException("Missing help button")
                    helpBtn.setOnClickListener {
                        welcomePanelEntity?.destroy()
                        welcomePanelEntity = null

                        objectDetectionFeature.pause()
                        kinematicTrigger.stopListening()
                        voiceActivator?.stopListening()
                        scanTimeoutJob?.cancel()

                        dismissInfoPanel()
                        tipManager.dismissTipPanels()
                        tipManager.showHelpPanel()
                        isScanInProgress = false
                    }
                }
            },
            PanelRegistration(R.layout.ui_camera_controls_view) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    layoutWidthInDp = 260f
                    width = 0.13f
                    height = 0.04f
                    layerConfig = LayerConfig()
                    layerBlendType = PanelShapeLayerBlendType.MASKED
                    enableLayerFeatheredEdge = true
                }
                panel {
                    cameraControlsBtn = rootView?.findViewById(R.id.camera_play_btn)
                        ?: throw RuntimeException("Missing camera play/pause button")

                    val benchmarkBtn = rootView?.findViewById<Button>(R.id.btnSelectFolder)
                    benchmarkBtn?.setOnClickListener {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        startActivityForResult(intent, BENCHMARK_FOLDER_REQUEST_CODE)
                    }

                    cameraControlsBtn?.setOnClickListener {
                        when (objectDetectionFeature.status) {
                            CameraStatus.PAUSED -> {
                                if (hasCameraPermission()) startScanning() else requestMissingPermissions()
                            }
                            CameraStatus.SCANNING -> {
                                enterRoamingMode()
                            }
                        }
                    }
                }
            },
            PanelRegistration(R.integer.info_panel_id) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    includeGlass = false
                    layoutWidthInDp = 632f
                    width = 0.632f
                    height = 0.644f
                    layerConfig = LayerConfig()
                    layerBlendType = PanelShapeLayerBlendType.MASKED
                    enableLayerFeatheredEdge = true
                }
                composePanel {
                    // Caching values from the Activity into the closure of the panel instance
                    val currentRequest = pendingInfoRequest ?: ObjectInfoRequest("Unknown", null)
                    val template = getString(R.string.object_query_template)
                    val currentIsBenchmark = pendingIsBenchmark
                    val currentBenchmarkUri = selectedBenchmarkFolderUri

                    // ONLY clear these if we actually found something to consume
                    if (pendingInfoRequest != null) {
                        Log.d(TAG, "Panel content initializing. Consumption of pending state...")
                        pendingInfoRequest = null
                        pendingIsBenchmark = false
                        // We keep selectedBenchmarkFolderUri for live logging if needed,
                        // but usually clearing it prevents accidental reuse.
                    }

                    setContent {
                        val vm: ObjectInfoViewModel = viewModel(
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    Log.d(TAG, "ViewModel Factory called. isBenchmark: $currentIsBenchmark")
                                    return ObjectInfoViewModel(
                                        currentRequest,
                                        template,
                                        currentIsBenchmark,
                                        currentBenchmarkUri,
                                        application
                                    ) as T
                                }
                            }
                        )

                        ObjectInfoScreen(
                            vm = vm,
                            onResume = {
                                startScanning()
                                tipManager.reportUserEvent(UserEvent.DISMISSED_INFO_PANEL)
                            },
                            onClose = {
                                dismissInfoPanel()
                                enterRoamingMode()
                                tipManager.reportUserEvent(UserEvent.DISMISSED_INFO_PANEL)
                            },
                        )
                    }
                }
            }
        )
    }

    private fun dismissInfoPanel() {
        infoPanelEntity?.destroy()
        infoPanelEntity = null
    }

    /**
     * STATE 1: ROAMING MODE (Lowest Power)
     * Camera/OCR is OFF. Kinematic and Voice triggers are ON.
     */
    private fun enterRoamingMode() {
        Log.d(TAG, "Entering Roaming Mode. Waiting for intent to scan...")

        isScanInProgress = false
        scanTimeoutJob?.cancel()
        objectDetectionFeature.pause()

        kinematicTrigger.startListening()
        if (hasAudioPermission()) {
            voiceActivator?.startListening()
        }
    }

    /**
     * STATE 2: SCANNING MODE (Medium Power)
     * Intent detected. Camera ON. Waiting for Visual Proxy to validate frame.
     */
    private fun startScanning() {
        if (isScanInProgress) return
        isScanInProgress = true

        Log.d(TAG, "Executing Scan...")

        // Turn OFF Level 0 triggers to prevent interference
        kinematicTrigger.stopListening()
        voiceActivator?.stopListening()

        welcomePanelEntity?.destroy()
        welcomePanelEntity = null
        dismissInfoPanel()
        tipManager.dismissTipPanels()

        objectDetectionFeature.scan()
        tipManager.reportUserEvent(UserEvent.STARTED_SCANNING)

        // --- SAFETY TIMEOUT ---
        scanTimeoutJob?.cancel()
        scanTimeoutJob = activityScope.launch {
            delay(6000L)
            Log.w(TAG, "⏱️ Scan timeout! No stable image found. Returning to sleep.")
            enterRoamingMode()
        }
    }

    /**
     * STATE 3: READING MODE (OCR Completed)
     * Camera is halted so user can read the panel.
     */
    private fun showInfoPanelForObject(name: String, bitmap: Bitmap? = null) {
        scanTimeoutJob?.cancel()

        // 1. Turn off the camera and sensors
        objectDetectionFeature.pause()
        kinematicTrigger.stopListening()
        voiceActivator?.stopListening()
        tipManager.dismissTipPanels()

        // 2. Hand the data off to the OCR/TTS pipeline
        pendingInfoRequest = ObjectInfoRequest(name, bitmap)

        // Spawn the info panel in front of the user
        infoPanelEntity = Entity.createPanelEntity(
            R.integer.info_panel_id,
            Transform(getPanelSpawnPose())
        )

        tipManager.reportUserEvent(UserEvent.SELECTED_OBJECT)

        // 3. Auto-Reset back to Roaming Mode after audio finishes
        // We rely on the onScanCompleted callback from ObjectDetectionFeature which is triggered when audio ends.
    }

    private fun getPanelSpawnPose(): Pose {
        val headEntity =
            Query.where { has(AvatarAttachment.id) }
                .eval()
                .filter { it.isLocal() && it.getComponent<AvatarAttachment>().type == "head" }
                .firstOrNull() ?: return Pose(Vector3(0f, 1.2f, -1f), Quaternion())

        val headTransform = headEntity.getComponent<Transform>().transform
        // apply offset to lower the panel to eye height
        val headPosition = headTransform.t - Vector3(0f, 0.1f, 0f)

        val xzForward = (headTransform.forward() * Vector3(1f, 0f, 1f)).normalize()

        // 1 meters in front of the user at eye height, with yaw rotation towards head
        val position = headPosition + xzForward * 1f
        val rotation = Quaternion.lookRotationAroundY(position - headPosition)

        return Pose(position, rotation)
    }

    private fun onObjectDetectionFeatureStatusChanged(newStatus: CameraStatus) {
        cameraControlsBtn?.setBackgroundResource(
            when (newStatus) {
                CameraStatus.PAUSED -> com.meta.spatial.uiset.R.drawable.ic_play_circle_24
                CameraStatus.SCANNING -> com.meta.spatial.uiset.R.drawable.ic_pause_circle_24
            }
        )
    }

    private fun onObjectsDetected(result: DetectedObjectsResult, bitmap: Bitmap?) {
        if (result.objects.any()) {
            Log.d(TAG, "Valid text found! Pausing camera to read.")

            // OPTIMIZATION: Explicitly stop triggers here to prevent "phantom" detections during playback
            kinematicTrigger.stopListening()
            voiceActivator?.stopListening()

            // This resets the guard as we hand off to the TTS logic
            isScanInProgress = false

            val firstLabel = result.objects.first().label

            // --- LIVE BENCHMARK LOGGING ---
            val benchmarkUri = selectedBenchmarkFolderUri
            if (benchmarkUri != null && bitmap != null) {
                saveBenchmarkEntry(benchmarkUri, result, bitmap)
            }

            showInfoPanelForObject(
                name = firstLabel,
                bitmap = bitmap
            )
        }
    }

    private fun saveBenchmarkEntry(folderUri: Uri, result: DetectedObjectsResult, bitmap: Bitmap) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val directory = DocumentFile.fromTreeUri(this@MainActivity, folderUri) ?: return@launch
                val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
                val outputDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

                // 1. Save Image
                val imageName = "capture_$timestampStr.jpg"
                val imageFile = directory.createFile("image/jpeg", imageName)
                if (imageFile != null) {
                    contentResolver.openOutputStream(imageFile.uri)?.use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                }

                // 2. Battery Stats
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                val temperature = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
                val flowUa = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val remainUah = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

                // 3. Energy Calculation: Joules = V * I * t
                // V in Volts, I in Amps, t in Seconds
                val voltageV = voltageMv / 1000.0
                val flowA = Math.abs(flowUa) / 1000000.0
                val timeS = result.inferenceTime / 1000.0
                val energyJoules = voltageV * flowA * timeS

                // 4. Save CSV Row
                val csvFile = directory.findFile("benchmark_log.csv") ?: directory.createFile("text/comma-separated-values", "benchmark_log.csv")
                if (csvFile != null) {
                    val header = "filename,date,inference_time_ms,energy_joules,temperature_c,voltage_mv,flow_ua,remain_energy_uah\n"
                    val row = "$imageName,$outputDate,${result.inferenceTime},$energyJoules,$temperature,$voltageMv,$flowUa,$remainUah\n"

                    val exists = contentResolver.openInputStream(csvFile.uri)?.use { it.available() > 0 } ?: false

                    contentResolver.openOutputStream(csvFile.uri, "wa")?.use {
                        OutputStreamWriter(it).use { writer ->
                            if (!exists) writer.write(header)
                            writer.write(row)
                        }
                    }
                }

                Log.d(TAG, "Benchmark entry saved: $imageName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save benchmark entry", e)
            }
        }
    }

    private fun confirmTrackedObjectSelected(): Boolean {
        return pendingInfoRequest == null && infoPanelEntity == null
    }

    override fun onPause() {
        scanTimeoutJob?.cancel()
        kinematicTrigger.stopListening()
        objectDetectionFeature.pause()
        voiceActivator?.stopListening()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // OPTIMIZATION: Moved enterRoamingMode trigger to onSceneReady to ensure stable environment
    }

    override fun onDestroy() {
        scanTimeoutJob?.cancel()
        kinematicTrigger.stopListening()
        voiceActivator?.destroy()
        super.onDestroy()
    }

    private fun loadGLXF(): Job {
        gltfxEntity = Entity.create()
        return activityScope.launch {
            glXFManager.inflateGLXF(
                "apk:///scenes/Composition.glxf".toUri(),
                rootEntity = gltfxEntity!!,
                keyName = "scanner_app_main_scene",
            )
        }
    }
}