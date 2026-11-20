package ke.co.kopilot.feature.dashboard.status

import ke.co.kopilot.feature.sensors.CompassHeadingEstimator
import ke.co.kopilot.feature.sensors.CompassReading
import kotlin.math.roundToInt

enum class SystemReadinessLevel(val label: String, val spokenSummary: String) {
    ALL_OPERATIONAL("All Required Systems Operational", "All required systems are operational. KoPilot is ready to begin your trip."),
    READY_WITH_LIMITATIONS("Ready with Limitations", "KoPilot is ready with limitations."),
    ATTENTION_REQUIRED("Attention Required", "Attention required before driving."),
}

data class SystemStatusInput(
    val nowMillis: Long,
    val isMoving: Boolean,
    val cameraAvailable: Boolean,
    val cameraPermissionGranted: Boolean,
    val accelerometerAvailable: Boolean,
    val gyroscopeAvailable: Boolean,
    val mountCalibrationLabel: String,
    val gpsConnected: Boolean,
    val gpsAccuracyMeters: Float?,
    val compassReading: CompassReading?,
    val compassCalibrationPoor: Boolean = false,
    val obdConnected: Boolean,
    val obdIgnitionOffSuspected: Boolean = false,
    val obdAdapterName: String? = null,
    val obdProtocolName: String? = null,
    val obdRpm: Int? = null,
    val obdSpeedKmh: Int? = null,
    val obdFuelLevelPct: Float? = null,
    val obdMafGramsPerSec: Float? = null,
    val obdCoolantTempC: Int? = null,
    val obdEngineLoadPct: Float? = null,
    val obdThrottlePct: Float? = null,
    val obdVoltageV: Float? = null,
    val obdDtcCodes: List<String> = emptyList(),
    val obdMilOn: Boolean? = null,
    val obdSupportedPids: Set<String> = emptySet(),
    val watchConnected: Boolean,
    val watchName: String? = null,
    val watchBatteryPct: Int? = null,
    val watchHeartRateBpm: Int? = null,
    val watchHeartRateFresh: Boolean = false,
    val watchSensorContact: Boolean? = null,
    val sleepDurationMinutes: Int? = null,
    val sleepHistoryAvailable: Boolean = false,
    val weatherAvailable: Boolean,
    val weatherCondition: String? = null,
    val weatherTempC: Double? = null,
    val weatherRainProbPct: Int? = null,
    val weatherWindKmh: Double? = null,
    val weatherIsStaleCache: Boolean = false,
    val weatherObservationAgeMinutes: Long? = null,
    val isOnline: Boolean = false,
)

data class SystemStatusReport(
    val checkedAtMillis: Long,
    val readiness: SystemReadinessLevel,
    val cameraStatus: String,
    val gpsStatus: String,
    val headingDisplay: String,
    val headingSpoken: String,
    val motionSensorsStatus: String,
    val obdStatus: String,
    val obdProtocol: String,
    val rpmText: String,
    val fuelText: String,
    val mafText: String,
    val vehicleSpeedText: String,
    val coolantText: String,
    val voltageText: String,
    val diagnosticWarnings: List<String>,
    val smartwatchStatus: String,
    val heartRateText: String,
    val sleepSummaryText: String,
    val weatherSummaryText: String,
    val weatherObservationLabel: String,
    val limitations: List<String>,
    val spokenBriefingFull: String,
    val spokenBriefingDrivingConcise: String,
)

/**
 * Generates structured System Status Reports and natural spoken voice briefings
 * from 100% real verified telemetry. Never fabricates readings or suppresses warnings.
 */
object SystemStatusBriefingEngine {

