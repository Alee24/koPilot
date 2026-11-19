package ke.co.kopilot.feature.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class ThermalState(
    val label: String,
    val baseIntervalMs: Long,
    val policySummary: String,
) {
    NORMAL(
        label = "Normal",
        baseIntervalMs = 90L,
        policySummary = "Full performance: multi-pass road-sign YOLO11n, composite ROI OCR, traffic-light housing CV, and scheduled plate OCR active.",
    ),
    WARM(
        label = "Warm",
        baseIntervalMs = 150L,
        policySummary = "Warm optimization: road-sign and traffic-light detection prioritized; plate OCR and full-frame secondary scans throttled by 50%.",
    ),
    HOT(
        label = "Hot",
        baseIntervalMs = 240L,
        policySummary = "Hot protection: single-pass letterboxed YOLO11n + Sign ROI OCR active; secondary object and plate OCR reduced to low-frequency background cadence.",
    ),
    CRITICAL(
        label = "Critical",
        baseIntervalMs = 650L,
        policySummary = "Critical thermal protection: heavy secondary vision suspended to respect Android thermal limits; essential safety checks and GPS/OBD trip logging retained.",
    );

    companion object {
        val MODERATE: ThermalState get() = WARM
        val SEVERE: ThermalState get() = HOT
    }
}

data class PerformanceHistoryPoint(
    val timestampMillis: Long,
    val cameraFps: Float,
    val inferenceFps: Float,
    val latencyMs: Long,
    val batteryTempC: Float?,
    val thermalState: ThermalState,
)

data class CameraStatusState(
    val permissionGranted: Boolean = false,
    val cameraAvailable: Boolean = false,
    val isPreviewActive: Boolean = false,
    val thermalState: ThermalState = ThermalState.NORMAL,
    val osThermalStatusLabel: String = "Normal (NONE)",
    val thermalHeadroom: Float? = null,
    val batteryTemperatureC: Float? = null,
    val batteryCurrentMilliamps: Int? = null,
    val jvmHeapUsedMb: Int = 0,
    val jvmHeapMaxMb: Int = 0,
    val nativeHeapAllocatedMb: Int = 0,
    val targetAnalysisIntervalMs: Long = 90L,
    val actualResolution: String = "Pending",
    val cameraFps: Float = 0f,
    val inferenceFps: Float = 0f,
    val analyzedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val droppedFramePercent: Float = 0f,
    val processingQueueDepth: Int = 0,
    val batteryPercent: Int = -1,
    val isBatteryLow: Boolean = false,
    val history: List<PerformanceHistoryPoint> = emptyList(),
    val recentErrors: List<String> = emptyList(),
    val statusMessage: String = "Camera permission required",
)

