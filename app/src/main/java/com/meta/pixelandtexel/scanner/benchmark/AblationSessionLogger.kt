package com.meta.pixelandtexel.scanner.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AblationSessionLogger(private val context: Context) {

    enum class Mode { GATED, CONTINUOUS }

    data class SessionSummary(
        val mode: Mode,
        val durationS: Long,
        val deltaChargeMah: Long,
        val startTemperatureC: Float,
        val endTemperatureC: Float,
        val deltaTemperatureC: Float,
        val framesTotal: Int,
        val framesPassedGate: Int,
        val framesRejectedByGate: Int,
        val gatePassRate: Float
    )

    private data class BatterySample(
        val elapsedS: Long,
        val chargeMah: Long,
        val temperatureC: Float,
        val currentUa: Long
    )

    companion object {
        private const val TAG = "AblationSessionLogger"
    }

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var mode = Mode.GATED
    private var folderUri: Uri? = null
    private var sessionStartMs = 0L
    private var startChargeMah = 0L
    private var startTemperatureC = 0f
    private val timeline = mutableListOf<BatterySample>()

    val isActive: Boolean get() = sessionStartMs != 0L

    fun start(mode: Mode, folderUri: Uri) {
        this.mode = mode
        this.folderUri = folderUri
        sessionStartMs = SystemClock.elapsedRealtime()
        val initial = captureSnapshot(0L)
        startChargeMah = initial.chargeMah
        startTemperatureC = initial.temperatureC
        timeline.clear()
        timeline.add(initial)
        Log.d(TAG, "Session started. mode=$mode charge=${startChargeMah}mAh temp=${startTemperatureC}°C")
    }

    fun logTick() {
        if (!isActive) return
        val elapsed = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000L
        val sample = captureSnapshot(elapsed)
        timeline.add(sample)
        Log.d(TAG, "Tick @${elapsed}s  ${sample.chargeMah}mAh  ${sample.temperatureC}°C")
    }

    fun stop(framesTotal: Int, framesPassedGate: Int): SessionSummary {
        val elapsed = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000L
        val end = captureSnapshot(elapsed)
        timeline.add(end)
        sessionStartMs = 0L

        val summary = SessionSummary(
            mode = mode,
            durationS = elapsed,
            deltaChargeMah = startChargeMah - end.chargeMah,
            startTemperatureC = startTemperatureC,
            endTemperatureC = end.temperatureC,
            deltaTemperatureC = end.temperatureC - startTemperatureC,
            framesTotal = framesTotal,
            framesPassedGate = framesPassedGate,
            framesRejectedByGate = framesTotal - framesPassedGate,
            gatePassRate = if (framesTotal > 0) framesPassedGate.toFloat() / framesTotal else 0f
        )

        Log.d(TAG, "Session stopped. $summary")
        val uri = folderUri ?: return summary
        val snapTimeline = timeline.toList()
        CoroutineScope(Dispatchers.IO).launch { writeFiles(uri, summary, snapTimeline) }
        return summary
    }

    private fun captureSnapshot(elapsedS: Long): BatterySample {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
        val mah = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) / 1000
        val ua = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return BatterySample(elapsedS, mah, tempC, ua)
    }

    private fun writeFiles(folderUri: Uri, summary: SessionSummary, timeline: List<BatterySample>) {
        try {
            val dir = DocumentFile.fromTreeUri(context, folderUri) ?: return
            val ts = sdf.format(Date())

            // Per-session timeline: one row every 30 s
            val timelineFile = dir.createFile("text/csv", "ablation_timeline_${mode}_$ts.csv")
            if (timelineFile != null) {
                context.contentResolver.openOutputStream(timelineFile.uri)?.use {
                    OutputStreamWriter(it).use { w ->
                        w.write("elapsed_s,charge_mah,temperature_c,current_ua\n")
                        timeline.forEach { s ->
                            w.write("${s.elapsedS},${s.chargeMah},${s.temperatureC},${s.currentUa}\n")
                        }
                    }
                }
            }

            // Cross-session summary table
            val sessFile = dir.findFile("ablation_sessions.csv")
                ?: dir.createFile("text/csv", "ablation_sessions.csv")
            if (sessFile != null) {
                val hasHeader = context.contentResolver.openInputStream(sessFile.uri)
                    ?.use { it.available() > 0 } ?: false
                context.contentResolver.openOutputStream(sessFile.uri, "wa")?.use {
                    OutputStreamWriter(it).use { w ->
                        if (!hasHeader) {
                            w.write("timestamp,mode,duration_s,delta_charge_mah," +
                                "start_temp_c,end_temp_c,delta_temp_c," +
                                "frames_total,frames_passed_gate,frames_rejected,gate_pass_rate\n")
                        }
                        w.write("$ts,${summary.mode},${summary.durationS}," +
                            "${summary.deltaChargeMah},${summary.startTemperatureC}," +
                            "${summary.endTemperatureC},${summary.deltaTemperatureC}," +
                            "${summary.framesTotal},${summary.framesPassedGate}," +
                            "${summary.framesRejectedByGate},${summary.gatePassRate}\n")
                    }
                }
            }

            Log.d(TAG, "Ablation data written for session $ts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ablation data", e)
        }
    }
}