    fun buildReport(input: SystemStatusInput): SystemStatusReport {
        val warnings = mutableListOf<String>()
        val limitations = mutableListOf<String>()
        val speechParts = mutableListOf<String>()

        speechParts.add("KoPilot system check complete.")

        // 1. Camera
        val cameraOk = input.cameraAvailable && input.cameraPermissionGranted
        val cameraStatus = when {
            cameraOk -> "Operational"
            !input.cameraPermissionGranted -> "Permission required"
            else -> "Unavailable"
        }
        if (cameraOk) {
            speechParts.add("Camera operational.")
        } else {
            warnings.add("Camera: $cameraStatus")
            speechParts.add("Camera is not available. $cameraStatus.")
        }

        // 2. GPS & Compass Heading (full words, True vs Magnetic North, unreliable handling)
        val headingReliable = input.compassReading != null &&
            input.compassReading.isReliable &&
            !input.compassReading.interferenceSuspected &&
            !input.compassCalibrationPoor
        val headingSpoken = if (headingReliable) {
            CompassHeadingEstimator.formatSpokenHeading(input.compassReading)
        } else {
            "Heading unavailable"
        }
        val headingDisplay = if (headingReliable) {
            "${CompassHeadingEstimator.formatDisplayHeading(input.compassReading)} (${input.compassReading!!.northReference})"
        } else {
            "Heading unavailable"
        }

        val gpsStatus = when {
            input.gpsConnected && input.gpsAccuracyMeters != null ->
                "Connected (±${input.gpsAccuracyMeters.roundToInt()} m)"
            input.gpsConnected -> "Connected"
            else -> "Unavailable"
        }
        if (input.gpsConnected) {
            if (headingSpoken == "Heading unavailable") {
                limitations.add("Compass heading unreliable or needs calibration")
                speechParts.add("GPS connected. Heading unavailable.")
            } else {
                speechParts.add("GPS connected. Current heading: $headingSpoken.")
            }
        } else {
            warnings.add("GPS unavailable")
            if (headingSpoken == "Heading unavailable") {
                speechParts.add("GPS is currently unavailable. Heading unavailable.")
            } else {
                speechParts.add("GPS is currently unavailable. Magnetic heading: $headingSpoken.")
            }
        }

        // 3. Phone Motion Sensors
        val motionOk = input.accelerometerAvailable && input.gyroscopeAvailable
        val motionStatus = when {
            motionOk -> "Operational (${input.mountCalibrationLabel})"
            input.accelerometerAvailable -> "Accelerometer active; gyroscope unavailable"
            else -> "Accelerometer unavailable"
        }
        if (motionOk) {
            speechParts.add("Phone motion sensors operational.")
        } else {
            if (!input.accelerometerAvailable) warnings.add("Accelerometer unavailable")
            else limitations.add("Gyroscope unavailable")
            speechParts.add("Phone motion sensors: $motionStatus.")
        }

        // 4. OBD-II Vehicle Telemetry (real PIDs only — never fabricate missing values)
        val obdStatus: String
        val obdProtocol = input.obdProtocolName?.takeIf { it.isNotBlank() } ?: if (input.obdConnected) "Connected" else "Not connected"
        val rpmText: String
        val fuelText: String
        val mafText: String
        val vehicleSpeedText: String
        val coolantText: String
        val voltageText: String

        if (input.obdConnected) {
            obdStatus = "Connected (${input.obdAdapterName ?: "OBD-II Adapter"})"
            speechParts.add("OBD two connected.")

            rpmText = input.obdRpm?.let { "$it RPM" } ?: "Not available"
            if (input.obdRpm != null) {
                if (input.obdRpm == 0) {
                    speechParts.add("Engine speed is zero revolutions per minute.")
                } else {
                    speechParts.add("Engine running at ${input.obdRpm} revolutions per minute.")
                }
            }

            fuelText = input.obdFuelLevelPct?.let { "${it.roundToInt()}%" } ?: "Not available from this vehicle"
            if (input.obdFuelLevelPct != null) {
                speechParts.add("Fuel level is ${input.obdFuelLevelPct.roundToInt()} percent.")
            } else if ("012F" !in input.obdSupportedPids && input.obdSupportedPids.isNotEmpty()) {
                speechParts.add("Fuel level is not available from this vehicle.")
            }

            mafText = input.obdMafGramsPerSec?.let { "${"%.1f".format(it)} g/s" } ?: "Not available from this vehicle"
            if (input.obdMafGramsPerSec != null) {
                speechParts.add("Mass airflow is ${"%.1f".format(input.obdMafGramsPerSec)} grams per second.")
            } else if ("0110" !in input.obdSupportedPids && input.obdSupportedPids.isNotEmpty()) {
                speechParts.add("Mass airflow is not available from this vehicle.")
            }

            coolantText = input.obdCoolantTempC?.let { "$it °C" } ?: "Not available"
            if (input.obdCoolantTempC != null) {
                speechParts.add("Engine coolant temperature is ${input.obdCoolantTempC} degrees Celsius.")
                if (input.obdCoolantTempC >= 105) {
                    warnings.add("High coolant temperature (${input.obdCoolantTempC}°C)")
                }
            }

            vehicleSpeedText = input.obdSpeedKmh?.let { "$it km/h" } ?: "Not available"
            if (input.obdSpeedKmh != null) {
                val spokenSpd = if (input.obdSpeedKmh == 0) "zero" else "${input.obdSpeedKmh}"
                speechParts.add("Vehicle speed is $spokenSpd kilometers per hour.")
            }

            voltageText = input.obdVoltageV?.let { "${"%.1f".format(it)} V" } ?: "Not available"
            if (input.obdVoltageV != null && input.obdVoltageV < 11.8f) {
                warnings.add("Low vehicle battery voltage (${"%.1f".format(input.obdVoltageV)} V)")
                speechParts.add("Warning: vehicle voltage is low at ${"%.1f".format(input.obdVoltageV)} volts.")
            }

            if (input.obdMilOn == true) {
                warnings.add("Check Engine light (MIL) is ON")
            }
            if (input.obdDtcCodes.isNotEmpty()) {
                warnings.add("${input.obdDtcCodes.size} diagnostic trouble code(s): ${input.obdDtcCodes.take(3).joinToString(", ")}")
                speechParts.add("Diagnostic warning: ${input.obdDtcCodes.size} trouble codes detected.")
            }
        } else {
            obdStatus = if (input.obdIgnitionOffSuspected) {
                "Disconnected (Vehicle ignition may be off)"
            } else {
                "Disconnected"
            }
            limitations.add("OBD-II adapter not connected")
            rpmText = "Unavailable"
            fuelText = "Unavailable"
            mafText = "Unavailable"
            vehicleSpeedText = "Unavailable"
            coolantText = "Unavailable"
            voltageText = "Unavailable"
            if (input.obdIgnitionOffSuspected) {
                speechParts.add("OBD two ECU is not responding. Ensure vehicle ignition is on.")
            } else {
                speechParts.add("OBD two adapter is not connected.")
            }
        }

        // 5. Smartwatch & Driver Wellness (fresh readings only, never claim medical fitness)
        val smartwatchStatus: String
        val heartRateText: String
        if (input.watchConnected) {
            val batt = input.watchBatteryPct?.let { " • $it% battery" } ?: ""
            smartwatchStatus = "Connected (${input.watchName ?: "Smartwatch"}$batt)"
            speechParts.add("Smartwatch connected.")
            if (input.watchHeartRateBpm != null && input.watchHeartRateFresh && input.watchSensorContact != false) {
                heartRateText = "${input.watchHeartRateBpm} BPM (Live)"
                speechParts.add("Your heart rate is ${input.watchHeartRateBpm} beats per minute.")
            } else if (input.watchHeartRateBpm != null && !input.watchHeartRateFresh) {
                heartRateText = "Stale reading (expired)"
                limitations.add("Smartwatch heart-rate reading is stale")
                speechParts.add("No fresh heart rate reading is currently available.")
            } else {
                heartRateText = "No live reading"
                speechParts.add("Heart rate reading is currently unavailable.")
            }
        } else {
            smartwatchStatus = "Disconnected"
            heartRateText = "Unavailable"
            limitations.add("Smartwatch not connected")
            speechParts.add("Smartwatch is not connected.")
        }

        val sleepSummaryText: String
        if (input.sleepHistoryAvailable && input.sleepDurationMinutes != null && input.sleepDurationMinutes > 0) {
            val hrs = input.sleepDurationMinutes / 60
            val mins = input.sleepDurationMinutes % 60
            sleepSummaryText = if (hrs > 0) "${hrs}h ${mins}m recorded" else "${mins}m recorded"
            val spokenSleep = when {
                hrs > 0 && mins > 0 -> "$hrs hours and $mins minutes"
                hrs > 0 -> "$hrs hours"
                else -> "$mins minutes"
            }
            speechParts.add("Last night's recorded sleep shows $spokenSleep.")
            if (input.sleepDurationMinutes < 300) { // < 5 hours
                speechParts.add("Recorded sleep is short. Consider resting or taking regular breaks during your drive.")
            }
        } else {
            sleepSummaryText = "Sleep history unavailable"
            limitations.add("Sleep history unavailable")
            speechParts.add("Sleep history is not available.")
        }

        // 6. Weather
        val weatherSummaryText: String
        val weatherObservationLabel: String
        if (input.weatherAvailable && !input.weatherCondition.isNullOrBlank()) {
            val tempStr = input.weatherTempC?.let { "${it.roundToInt()} °C" } ?: ""
            val ageMin = input.weatherObservationAgeMinutes
            weatherObservationLabel = when {
                input.weatherIsStaleCache && ageMin != null -> "Cached (${ageMin}m ago)"
                input.weatherIsStaleCache -> "Cached (offline)"
                ageMin != null && ageMin > 0 -> "Updated ${ageMin}m ago"
                else -> "Live"
            }
            weatherSummaryText = buildString {
                append(input.weatherCondition)
                if (tempStr.isNotEmpty()) append(", $tempStr")
                input.weatherRainProbPct?.takeIf { it >= 30 }?.let { append(" • $it% rain") }
                input.weatherWindKmh?.takeIf { it >= 25.0 }?.let { append(" • Wind ${it.roundToInt()} km/h") }
            }
            val spokenWeather = buildString {
                if (input.weatherIsStaleCache) {
                    append("Cached weather report: ")
                } else {
                    append("Current reported weather is ")
                }
                append(input.weatherCondition.lowercase())
                input.weatherTempC?.let {
                    append(", with a temperature of ${it.roundToInt()} degrees Celsius")
                }
                input.weatherRainProbPct?.takeIf { it >= 40 }?.let {
                    append(", rain probability $it percent")
                }
                append(".")
            }
            speechParts.add(spokenWeather)
        } else {
            weatherSummaryText = "Current weather information is unavailable"
            weatherObservationLabel = if (input.isOnline) "Unavailable" else "Offline (no cache)"
            limitations.add("Weather unavailable")
            speechParts.add("Current weather information is unavailable.")
        }

        // 7. Diagnostic warnings & Final readiness evaluation
        val readiness = when {
            !cameraOk || !input.gpsConnected || !input.accelerometerAvailable || warnings.isNotEmpty() ->
                SystemReadinessLevel.ATTENTION_REQUIRED
            limitations.isNotEmpty() ->
                SystemReadinessLevel.READY_WITH_LIMITATIONS
            else ->
                SystemReadinessLevel.ALL_OPERATIONAL
        }

        if (warnings.isEmpty()) {
            speechParts.add("No diagnostic warnings detected from the available checks.")
        } else {
            speechParts.add("Warnings detected: ${warnings.joinToString(". ")}.")
        }

        when (readiness) {
            SystemReadinessLevel.ALL_OPERATIONAL -> {
                speechParts.add(readiness.spokenSummary)
            }
            SystemReadinessLevel.READY_WITH_LIMITATIONS -> {
                val missingList = limitations.take(3).joinToString(", ")
                speechParts.add("Ready with limitations: $missingList.")
            }
            SystemReadinessLevel.ATTENTION_REQUIRED -> {
                speechParts.add("Attention required before driving. Please review warnings.")
            }
        }

        val fullBriefing = expandAbbreviationsForSpeech(speechParts.joinToString(" "))

        // Concise driving-mode spoken summary when vehicle is moving
        val conciseParts = mutableListOf<String>()
        conciseParts.add("KoPilot status check.")
        if (warnings.isNotEmpty()) {
            conciseParts.add("Attention: ${warnings.take(2).joinToString(". ")}.")
        } else {
            conciseParts.add("${readiness.label}.")
        }
        if (headingSpoken != "Heading unavailable") {
            conciseParts.add("Heading $headingSpoken.")
        }
        if (input.obdConnected && input.obdRpm != null) {
            conciseParts.add("Engine at ${input.obdRpm} revolutions per minute.")
        }
        if (input.watchConnected && input.watchHeartRateBpm != null && input.watchHeartRateFresh) {
            conciseParts.add("Heart rate ${input.watchHeartRateBpm} beats per minute.")
        }
        conciseParts.add("Full report available when safely stopped.")
        val conciseBriefing = expandAbbreviationsForSpeech(conciseParts.joinToString(" "))

        return SystemStatusReport(
            checkedAtMillis = input.nowMillis,
            readiness = readiness,
            cameraStatus = cameraStatus,
            gpsStatus = gpsStatus,
            headingDisplay = headingDisplay,
            headingSpoken = headingSpoken,
            motionSensorsStatus = motionStatus,
            obdStatus = obdStatus,
            obdProtocol = obdProtocol,
            rpmText = rpmText,
            fuelText = fuelText,
            mafText = mafText,
            vehicleSpeedText = vehicleSpeedText,
            coolantText = coolantText,
            voltageText = voltageText,
            diagnosticWarnings = warnings,
            smartwatchStatus = smartwatchStatus,
            heartRateText = heartRateText,
            sleepSummaryText = sleepSummaryText,
            weatherSummaryText = weatherSummaryText,
            weatherObservationLabel = weatherObservationLabel,
            limitations = limitations,
            spokenBriefingFull = fullBriefing,
            spokenBriefingDrivingConcise = conciseBriefing,
        )
    }

    /**
     * Expands technical abbreviations before TTS so Voice Studio pronounces all units naturally.
     */
    fun expandAbbreviationsForSpeech(raw: String): String {
        return raw
            .replace(Regex("\\bRPM\\b"), "revolutions per minute")
            .replace(Regex("\\bMAF\\b"), "mass airflow")
            .replace(Regex("\\bkm/h\\b", RegexOption.IGNORE_CASE), "kilometers per hour")
            .replace(Regex("°C"), " degrees Celsius")
            .replace(Regex("\\bBPM\\b"), "beats per minute")
            .replace(Regex("\\bg/s\\b"), "grams per second")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
