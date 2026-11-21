package ke.co.kopilot.feature.sensors

/**
 * Deterministic 8-state accident detection state machine.
 */
enum class AccidentState(val label: String) {
    NORMAL("Normal Monitoring"),
    SUSPICIOUS_EVENT("Suspicious Motion Detected"),
    VERIFYING("Verifying Independent Evidence (5s)"),
    DISMISSED_ROAD_EVENT("Dismissed Road Event (Bump/Pothole/Rough Road)"),
    DRIVING_EVENT_ONLY("Driving Event Only (No SOS)"),
    POSSIBLE_ACCIDENT("Possible Accident Detected"),
    SEVERE_EVENT_FALLBACK("Severe Motion Fallback Warning"),
    CANCELED("Canceled by Driver"),
    EMERGENCY_NOTIFICATION("Emergency Notification Active"),
}

/**
 * Genuinely independent evidence groups.
 * Phone accelerometer + gyroscope + rotation vector belong to ONE group (GROUP_A_PHONE_MOTION)
 * and are never counted as independent of one another.
 */
enum class EvidenceGroup(val code: String, val label: String) {
    GROUP_A_PHONE_MOTION("A", "Phone Motion (IMU)"),
    GROUP_B_VEHICLE_MOVEMENT("B", "Vehicle Movement (GPS / OBD Speed)"),
    GROUP_C_VEHICLE_DIAGNOSTICS("C", "Vehicle Diagnostics (OBD RPM / ECU)"),
    GROUP_D_DRIVER_WELLNESS("D", "Driver Wellness (Smartwatch HR)"),
}

data class EvidenceGroupStatus(
    val group: EvidenceGroup,
    val corroborated: Boolean,
    val available: Boolean,
    val stale: Boolean = false,
    val details: List<String> = emptyList(),
)

/**
 * Dedicated multi-feature swerving classification.
 * Swerving alone never triggers an automatic emergency SMS.
 */
enum class SwerveBehaviorCategory(val label: String, val shouldPreserveEvidence: Boolean) {
    NONE("Stable lane tracking", false),
    NORMAL_ROAD_BEND("Normal road bend", false),
    INTENTIONAL_TURN("Intentional turn", false),
    ROUNDABOUT("Roundabout navigation", false),
    LANE_CHANGE("Lane change", false),
    REPEATED_ERRATIC_SWERVING("Repeated erratic swerving", true),
    ABRUPT_EVASIVE_MOVEMENT("Abrupt evasive movement", true),
}

/**
 * Bounded, versioned engineering thresholds for accident and swerving detection.
 * These are provisional engineering thresholds, not validated crash-certification guarantees.
 */
data class AccidentThresholdConfig(
    val configVersion: String = DEFAULT_CONFIG_VERSION,
    val minorMovementG: Float = 0.5f,
    val strongImpactG: Float = 2.5f,
    val severeImpactG: Float = 4.0f,
    val harshBrakingMps2: Float = -6.0f,
    val abruptSpeedLossKmh: Float = 20.0f,
    val rolloverAngleDeg: Float = 60.0f,
    val minImpactDurationMs: Long = 80L,
    val swerveLateralMps2: Float = 5.5f,
    val swerveYawDegPerSec: Float = 42.0f,
    val rollingBufferPreSec: Int = 15,
    val rollingBufferPostSec: Int = 15,
) {
    val minorMovementMps2: Float get() = minorMovementG * GRAVITY_MPS2
    val strongImpactMps2: Float get() = strongImpactG * GRAVITY_MPS2
    val severeImpactMps2: Float get() = severeImpactG * GRAVITY_MPS2

    fun sanitized(): AccidentThresholdConfig = copy(
        minorMovementG = minorMovementG.coerceIn(0.2f, 1.2f),
        strongImpactG = strongImpactG.coerceIn(1.8f, 4.0f),
        severeImpactG = severeImpactG.coerceIn(maxOf(3.0f, strongImpactG + 0.8f), 6.5f),
        harshBrakingMps2 = harshBrakingMps2.coerceIn(-9.0f, -4.0f),
        abruptSpeedLossKmh = abruptSpeedLossKmh.coerceIn(12.0f, 40.0f),
        rolloverAngleDeg = rolloverAngleDeg.coerceIn(45.0f, 80.0f),
        minImpactDurationMs = minImpactDurationMs.coerceIn(40L, 400L),
        swerveLateralMps2 = swerveLateralMps2.coerceIn(3.5f, 8.5f),
        swerveYawDegPerSec = swerveYawDegPerSec.coerceIn(25.0f, 85.0f),
        rollingBufferPreSec = rollingBufferPreSec.coerceIn(5, 30),
        rollingBufferPostSec = rollingBufferPostSec.coerceIn(5, 30),
    )

    companion object {
        const val GRAVITY_MPS2 = 9.80665f
        const val DEFAULT_CONFIG_VERSION = "v2.0-provisional"

        fun fromSensitivity(profile: AccidentSensitivityProfile): AccidentThresholdConfig = when (profile) {
            AccidentSensitivityProfile.LOW -> AccidentThresholdConfig(
                configVersion = "v2.0-conservative",
                strongImpactG = 3.0f,
                severeImpactG = 4.5f,
                rolloverAngleDeg = 65.0f,
                abruptSpeedLossKmh = 24.0f,
                swerveLateralMps2 = 6.5f,
            )
            AccidentSensitivityProfile.STANDARD -> AccidentThresholdConfig(
                configVersion = DEFAULT_CONFIG_VERSION,
            )
            AccidentSensitivityProfile.HIGH -> AccidentThresholdConfig(
                configVersion = "v2.0-sensitive",
                strongImpactG = 2.2f,
                severeImpactG = 3.6f,
                rolloverAngleDeg = 55.0f,
                abruptSpeedLossKmh = 18.0f,
                swerveLateralMps2 = 4.8f,
            )
        }
    }
}

/**
 * Structured safety event log entry persisted to Trip Intelligence Report & Diagnostics history.
 */
data class SafetyEventRecord(
    val incidentId: String,
    val timestampMillis: Long,
    val category: String,
    val severity: String,
    val state: AccidentState,
    val peakLinearG: Float,
    val pitchRollMaxDeg: Float,
    val peakGyroDegPerSec: Float,
    val gpsSpeedKmh: Float?,
    val obdSpeedKmh: Float?,
    val obdRpm: Int?,
    val obdConnected: Boolean,
    val watchBpm: Int?,
    val watchFresh: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val activeEvidenceGroups: List<String>,
    val confirmationReasons: List<String>,
    val cameraEvidenceRef: String?,
    val confirmationOutcome: String,
    val canceledByDriver: Boolean = false,
    val smsAttempted: Boolean = false,
    val smsResult: String = "Not attempted",
    val isSimulation: Boolean = false,
    val configVersion: String = AccidentThresholdConfig.DEFAULT_CONFIG_VERSION,
    val roadClassification: String = "Smooth",
    val fiveSecondMotionObservation: String = "",
    val dismissalOrEscalationReason: String = "",
    val markedFalseAlarm: Boolean = false,
)

data class SensorQualitySnapshot(
    val samplingRateHz: Float = 0f,
    val maxSamplingGapMs: Long = 0L,
    val qualityLabel: String = "Initializing",
    val mountConfidenceLabel: String = "Uncalibrated",
    val jerkMps3: Float = 0f,
    val motionEventDurationMs: Long = 0L,
)
