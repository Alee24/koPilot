package ke.co.kopilot.feature.location

import ke.co.kopilot.core.common.model.GpsFix
import ke.co.kopilot.core.common.model.GpsSignalState
import ke.co.kopilot.core.common.model.SpeedAlertDecision
import ke.co.kopilot.core.common.model.TrafficSignObservation
import javax.inject.Inject
import javax.inject.Singleton

data class SpeedEvaluation(
    val decision: SpeedAlertDecision,
    val signalState: GpsSignalState,
    val currentSpeedKmh: Float?,
    val applicableSpeedLimitKmh: Int?,
    val reason: String,
)

@Singleton
class SpeedAlertEngine @Inject constructor() {

    private var warningActive = false
    private var lastWarningTimestampMs = 0L

    fun evaluate(
        fix: GpsFix?,
        latestSign: TrafficSignObservation?,
        thresholdKmh: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): SpeedEvaluation {
        if (fix == null || fix.isStale || (nowMillis - fix.timestampMillis) > STALE_FIX_MS) {
            warningActive = false
            return SpeedEvaluation(
                decision = SpeedAlertDecision.SPEED_DATA_UNAVAILABLE,
                signalState = GpsSignalState.UNAVAILABLE,
                currentSpeedKmh = null,
                applicableSpeedLimitKmh = null,
                reason = "GPS unavailable or stale",
            )
        }

        val speedKmh = fix.speedKmh
        val accuracyKmh = fix.speedAccuracyKmh ?: Float.MAX_VALUE
        val horizAcc = fix.horizontalAccuracyMeters ?: Float.MAX_VALUE

        if (speedKmh == null || horizAcc > MAX_HORIZONTAL_ACCURACY_M || accuracyKmh > MAX_SPEED_UNCERTAINTY_KMH) {
            return SpeedEvaluation(
                decision = SpeedAlertDecision.SPEED_DATA_UNAVAILABLE,
                signalState = GpsSignalState.UNCERTAIN,
                currentSpeedKmh = speedKmh,
                applicableSpeedLimitKmh = null,
                reason = "Speed measurement uncertain",
            )
        }

        val validLimit = latestSign
            ?.takeIf { it.category.speedLimitKmh != null }
            ?.takeIf { (nowMillis - it.timestampMillis) <= SIGN_VALIDITY_MS }
            ?.takeIf { it.confidence >= MIN_SIGN_CONFIDENCE && it.consecutiveFrames >= MIN_CONSECUTIVE_FRAMES }

        if (validLimit == null) {
            warningActive = false
            return SpeedEvaluation(
                decision = SpeedAlertDecision.NO_ALERT,
                signalState = GpsSignalState.GOOD,
                currentSpeedKmh = speedKmh,
                applicableSpeedLimitKmh = null,
                reason = "No verified speed limit observed",
            )
        }

        val limitKmh = validLimit.category.speedLimitKmh!!
        if (validLimit.applicableToDriverLane == false) {
            warningActive = false
            return SpeedEvaluation(
                decision = SpeedAlertDecision.SPEED_LIMIT_OBSERVATION,
                signalState = GpsSignalState.GOOD,
                currentSpeedKmh = speedKmh,
                applicableSpeedLimitKmh = limitKmh,
                reason = "Sign observed but lane applicability uncertain",
            )
        }

        // Subtract speed uncertainty conservatively so noisy GPS does not trigger false overspeed alerts
        val conservativeSpeed = speedKmh - accuracyKmh
        val triggerLimit = limitKmh + thresholdKmh
        val clearLimit = limitKmh + thresholdKmh - HYSTERESIS_KMH

        if (warningActive) {
            if (conservativeSpeed < clearLimit) {
                warningActive = false
            } else if ((nowMillis - lastWarningTimestampMs) >= WARNING_COOLDOWN_MS) {
                lastWarningTimestampMs = nowMillis
                return SpeedEvaluation(
                    decision = SpeedAlertDecision.POSSIBLE_OVERSPEED_WARNING,
                    signalState = GpsSignalState.GOOD,
                    currentSpeedKmh = speedKmh,
                    applicableSpeedLimitKmh = limitKmh,
                    reason = "Speed %.0f km/h exceeds observed %d km/h".format(speedKmh, limitKmh),
                )
            }
            return SpeedEvaluation(
                decision = SpeedAlertDecision.NO_ALERT,
                signalState = GpsSignalState.GOOD,
                currentSpeedKmh = speedKmh,
                applicableSpeedLimitKmh = limitKmh,
                reason = "Cooldown active",
            )
        }

        if (conservativeSpeed > triggerLimit) {
            warningActive = true
            lastWarningTimestampMs = nowMillis
            return SpeedEvaluation(
                decision = SpeedAlertDecision.POSSIBLE_OVERSPEED_WARNING,
                signalState = GpsSignalState.GOOD,
                currentSpeedKmh = speedKmh,
                applicableSpeedLimitKmh = limitKmh,
                reason = "Speed %.0f km/h exceeds observed %d km/h".format(speedKmh, limitKmh),
            )
        }

        return SpeedEvaluation(
            decision = SpeedAlertDecision.SPEED_LIMIT_OBSERVATION,
            signalState = GpsSignalState.GOOD,
            currentSpeedKmh = speedKmh,
            applicableSpeedLimitKmh = limitKmh,
            reason = "Within observed limit",
        )
    }

    fun reset() {
        warningActive = false
        lastWarningTimestampMs = 0L
    }

    companion object {
        const val STALE_FIX_MS = 6_000L
        const val SIGN_VALIDITY_MS = 180_000L
        const val MIN_SIGN_CONFIDENCE = 0.72f
        const val MIN_CONSECUTIVE_FRAMES = 2
        const val MAX_HORIZONTAL_ACCURACY_M = 35f
        const val MAX_SPEED_UNCERTAINTY_KMH = 12f
        const val HYSTERESIS_KMH = 4f
        const val WARNING_COOLDOWN_MS = 25_000L
    }
}
