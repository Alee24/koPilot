package ke.co.kopilot.feature.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThermalState {
    NORMAL,
    MODERATE,
    SEVERE,
}

data class CameraStatusState(
    val permissionGranted: Boolean = false,
    val cameraAvailable: Boolean = false,
    val isPreviewActive: Boolean = false,
    val thermalState: ThermalState = ThermalState.NORMAL,
    val targetAnalysisIntervalMs: Long = 80L,
    val actualResolution: String = "Pending",
    val cameraFps: Float = 0f,
    val inferenceFps: Float = 0f,
    val analyzedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val batteryPercent: Int = -1,
    val isBatteryLow: Boolean = false,
    val recentErrors: List<String> = emptyList(),
    val statusMessage: String = "Camera permission required",
)

@Singleton
class CameraPerformanceController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _status = MutableStateFlow(CameraStatusState())
    val status: StateFlow<CameraStatusState> = _status.asStateFlow()

    private var lastAnalyzedTimestampMs = 0L
    private var fpsWindowStartMs = 0L
    private var cameraFramesInWindow = 0
    private var analyzedFramesInWindow = 0

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener { thermalStatus ->
                val mapped = when {
                    thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
                    thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                    else -> ThermalState.NORMAL
                }
                val interval = when (mapped) {
                    ThermalState.NORMAL -> 80L
                    ThermalState.MODERATE -> 250L
                    ThermalState.SEVERE -> 700L
                }
                _status.value = _status.value.copy(
                    thermalState = mapped,
                    targetAnalysisIntervalMs = interval,
                )
            }
        }
        refreshBatteryStatus()
    }

    fun refreshBatteryStatus() {
        runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val low = pct in 0..15
            _status.value = _status.value.copy(
                batteryPercent = pct,
                isBatteryLow = low,
            )
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _status.value = _status.value.copy(
            permissionGranted = granted,
            statusMessage = if (granted) "Camera ready" else "Camera permission required",
        )
    }

    fun setCameraActive(active: Boolean, reason: String) {
        _status.value = _status.value.copy(
            cameraAvailable = active,
            isPreviewActive = active,
            statusMessage = reason,
        )
    }

    fun recordResolution(width: Int, height: Int) {
        val label = "${width}x${height}"
        if (_status.value.actualResolution != label) {
            _status.value = _status.value.copy(actualResolution = label)
        }
    }

    fun recordError(message: String) {
        val updated = (_status.value.recentErrors + message).takeLast(25)
        _status.value = _status.value.copy(recentErrors = updated)
    }

    fun shouldAnalyzeFrame(nowMillis: Long, qualityIntervalMs: Long): Boolean {
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = nowMillis
        cameraFramesInWindow++

        val batteryFloor = if (_status.value.isBatteryLow) 300L else 0L
        val effectiveInterval = maxOf(qualityIntervalMs, _status.value.targetAnalysisIntervalMs, batteryFloor)
        val analyze = (nowMillis - lastAnalyzedTimestampMs) >= effectiveInterval

        if (analyze) {
            lastAnalyzedTimestampMs = nowMillis
            analyzedFramesInWindow++
            _status.value = _status.value.copy(analyzedFrames = _status.value.analyzedFrames + 1)
        } else {
            _status.value = _status.value.copy(droppedFrames = _status.value.droppedFrames + 1)
        }

        val elapsed = nowMillis - fpsWindowStartMs
        if (elapsed >= 1_000L) {
            val sec = elapsed / 1000f
            _status.value = _status.value.copy(
                cameraFps = cameraFramesInWindow / sec,
                inferenceFps = analyzedFramesInWindow / sec,
            )
            cameraFramesInWindow = 0
            analyzedFramesInWindow = 0
            fpsWindowStartMs = nowMillis
            refreshBatteryStatus()
        }
        return analyze
    }
}

fun interface RoadFrameListener {
    fun onFrame(imageProxy: ImageProxy, rotationDegrees: Int, timestampMillis: Long)
}
