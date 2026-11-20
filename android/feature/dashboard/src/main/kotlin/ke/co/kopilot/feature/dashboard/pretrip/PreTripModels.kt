package ke.co.kopilot.feature.dashboard.pretrip

enum class PreTripWizardStage(val stepNumber: Int, val title: String) {
    WELCOME_INIT(1, "Welcome & Initialization"),
    CAMERA_AND_SENSORS(2, "Camera & Phone Sensors"),
    OBD_CONNECTION(3, "OBD-II Vehicle Connection"),
    SMARTWATCH_CONNECTION(4, "Smartwatch & Sleep"),
    SENSOR_CALIBRATION(5, "Sensor Calibration"),
    READINESS_REPORT(6, "Pre-Trip Readiness Report"),
}

enum class PreTripCheckStatus(val label: String) {
    CHECKING("Checking"),
    READY("Ready"),
    NEEDS_PERMISSION("Needs permission"),
    CALIBRATION_REQUIRED("Calibration required"),
    UNAVAILABLE("Unavailable"),
    FAILED("Failed"),
}

enum class OverallReadinessLevel(val label: String) {
    READY("Ready"),
    READY_WITH_LIMITATIONS("Ready with limitations"),
    ATTENTION_REQUIRED("Attention required"),
}

data class SensorCheckItem(
    val id: String,
    val name: String,
    val status: PreTripCheckStatus,
    val readingText: String,
    val detailText: String,
    val isCoreDrivingRequirement: Boolean = false,
)

data class VehicleHealthParameter(
    val name: String,
    val valueText: String,
    val isAvailable: Boolean,
    val isAbnormal: Boolean = false,
    val note: String? = null,
)

data class DriverWellnessAssessment(
    val sleepDurationText: String,
    val sleepWindowText: String,
    val sleepStagesSummary: String,
    val sleepConsistencyText: String,
    val currentHeartRateText: String,
    val restingOrAvgHeartRateText: String,
    val watchContactText: String,
    val dataFreshnessText: String,
    val dataSufficient: Boolean,
    val cautionMessages: List<String>,
    val summaryStatement: String,
)

data class PreTripReadinessSummary(
    val overallLevel: OverallReadinessLevel,
    val headline: String,
    val reasons: List<String>,
    val readySystemsCount: Int,
    val totalSystemsCount: Int,
)

/**
 * Pure deterministic evaluator for Pre-Trip Readiness and Vehicle Health rules
 * so all readiness logic is unit-testable on the JVM with zero fabricated scores.
 */
object PreTripReadinessEvaluator {

    fun evaluateOverallReadiness(
        cameraStatus: PreTripCheckStatus,
        gpsStatus: PreTripCheckStatus,
        motionStatus: PreTripCheckStatus,
        obdConnected: Boolean,
        obdAbnormalCount: Int,
        watchConnected: Boolean,
        mountCalibrated: Boolean,
        hasDriverWellnessCaution: Boolean,
    ): PreTripReadinessSummary {
        val reasons = mutableListOf<String>()
        var attentionRequired = false
        var hasLimitations = false

        if (cameraStatus == PreTripCheckStatus.NEEDS_PERMISSION || cameraStatus == PreTripCheckStatus.FAILED) {
            attentionRequired = true
            reasons.add("Camera is ${cameraStatus.label.lowercase()}: road-sign and traffic-light recognition disabled.")
        } else if (cameraStatus != PreTripCheckStatus.READY) {
            hasLimitations = true
            reasons.add("Camera status: ${cameraStatus.label}.")
        }

        if (gpsStatus == PreTripCheckStatus.NEEDS_PERMISSION || gpsStatus == PreTripCheckStatus.FAILED) {
            attentionRequired = true
            reasons.add("GPS is ${gpsStatus.label.lowercase()}: live speed and trip distance require location permission.")
        } else if (gpsStatus != PreTripCheckStatus.READY) {
            hasLimitations = true
            reasons.add("GPS is still acquiring a satellite lock (optional to start, speed may show -- initially).")
        }

        if (motionStatus == PreTripCheckStatus.FAILED || motionStatus == PreTripCheckStatus.UNAVAILABLE) {
            hasLimitations = true
            reasons.add("Motion sensors unavailable: road quality and harsh-event scoring limited.")
        } else if (!mountCalibrated) {
            hasLimitations = true
            reasons.add("Phone mounting calibration not yet locked: run calibration once mounted securely.")
        }

        if (!obdConnected) {
            hasLimitations = true
            reasons.add("OBD-II adapter not connected: live ECU RPM, coolant, voltage, and DTC checks unavailable.")
        } else if (obdAbnormalCount > 0) {
            attentionRequired = true
            reasons.add("Vehicle OBD-II reported $obdAbnormalCount abnormal parameter(s) or diagnostic trouble code(s).")
        }

        if (!watchConnected) {
            hasLimitations = true
            reasons.add("Smartwatch not connected: live heart-rate monitoring unavailable for this trip.")
        }

        if (hasDriverWellnessCaution) {
            attentionRequired = true
            reasons.add("Driver wellness caution noted (e.g., short recorded sleep duration).")
        }

        val level = when {
            attentionRequired -> OverallReadinessLevel.ATTENTION_REQUIRED
            hasLimitations -> OverallReadinessLevel.READY_WITH_LIMITATIONS
            else -> OverallReadinessLevel.READY
        }

        val headline = when (level) {
            OverallReadinessLevel.READY -> "All core systems, vehicle ECU, and smartwatch sensors are verified."
            OverallReadinessLevel.READY_WITH_LIMITATIONS -> "Core driving systems ready; optional devices or calibration have limitations."
            OverallReadinessLevel.ATTENTION_REQUIRED -> "Review highlighted system or vehicle warnings before starting your trip."
        }

        val totalCore = 5 // Camera, GPS, Motion, OBD, Watch
        val readyCore = listOf(
            cameraStatus == PreTripCheckStatus.READY,
            gpsStatus == PreTripCheckStatus.READY,
            motionStatus == PreTripCheckStatus.READY,
            obdConnected,
            watchConnected,
        ).count { it }

        return PreTripReadinessSummary(
            overallLevel = level,
            headline = headline,
            reasons = reasons,
            readySystemsCount = readyCore,
            totalSystemsCount = totalCore,
        )
    }

