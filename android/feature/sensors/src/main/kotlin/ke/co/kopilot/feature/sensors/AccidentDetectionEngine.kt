package ke.co.kopilot.feature.sensors

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

data class SensorFrame(
    val timestampMillis: Long,
    val forwardAccelMps2: Float,
    val lateralAccelMps2: Float,
    val verticalAccelMps2: Float,
    val linearMagMps2: Float,
    val angularVelDegPerSec: Float,
    val pitchDeltaDeg: Float,
    val rollDeltaDeg: Float,
    val speedKmh: Float?,
    val gpsAccuracyMeters: Float?,
    val obdRpm: Int?,
)

data class AccidentEngineOutput(
    val assessment: AccidentAssessment,
    val swerveClass: SwerveClass,
    val postImpactMonitoringActive: Boolean,
    val postImpactElapsedSec: Long,
    val bufferSize: Int,
)

/**
 * Multi-sensor accident detection engine with a 30-second rolling synchronized history buffer.
 * Evaluates collisions, rollovers, and swerving/loss-of-control without relying on a single axis
 * or single threshold, and tracks post-impact stationary periods.
 */
class AccidentDetectionEngine(
    private val confidenceEvaluator: AccidentConfidenceEvaluator = AccidentConfidenceEvaluator(),
) {
    private val buffer = ArrayDeque<SensorFrame>()
    private var lastTriggerMillis = 0L
    private var significantImpactMillis = 0L
    private var preImpactSpeedKmh: Float? = null

    fun reset() {
        buffer.clear()
        lastTriggerMillis = 0L
        significantImpactMillis = 0L
        preImpactSpeedKmh = null
    }

    fun processFrame(
        frame: SensorFrame,
        mountStatus: MountCalibrationStatus,
        accelAvailable: Boolean,
        gyroAvailable: Boolean,
        obdConnected: Boolean,
        sensitivity: AccidentSensitivityProfile,
        impactEnabled: Boolean,
        rolloverEnabled: Boolean,
        swerveEnabled: Boolean,
    ): AccidentEngineOutput {
        buffer.addLast(frame)
        val cutoff = frame.timestampMillis - BUFFER_WINDOW_MS
        while (buffer.isNotEmpty() && buffer.first.timestampMillis < cutoff) {
            buffer.removeFirst()
        }

        val recent5s = buffer.filter { it.timestampMillis >= frame.timestampMillis - 5_000L }
        val preWindow = buffer.filter {
            it.timestampMillis in (frame.timestampMillis - 6_000L)..(frame.timestampMillis - 1_500L)
        }

        val peakMag = recent5s.maxOfOrNull { it.linearMagMps2 } ?: frame.linearMagMps2
        val peakHoriz = recent5s.maxOfOrNull {
            sqrt(it.forwardAccelMps2 * it.forwardAccelMps2 + it.lateralAccelMps2 * it.lateralAccelMps2)
        } ?: 0f
        val peakGyro = recent5s.maxOfOrNull { it.angularVelDegPerSec } ?: frame.angularVelDegPerSec
        val maxRollDelta = recent5s.maxOfOrNull { abs(it.rollDeltaDeg) } ?: abs(frame.rollDeltaDeg)
        val maxPitchDelta = recent5s.maxOfOrNull { abs(it.pitchDeltaDeg) } ?: abs(frame.pitchDeltaDeg)

        val preSpeed = preWindow.mapNotNull { it.speedKmh }.maxOrNull() ?: preImpactSpeedKmh
        val postSpeed = frame.speedKmh

        if (peakMag >= sensitivity.impactThresholdMps2 && significantImpactMillis == 0L) {
            significantImpactMillis = frame.timestampMillis
            preImpactSpeedKmh = preSpeed
        }

        val postImpactStationarySec = if (significantImpactMillis > 0L) {
            val stationaryFrames = buffer.count {
                it.timestampMillis >= significantImpactMillis && (it.speedKmh ?: 0f) < 3.0f
            }
            if ((frame.speedKmh ?: 0f) < 3.0f && stationaryFrames >= 4) {
                (frame.timestampMillis - significantImpactMillis) / 1000f
            } else {
                0f
            }
        } else {
            0f
        }

        val swerveClass = if (swerveEnabled) {
            classifySwerve(recent5s, sensitivity)
        } else {
            SwerveClass.NORMAL_TURNING
        }

        var assessment = confidenceEvaluator.evaluate(
            timestampMillis = frame.timestampMillis,
            peakLinearAccelMps2 = peakMag,
            horizontalImpulseMps2 = peakHoriz,
            preEventSpeedKmh = preSpeed,
            postEventSpeedKmh = postSpeed,
            rollChangeDeg = maxRollDelta,
            pitchChangeDeg = maxPitchDelta,
            peakAngularVelDegPerSec = peakGyro,
            postImpactStationarySec = postImpactStationarySec,
            mountCalibrationStatus = mountStatus,
            gpsAccuracyMeters = frame.gpsAccuracyMeters,
            accelAvailable = accelAvailable,
            gyroAvailable = gyroAvailable,
            obdConnected = obdConnected,
            sensitivity = sensitivity,
            impactEnabled = impactEnabled,
            rolloverEnabled = rolloverEnabled,
        ).copy(swerveClass = swerveClass)

        // Short cooldown only suppresses repeated triggers for the same incident (not distinct severe ones later)
        if (assessment.eventClass.triggersCountdown) {
            if (lastTriggerMillis > 0L && (frame.timestampMillis - lastTriggerMillis) < COOLDOWN_MS) {
                assessment = assessment.copy(
                    eventClass = AccidentEventClass.SIGNIFICANT_IMPACT,
                )
            } else {
                lastTriggerMillis = frame.timestampMillis
            }
        }

        val postActive = significantImpactMillis > 0L &&
            (frame.timestampMillis - significantImpactMillis) <= POST_IMPACT_WINDOW_MS
        if (!postActive && (frame.speedKmh ?: 0f) > 12f) {
            significantImpactMillis = 0L
            preImpactSpeedKmh = null
        }

        return AccidentEngineOutput(
            assessment = assessment,
            swerveClass = swerveClass,
            postImpactMonitoringActive = postActive,
            postImpactElapsedSec = if (postActive) (frame.timestampMillis - significantImpactMillis) / 1000L else 0L,
            bufferSize = buffer.size,
        )
    }

    private fun classifySwerve(
        recent: List<SensorFrame>,
        sensitivity: AccidentSensitivityProfile,
    ): SwerveClass {
        if (recent.isEmpty()) return SwerveClass.NORMAL_TURNING
        val peakLat = recent.maxOf { abs(it.lateralAccelMps2) }
        val peakGyro = recent.maxOf { it.angularVelDegPerSec }
        val hasPositive = recent.any { it.lateralAccelMps2 >= sensitivity.swerveLateralMps2 * 0.7f }
        val hasNegative = recent.any { it.lateralAccelMps2 <= -sensitivity.swerveLateralMps2 * 0.7f }
        val alternating = hasPositive && hasNegative
        val moving = recent.any { (it.speedKmh ?: 0f) >= 15f }

        return when {
            !moving -> SwerveClass.NORMAL_TURNING
            alternating && peakLat >= sensitivity.swerveLateralMps2 * 1.25f && peakGyro >= 65f ->
                SwerveClass.LOSS_OF_CONTROL
            alternating && peakLat >= sensitivity.swerveLateralMps2 ->
                SwerveClass.POSSIBLE_SWERVING
            peakLat >= sensitivity.swerveLateralMps2 * 0.9f && peakGyro >= 35f ->
                SwerveClass.LANE_CHANGE
            peakLat >= sensitivity.swerveLateralMps2 * 0.65f ->
                SwerveClass.SHARP_TURNING
            else -> SwerveClass.NORMAL_TURNING
        }
    }

    companion object {
        const val BUFFER_WINDOW_MS = 30_000L
        const val COOLDOWN_MS = 25_000L
        const val POST_IMPACT_WINDOW_MS = 180_000L
    }
}