@Singleton
class CameraPerformanceController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val _status = MutableStateFlow(CameraStatusState())
    val status: StateFlow<CameraStatusState> = _status.asStateFlow()

    private var lastAnalyzedTimestampMs = 0L
    private var fpsWindowStartMs = 0L
    private var cameraFramesInWindow = 0
    private var analyzedFramesInWindow = 0
    private var latestOsThermalStatus = 0
    private var lastHeadroomQueryMs = 0L
    private var cachedHeadroom: Float? = null
    private var candidateCoolerState: ThermalState? = null
    private var candidateCoolerSinceMs: Long = 0L
    private var latestFrameLatencyMs: Long = 0L
    private val historyBuffer = ArrayDeque<PerformanceHistoryPoint>(MAX_HISTORY_POINTS)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                latestOsThermalStatus = powerManager.currentThermalStatus
                powerManager.addThermalStatusListener { thermalStatus ->
                    latestOsThermalStatus = thermalStatus
                    updateThermalAndBatteryTelemetry(System.currentTimeMillis())
                }
            }
        }
        updateThermalAndBatteryTelemetry(System.currentTimeMillis())
    }

    fun recordFrameLatency(latencyMs: Long, queueDepth: Int) {
        latestFrameLatencyMs = latencyMs
        if (_status.value.processingQueueDepth != queueDepth) {
            _status.value = _status.value.copy(processingQueueDepth = queueDepth)
        }
    }

    fun updateThermalAndBatteryTelemetry(nowMillis: Long = System.currentTimeMillis()) {
        runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val low = pct in 0..15

            val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val tempC = if (tempTenths in 10..800) tempTenths / 10f else null

            val rawCurrentUa = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val currentMa = if (rawCurrentUa != null && rawCurrentUa != Long.MIN_VALUE && rawCurrentUa != 0L) {
                (rawCurrentUa / 1000L).toInt()
            } else {
                null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (nowMillis - lastHeadroomQueryMs) >= 10_000L) {
                lastHeadroomQueryMs = nowMillis
                val hr = runCatching { powerManager.getThermalHeadroom(10) }.getOrNull()
                cachedHeadroom = if (hr != null && !hr.isNaN() && hr > 0f) hr else null
            }

            val osLabel = formatOsThermalStatus(latestOsThermalStatus)
            val currentState = _status.value.thermalState
            val nextState = evaluateNextThermalState(
                currentState = currentState,
                osThermalStatus = latestOsThermalStatus,
                thermalHeadroom = cachedHeadroom,
                batteryTempC = tempC,
                nowMillis = nowMillis,
            )

            val rt = Runtime.getRuntime()
            val usedHeapMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt()
            val maxHeapMb = (rt.maxMemory() / (1024L * 1024L)).toInt()
            val nativeMb = (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt()

            val totalFrames = _status.value.analyzedFrames + _status.value.droppedFrames
            val dropPct = if (totalFrames > 0) {
                (_status.value.droppedFrames.toFloat() * 100f) / totalFrames.toFloat()
            } else {
                0f
            }

            _status.value = _status.value.copy(
                thermalState = nextState,
                osThermalStatusLabel = osLabel,
                thermalHeadroom = cachedHeadroom,
                batteryTemperatureC = tempC,
                batteryCurrentMilliamps = currentMa,
                jvmHeapUsedMb = usedHeapMb,
                jvmHeapMaxMb = maxHeapMb,
                nativeHeapAllocatedMb = nativeMb,
                targetAnalysisIntervalMs = nextState.baseIntervalMs,
                batteryPercent = pct,
                isBatteryLow = low,
                droppedFramePercent = dropPct,
            )
        }
    }

    @Synchronized
    internal fun evaluateNextThermalState(
        currentState: ThermalState,
        osThermalStatus: Int,
        thermalHeadroom: Float?,
        batteryTempC: Float?,
        nowMillis: Long,
    ): ThermalState {
        val rawTarget = computeRawThermalTarget(osThermalStatus, thermalHeadroom, batteryTempC)

        // Immediate upward escalation to protect device and respect Android thermal signals
        if (rawTarget.ordinal >= currentState.ordinal) {
            candidateCoolerState = null
            candidateCoolerSinceMs = 0L
            return rawTarget
        }

        // Downward recovery requires hysteresis deadband (1.5C cooler than entry threshold) AND 15s sustained cooldown
        val tempAllowsCooldown = when (currentState) {
            ThermalState.CRITICAL -> batteryTempC == null || batteryTempC <= 44.0f
            ThermalState.HOT -> batteryTempC == null || batteryTempC <= 40.5f
            ThermalState.WARM -> batteryTempC == null || batteryTempC <= 37.0f
            ThermalState.NORMAL -> true
        }
        if (!tempAllowsCooldown) {
            candidateCoolerState = null
            candidateCoolerSinceMs = 0L
            return currentState
        }

        if (candidateCoolerState != rawTarget) {
            candidateCoolerState = rawTarget
            candidateCoolerSinceMs = nowMillis
            return currentState
        }

        return if ((nowMillis - candidateCoolerSinceMs) >= THERMAL_RECOVERY_HYSTERESIS_MS) {
            candidateCoolerState = null
            candidateCoolerSinceMs = 0L
            // Step down one tier at a time for smooth recovery
            ThermalState.entries[(currentState.ordinal - 1).coerceAtLeast(rawTarget.ordinal)]
        } else {
            currentState
        }
    }

    private fun computeRawThermalTarget(
        osThermalStatus: Int,
        thermalHeadroom: Float?,
        batteryTempC: Float?,
    ): ThermalState {
        val byOs = when {
            osThermalStatus >= 4 -> ThermalState.CRITICAL // THERMAL_STATUS_CRITICAL / EMERGENCY / SHUTDOWN
            osThermalStatus == 3 -> ThermalState.HOT      // THERMAL_STATUS_SEVERE
            osThermalStatus in 1..2 -> ThermalState.WARM  // THERMAL_STATUS_LIGHT / MODERATE
            else -> ThermalState.NORMAL
        }
        val byHeadroom = when {
            thermalHeadroom == null -> ThermalState.NORMAL
            thermalHeadroom >= 1.00f -> ThermalState.CRITICAL
            thermalHeadroom >= 0.85f -> ThermalState.HOT
            thermalHeadroom >= 0.72f -> ThermalState.WARM
            else -> ThermalState.NORMAL
        }
        val byBatteryTemp = when {
            batteryTempC == null -> ThermalState.NORMAL
            batteryTempC >= 45.5f -> ThermalState.CRITICAL
            batteryTempC >= 42.0f -> ThermalState.HOT
            batteryTempC >= 38.5f -> ThermalState.WARM
            else -> ThermalState.NORMAL
        }
        return maxOf(byOs, byHeadroom, byBatteryTemp)
    }

    private fun formatOsThermalStatus(status: Int): String = when (status) {
        0 -> "NONE (Normal)"
        1 -> "LIGHT (Warm)"
        2 -> "MODERATE (Warm)"
        3 -> "SEVERE (Hot)"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "Status $status"
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

        val newAnalyzed = if (analyze) _status.value.analyzedFrames + 1 else _status.value.analyzedFrames
        val newDropped = if (analyze) _status.value.droppedFrames else _status.value.droppedFrames + 1
        val total = (newAnalyzed + newDropped).coerceAtLeast(1L)
        val dropPct = ((newDropped.toFloat() * 100f) / total.toFloat())

        if (analyze) {
            lastAnalyzedTimestampMs = nowMillis
            analyzedFramesInWindow++
        }
        _status.value = _status.value.copy(
            analyzedFrames = newAnalyzed,
            droppedFrames = newDropped,
            droppedFramePercent = dropPct,
        )

        val elapsed = nowMillis - fpsWindowStartMs
        if (elapsed >= 1_000L) {
            val sec = elapsed / 1000f
            val camFps = cameraFramesInWindow / sec
            val infFps = analyzedFramesInWindow / sec
            cameraFramesInWindow = 0
            analyzedFramesInWindow = 0
            fpsWindowStartMs = nowMillis
            updateThermalAndBatteryTelemetry(nowMillis)

            synchronized(historyBuffer) {
                if (historyBuffer.size >= MAX_HISTORY_POINTS) historyBuffer.removeFirst()
                historyBuffer.addLast(
                    PerformanceHistoryPoint(
                        timestampMillis = nowMillis,
                        cameraFps = (camFps * 10f).roundToInt() / 10f,
                        inferenceFps = (infFps * 10f).roundToInt() / 10f,
                        latencyMs = latestFrameLatencyMs,
                        batteryTempC = _status.value.batteryTemperatureC,
                        thermalState = _status.value.thermalState,
                    ),
                )
                _status.value = _status.value.copy(
                    cameraFps = camFps,
                    inferenceFps = infFps,
                    history = historyBuffer.toList(),
                )
            }
        }
        return analyze
    }

    companion object {
        const val THERMAL_RECOVERY_HYSTERESIS_MS = 15_000L
        private const val MAX_HISTORY_POINTS = 120
    }
}

fun interface RoadFrameListener {
    fun onFrame(imageProxy: ImageProxy, rotationDegrees: Int, timestampMillis: Long)
}
