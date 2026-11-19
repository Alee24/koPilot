package ke.co.kopilot.feature.sensors

enum class AccidentEventClass(val label: String, val triggersCountdown: Boolean) {
    NORMAL_DRIVING("Normal driving", false),
    MINOR_ROAD_IMPACT("Minor road impact", false),
    SIGNIFICANT_IMPACT("Significant impact", false),
    SUSPECTED_COLLISION("Suspected collision", true),
    SUSPECTED_ROLLOVER("Suspected rollover", true),
    HIGH_CONFIDENCE_POSSIBLE_ACCIDENT("High-confidence possible accident", true),
}

enum class SwerveClass(val label: String) {
    NORMAL_TURNING("Normal turning"),
    SHARP_TURNING("Sharp turning"),
    LANE_CHANGE("Sudden lane-change movement"),
    POSSIBLE_SWERVING("Possible swerving"),
    LOSS_OF_CONTROL("Possible loss-of-control pattern"),
}

enum class AccidentSensitivityProfile(
    val label: String,
    val impactThresholdMps2: Float,
    val severeImpactThresholdMps2: Float,
    val rolloverAngleDeg: Float,
    val swerveLateralMps2: Float,
) {
    LOW("Conservative", 22.0f, 30.0f, 52.0f, 7.5f),
    STANDARD("Standard", 17.5f, 24.5f, 44.0f, 6.0f),
    HIGH("Sensitive", 14.0f, 20.0f, 36.0f, 4.8f),
}

data class AccidentAssessment(
    val eventClass: AccidentEventClass,
    val swerveClass: SwerveClass = SwerveClass.NORMAL_TURNING,
    val confidencePercent: Int,
    val timestampMillis: Long,
    val preEventSpeedKmh: Float?,
    val postEventSpeedKmh: Float?,
    val impactIntensityMps2: Float,
    val orientationChangeDeg: Float,
    val gpsAccuracyMeters: Float?,
    val sensorReliability: String,
    val triggeringMeasurements: List<String>,
    val missingEvidence: List<String>,
    val suppressedAsPhoneHandling: Boolean = false,
)

/**
 * Evaluates multi-sensor accident evidence with explicit explainability and no single-threshold
 * blind triggers. Distinguishes phone drops/mount shifts from real vehicle collisions/rollovers.
 */
class AccidentConfidenceEvaluator {