    fun evaluateDriverWellness(
        latestSleepDurationMinutes: Int?,
        sleepWindowText: String?,
        sleepStagesSummary: String?,
        sleepConsistencyText: String?,
        liveHeartRateBpm: Int?,
        avgHeartRateBpm: Int?,
        sensorContactSupported: Boolean,
        sensorContactDetected: Boolean?,
        isHeartRateFresh: Boolean,
    ): DriverWellnessAssessment {
        val cautions = mutableListOf<String>()
        val hasSleep = latestSleepDurationMinutes != null && latestSleepDurationMinutes > 0
        val hasFreshHr = isHeartRateFresh && liveHeartRateBpm != null

        if (hasSleep && latestSleepDurationMinutes!! < 360) {
            val h = latestSleepDurationMinutes / 60
            val m = latestSleepDurationMinutes % 60
            cautions.add("Last recorded sleep was ${h}h ${m}m (under 6 hours). Consider resting or scheduling breaks before driving.")
        }
        if (hasFreshHr && (liveHeartRateBpm!! > 115 || liveHeartRateBpm < 45)) {
            cautions.add("Current measured heart rate is $liveHeartRateBpm BPM, which is outside typical resting range. Ensure you feel well before driving.")
        }

        val dataSufficient = hasSleep || hasFreshHr
        val summaryStatement = if (!dataSufficient) {
            "Readiness cannot be determined from available data."
        } else if (cautions.isNotEmpty()) {
            cautions.first()
        } else {
            "Available wellness records show no obvious caution indicators. Always assess how alert you feel before driving."
        }

        val sleepDurText = if (hasSleep) {
            val h = latestSleepDurationMinutes!! / 60
            val m = latestSleepDurationMinutes % 60
            if (h > 0) "${h}h ${m}m" else "${m}m"
        } else {
            "No synchronized sleep record"
        }

        val contactText = when {
            !hasFreshHr && !sensorContactSupported -> "Not connected / no live contact data"
            !sensorContactSupported -> "Contact bit not reported by watch"
            sensorContactDetected == true -> "Wrist contact confirmed"
            sensorContactDetected == false -> "No wrist contact detected"
            else -> "Pending reading"
        }

        return DriverWellnessAssessment(
            sleepDurationText = sleepDurText,
            sleepWindowText = sleepWindowText ?: "Unavailable",
            sleepStagesSummary = sleepStagesSummary ?: "Unavailable",
            sleepConsistencyText = sleepConsistencyText ?: "Unavailable",
            currentHeartRateText = if (hasFreshHr) "$liveHeartRateBpm BPM" else "-- (No current reading)",
            restingOrAvgHeartRateText = avgHeartRateBpm?.let { "$it BPM (session/recent avg)" } ?: "Unavailable",
            watchContactText = contactText,
            dataFreshnessText = if (hasFreshHr) "Live BLE reading (<15s old)" else "No live BLE sample",
            dataSufficient = dataSufficient,
            cautionMessages = cautions,
            summaryStatement = summaryStatement,
        )
    }

