package com.meta.pixelandtexel.scanner.objectdetection.detector

import android.util.Log
import com.meta.spatial.core.Vector3
import kotlinx.coroutines.*

class KinematicTrigger(
    private val scope: CoroutineScope,
    private val getWristPosition: () -> Vector3?,
    private val onDwellDetected: () -> Unit
) {
    companion object {
        private const val TAG = "KinematicTrigger"
    }

    private var isListening = false
    private var job: Job? = null

    private var anchorPosition: Vector3? = null
    private var dwellStartTime = 0L
    private var hasTriggered = false

    // --- "HOLDING" TUNING VARIABLES ---
    private val pollIntervalMs = 100L 
    private val minimumElevation = 0.85f // Hand must be above the waist
    private val dwellRadiusSq = 0.0016f  // 4cm bubble (0.04^2)
    private val requiredDwellTimeMs = 800L // 0.8 seconds in real-world time

    fun startListening() {
        if (isListening) return
        isListening = true
        dwellStartTime = 0L
        anchorPosition = null
        hasTriggered = false

        Log.d(TAG, "Level 0: Listening for 'Reading Pose' (Hand Raised & Anchored)")

        job = scope.launch(Dispatchers.Main) {
            while (isListening && isActive) {
                val currentPos = getWristPosition()

                if (!isListening || !isActive) break

                // ELEVATION GATE
                if (currentPos != null && currentPos.y > minimumElevation) {
                    val currentTime = System.currentTimeMillis()

                    if (hasTriggered) {
                        // Hand is still up after a previous trigger. Wait for reset.
                        delay(pollIntervalMs)
                        continue
                    }

                    if (anchorPosition == null) {
                        anchorPosition = currentPos
                        dwellStartTime = currentTime
                    } else {
                        val dx = currentPos.x - anchorPosition!!.x
                        val dy = currentPos.y - anchorPosition!!.y
                        val dz = currentPos.z - anchorPosition!!.z
                        val deltaSq = (dx * dx) + (dy * dy) + (dz * dz)

                        if (deltaSq < dwellRadiusSq) {
                            if (currentTime - dwellStartTime >= requiredDwellTimeMs) {
                                if (isListening) {
                                    Log.d(TAG, "🎯 READING POSE DETECTED! Waking up Camera.")
                                    hasTriggered = true // Require reset
                                    onDwellDetected()
                                }

                                anchorPosition = null
                                dwellStartTime = 0L
                            }
                        } else {
                            anchorPosition = currentPos
                            dwellStartTime = currentTime
                        }
                    }
                } else {
                    // Hand dropped below threshold. Reset the trigger guard.
                    anchorPosition = null
                    dwellStartTime = 0L
                    hasTriggered = false
                }

                delay(pollIntervalMs)
            }
        }
    }

    fun stopListening() {
        if (!isListening) return
        Log.d(TAG, "Level 0: Stopped listening for Kinematic Intent.")
        isListening = false
        job?.cancel()
        job = null
        anchorPosition = null
        dwellStartTime = 0L
    }
}