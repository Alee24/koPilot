package ke.co.kopilot.feature.camera

import android.content.Context
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
    val targetAnalysisIntervalMs: Long = 200L,
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

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener { thermalStatus ->
                val mapped = when {
                    thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
                    thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                    else -> ThermalState.NORMAL
                }
                val interval = when (mapped) {
                    ThermalState.NORMAL -> 200L
                    ThermalState.MODERATE -> 450L
                    ThermalState.SEVERE -> 1000L
                }
                _status.value = _status.value.copy(
                    thermalState = mapped,
                    targetAnalysisIntervalMs = interval,
                )
            }
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

    fun shouldAnalyzeFrame(nowMillis: Long, qualityIntervalMs: Long): Boolean {
        val effectiveInterval = maxOf(qualityIntervalMs, _status.value.targetAnalysisIntervalMs)
        if ((nowMillis - lastAnalyzedTimestampMs) < effectiveInterval) {
            return false
        }
        lastAnalyzedTimestampMs = nowMillis
        return true
    }
}

fun interface RoadFrameListener {
    fun onFrame(imageProxy: ImageProxy, rotationDegrees: Int, timestampMillis: Long)
}