    fun evaluateVehicleHealthParameters(
        obdConnected: Boolean,
        rpm: Int?,
        coolantTempC: Int?,
        speedKmh: Int?,
        engineLoadPct: Float?,
        throttlePct: Float?,
        voltageV: Float?,
        fuelLevelPct: Float?,
        dtcCodes: List<String>,
        milOn: Boolean?,
    ): List<VehicleHealthParameter> {
        if (!obdConnected) {
            return listOf(
                VehicleHealthParameter(
                    name = "OBD-II ECU Status",
                    valueText = "Not connected",
                    isAvailable = false,
                    note = "Connect an ELM327-compatible adapter with ignition ON to read live vehicle parameters.",
                ),
            )
        }
        return listOf(
            VehicleHealthParameter(
                name = "Engine RPM (010C)",
                valueText = rpm?.let { "$it rpm" } ?: "Unavailable",
                isAvailable = rpm != null,
                isAbnormal = (rpm != null && rpm > 4500),
                note = if (rpm != null && rpm > 4500) "High stationary RPM detected." else null,
            ),
            VehicleHealthParameter(
                name = "Coolant Temperature (0105)",
                valueText = coolantTempC?.let { "$it C" } ?: "Unavailable",
                isAvailable = coolantTempC != null,
                isAbnormal = (coolantTempC != null && coolantTempC >= 104),
                note = if (coolantTempC != null && coolantTempC >= 104) "Coolant temperature is above normal operating range (>= 104 C)." else null,
            ),
            VehicleHealthParameter(
                name = "Vehicle Speed (010D)",
                valueText = speedKmh?.let { "$it km/h" } ?: "Unavailable",
                isAvailable = speedKmh != null,
            ),
            VehicleHealthParameter(
                name = "Calculated Engine Load (0104)",
                valueText = engineLoadPct?.let { "${"%.1f".format(it)}%" } ?: "Unavailable",
                isAvailable = engineLoadPct != null,
            ),
            VehicleHealthParameter(
                name = "Throttle Position (0111)",
                valueText = throttlePct?.let { "${"%.1f".format(it)}%" } ?: "Unavailable",
                isAvailable = throttlePct != null,
            ),
            VehicleHealthParameter(
                name = "Control Module Voltage (0142 / ATRV)",
                valueText = voltageV?.let { "${"%.2f".format(it)} V" } ?: "Unavailable",
                isAvailable = voltageV != null,
                isAbnormal = (voltageV != null && (voltageV < 11.8f || voltageV > 15.2f)),
                note = when {
                    voltageV != null && voltageV < 11.8f -> "Battery voltage is low (< 11.8 V)."
                    voltageV != null && voltageV > 15.2f -> "Alternator charging voltage is high (> 15.2 V)."
                    else -> null
                },
            ),
            VehicleHealthParameter(
                name = "Fuel Level (012F)",
                valueText = fuelLevelPct?.let { "${"%.0f".format(it)}%" } ?: "Not exposed by ECU",
                isAvailable = fuelLevelPct != null,
                isAbnormal = (fuelLevelPct != null && fuelLevelPct < 12f),
                note = if (fuelLevelPct != null && fuelLevelPct < 12f) "Low fuel level (< 12%)." else null,
            ),
            VehicleHealthParameter(
                name = "Malfunction Indicator Lamp (0101)",
                valueText = when (milOn) {
                    true -> "ON (Check Engine Active)"
                    false -> "OFF (Normal)"
                    null -> "Unavailable"
                },
                isAvailable = milOn != null,
                isAbnormal = (milOn == true),
                note = if (milOn == true) "Check Engine Light is commanded ON by ECU." else null,
            ),
            VehicleHealthParameter(
                name = "Diagnostic Trouble Codes (Mode 03)",
                valueText = if (dtcCodes.isEmpty()) "0 active DTCs" else "${dtcCodes.size} active: ${dtcCodes.joinToString(", ")}",
                isAvailable = true,
                isAbnormal = dtcCodes.isNotEmpty(),
                note = if (dtcCodes.isNotEmpty()) "Read-only scan detected stored fault codes. Inspect before long journeys." else null,
            ),
            VehicleHealthParameter(
                name = "Transmission / Gearbox Fluid & Pressure",
                valueText = "Unavailable via standard OBD-II",
                isAvailable = false,
                note = "Manufacturer-specific extended PIDs are required; KoPilot does not infer gearbox health from unsupported PIDs.",
            ),
        )
    }
}