    fun evaluate(
        timestampMillis: Long,
        peakLinearAccelMps2: Float,
        horizontalImpulseMps2: Float,
        preEventSpeedKmh: Float?,
        postEventSpeedKmh: Float?,
        rollChangeDeg: Float,
        pitchChangeDeg: Float,
        peakAngularVelDegPerSec: Float,
        postImpactStationarySec: Float,
        mountCalibrationStatus: MountCalibrationStatus,
        gpsAccuracyMeters: Float?,
        accelAvailable: Boolean,
        gyroAvailable: Boolean,
        obdConnected: Boolean,
        sensitivity: AccidentSensitivityProfile,
        impactEnabled: Boolean,
        rolloverEnabled: Boolean,
    ): AccidentAssessment {
        val orientationDelta = maxOf(kotlin.math.abs(rollChangeDeg), kotlin.math.abs(pitchChangeDeg))
        val speedDropKmh = if (preEventSpeedKmh != null && postEventSpeedKmh != null) {
            (preEventSpeedKmh - postEventSpeedKmh).coerceAtLeast(0f)
        } else {
            0f
        }

        val triggers = mutableListOf<String>()
        val missing = mutableListOf<String>()

        if (!gyroAvailable) missing.add("Gyroscope unavailable")
        if (preEventSpeedKmh == null) missing.add("Pre-event speed unavailable")
        if (gpsAccuracyMeters == null || gpsAccuracyMeters > 35f) missing.add("Low/missing GPS accuracy")
        if (!obdConnected) missing.add("OBD-II speed/RPM unavailable")

        val sensorReliability = when {
            accelAvailable && gyroAvailable && preEventSpeedKmh != null -> "High"
            accelAvailable && (gyroAvailable || preEventSpeedKmh != null) -> "Moderate"
            else -> "Degraded"
        }

        // Phone-handling / mount-slip guard: large orientation shift with low speed & no severe horizontal impulse
        val likelyPhoneHandling = mountCalibrationStatus == MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED ||
            ((orientationDelta >= 35f) &&
                (preEventSpeedKmh != null && preEventSpeedKmh < 8f) &&
                horizontalImpulseMps2 < sensitivity.impactThresholdMps2 * 0.75f)

        var score = 0
        if (impactEnabled && peakLinearAccelMps2 >= sensitivity.impactThresholdMps2) {
            score += 30
            triggers.add("Peak impact ${"%.1f".format(peakLinearAccelMps2)} m/s²")
        }
        if (impactEnabled && horizontalImpulseMps2 >= sensitivity.severeImpactThresholdMps2) {
            score += 25
            triggers.add("Severe horizontal impulse ${"%.1f".format(horizontalImpulseMps2)} m/s²")
        }
        if (speedDropKmh >= 18f && (preEventSpeedKmh ?: 0f) >= 22f) {
            score += 25
            triggers.add("Rapid speed drop ${"%.0f".format(speedDropKmh)} km/h")
        }
        if (rolloverEnabled && orientationDelta >= sensitivity.rolloverAngleDeg && peakAngularVelDegPerSec >= 90f) {
            score += 35
            triggers.add("Roll/pitch shift ${"%.0f".format(orientationDelta)}° at ${"%.0f".format(peakAngularVelDegPerSec)}°/s")
        }
        if (postImpactStationarySec >= 3.0f && (preEventSpeedKmh ?: 0f) >= 15f) {
            score += 15
            triggers.add("Vehicle stationary ${"%.1f".format(postImpactStationarySec)}s post-event")
        }

        if (likelyPhoneHandling) {
            score = (score - 45).coerceAtLeast(0)
        }

        val eventClass = when {
            likelyPhoneHandling -> AccidentEventClass.MINOR_ROAD_IMPACT
            rolloverEnabled &&
                orientationDelta >= sensitivity.rolloverAngleDeg &&
                peakAngularVelDegPerSec >= 95f &&
                (peakLinearAccelMps2 >= sensitivity.impactThresholdMps2 * 0.8f || (preEventSpeedKmh ?: 0f) >= 15f) ->
                if (score >= 80) AccidentEventClass.HIGH_CONFIDENCE_POSSIBLE_ACCIDENT else AccidentEventClass.SUSPECTED_ROLLOVER
            score >= 75 -> AccidentEventClass.HIGH_CONFIDENCE_POSSIBLE_ACCIDENT
            score >= 55 && triggers.size >= 2 -> AccidentEventClass.SUSPECTED_COLLISION
            impactEnabled && peakLinearAccelMps2 >= sensitivity.impactThresholdMps2 -> AccidentEventClass.SIGNIFICANT_IMPACT
            impactEnabled && peakLinearAccelMps2 >= 9.5f -> AccidentEventClass.MINOR_ROAD_IMPACT
            else -> AccidentEventClass.NORMAL_DRIVING
        }

        return AccidentAssessment(
            eventClass = eventClass,
            confidencePercent = score.coerceIn(0, 99),
            timestampMillis = timestampMillis,
            preEventSpeedKmh = preEventSpeedKmh,
            postEventSpeedKmh = postEventSpeedKmh,
            impactIntensityMps2 = peakLinearAccelMps2,
            orientationChangeDeg = orientationDelta,
            gpsAccuracyMeters = gpsAccuracyMeters,
            sensorReliability = sensorReliability,
            triggeringMeasurements = triggers.ifEmpty { listOf("Within normal driving envelope") },
            missingEvidence = missing,
            suppressedAsPhoneHandling = likelyPhoneHandling,
        )
    }
}
