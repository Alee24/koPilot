package ke.co.kopilot.intelligence

import ke.co.kopilot.core.database.entity.SensorHistoryEntity
import ke.co.kopilot.feature.diagnostics.ObdPidCatalog
import ke.co.kopilot.feature.sensors.RealTimeDrivingIntelligence
import ke.co.kopilot.feature.trips.TripRepository
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deterministic, offline-first Vehicle Intelligence Engine.
 * Interprets live OBD-II, phone sensor, and historical Room telemetry using validated
 * automotive rules. Never fabricates measurements or bypasses read-only ECU constraints.
 */
@Singleton
class VehicleIntelligenceEngine @Inject constructor(
    private val telemetryRepo: VehicleTelemetryRepository,
    private val drivingIntelligence: RealTimeDrivingIntelligence,
    private val tripRepo: TripRepository,
) {

    // -----------------------------------------------------------------------
    // Read-Only AI Tool Interface (Section 8)
    // -----------------------------------------------------------------------

    fun getVehicleSnapshot(): VehicleSnapshot = telemetryRepo.getVehicleSnapshot()

    suspend fun getCurrentEngineHealth(): String {
        val snap = telemetryRepo.getVehicleSnapshot()
        val history = telemetryRepo.getRecentHistory(snap.timestampMillis - 30 * 60_000L)
        return analyzeEngine(snap, history).joinToString(" ")
    }

    fun getSupportedSensors(): Map<String, List<String>> {
        val snap = telemetryRepo.getVehicleSnapshot()
        val available = snap.engineReadings.values
            .filter { it.validity == MeasurementValidity.AVAILABLE || it.validity == MeasurementValidity.STALE }
            .map { "${it.name} (${it.pidOrSensorId})" }
        val unsupported = snap.engineReadings.values
            .filter { it.validity == MeasurementValidity.UNSUPPORTED }
            .map { "${it.name} (${it.pidOrSensorId})" }
        return mapOf("available" to available, "unsupported" to unsupported)
    }

    fun getLatestSensorReading(sensorId: String): MeasurementReading<Float>? {
        val snap = telemetryRepo.getVehicleSnapshot()
        return snap.engineReadings[sensorId.uppercase()]
            ?: snap.engineReadings.values.firstOrNull { it.name.contains(sensorId, ignoreCase = true) }
    }

    suspend fun getSensorHistory(sensorId: String, durationMs: Long): List<SensorHistoryEntity> {
        val since = System.currentTimeMillis() - durationMs.coerceAtLeast(60_000L)
        return telemetryRepo.getRecentHistory(since)
    }

    fun getDiagnosticCodes(): List<DtcInterpretation> {
        val diag = telemetryRepo.getVehicleSnapshot().diagnostics
        val out = mutableListOf<DtcInterpretation>()
        diag.storedCodes.forEach { out.add(interpretDtc(it, isPending = false, isPermanent = false)) }
        diag.pendingCodes.forEach { out.add(interpretDtc(it, isPending = true, isPermanent = false)) }
        diag.permanentCodes.forEach { out.add(interpretDtc(it, isPending = false, isPermanent = true)) }
        return out
    }

    suspend fun getTripSummary(tripId: String? = null): String {
        val trips = tripRepo.trips.firstOrNull().orEmpty()
        val target = if (tripId != null) trips.firstOrNull { it.id == tripId } else trips.firstOrNull()
        return if (target == null) {
            "No completed trips have been saved locally yet."
        } else {
            val mins = (target.durationSeconds / 60L).coerceAtLeast(1L)
            "Last trip (${target.originLabel ?: "Local"} to ${target.destinationLabel ?: "Destination"}): " +
                "${"%.1f".format(target.distanceKm)} km in $mins minutes, max speed ${target.maxSpeedKmh.roundToInt()} km/h, " +
                "avg speed ${target.avgSpeedKmh.roundToInt()} km/h, ${target.signsDetectedCount} road signs detected."
        }
    }

    fun getFuelEfficiencySummary(): String {
        val snap = telemetryRepo.getVehicleSnapshot()
        return analyzeFuel(snap).joinToString(" ")
    }

    fun getDrivingSafetySummary(): String {
        val intel = drivingIntelligence.state.value
        return "Driving Safety Score is ${intel.safetyScore}/100 (state: ${intel.drivingState.label}). " +
            "Recorded events: ${intel.harshBrakeCount} harsh braking, ${intel.hardAccelCount} aggressive acceleration, " +
            "${intel.sharpCornerCount} sharp cornering, ${intel.overspeedEventCount} overspeeding."
    }

    suspend fun getVehicleHealthAlerts(): List<String> {
        val snap = telemetryRepo.getVehicleSnapshot()
        val history = telemetryRepo.getRecentHistory(snap.timestampMillis - 30 * 60_000L)
        return collectConcerns(snap, history)
    }

    // -----------------------------------------------------------------------
    // Full Vehicle Health Interview (Section 9)
    // -----------------------------------------------------------------------

    suspend fun generateFullVehicleHealthReport(performFreshDtcScan: Boolean = false): FullVehicleHealthReport {
        if (performFreshDtcScan) {
            telemetryRepo.performSafeReadOnlyDtcScan()
        }
        val snap = telemetryRepo.getVehicleSnapshot()
        val history = telemetryRepo.getRecentHistory(snap.timestampMillis - 60 * 60_000L)

        val monitoringStatus = if (snap.obdConnected) {
            "OBD-II Connected (${snap.obdProtocol ?: "Auto"}) • ${snap.availableCount()} active PIDs • Phone GPS/IMU active."
        } else {
            "OBD-II Disconnected — relying on phone GPS, motion sensors, and cached history."
        }

        val engineObs = analyzeEngine(snap, history)
        val electricalObs = analyzeElectrical(snap, history)
        val fuelObs = analyzeFuel(snap)
        val diagObs = analyzeDiagnostics(snap)
        val trends = analyzeTrends(snap, history)
        val concerns = collectConcerns(snap, history)
        val checks = buildRecommendedChecks(snap, concerns)
        val missing = snap.engineReadings.values
            .filter { it.validity == MeasurementValidity.UNSUPPORTED || it.validity == MeasurementValidity.TEMPORARILY_UNAVAILABLE }
            .map { "${it.name} (${it.pidOrSensorId}): ${it.validity.label}" }

        val spoken = buildString {
            if (!snap.obdConnected) {
                append("OBD-II is currently disconnected, so live ECU readings are unavailable. ")
                append("Phone driving safety score is ${snap.phoneSensors.drivingSafetyScore} out of 100 and fuel efficiency score is ${snap.phoneSensors.fuelEfficiencyScore} out of 100. ")
            } else {
                val rpm = snap.reading("010C")?.value?.roundToInt()
                val coolant = snap.reading("0105")?.value?.roundToInt()
                val volt = snap.reading("0142")?.value
                append("For your ${snap.vehicleIdentity}: ")
                if (rpm != null) append("Engine is at $rpm RPM. ")
                if (coolant != null) append("Coolant temperature is $coolant degrees Celsius. ")
                if (volt != null) append("Module voltage is ${"%.1f".format(volt)} volts. ")
                append(
                    when (snap.diagnostics.scanStatus) {
                        DtcScanStatus.SCANNED_CLEAN -> "Diagnostic scan found zero stored fault codes, though regular inspection still applies. "
                        DtcScanStatus.SCANNED_FAULTS_FOUND -> "Warning: ${snap.diagnostics.storedCodes.size} stored fault code(s) detected: ${snap.diagnostics.storedCodes.joinToString(", ")}. "
                        DtcScanStatus.NOT_SCANNED -> "A full fault code scan has not been completed yet. "
                        DtcScanStatus.UNAVAILABLE -> "Diagnostic code status is unavailable. "
                    },
                )
            }
            if (concerns.isNotEmpty()) {
                append("Attention needed: ${concerns.first()}")
            } else {
                append("No critical abnormalities detected in currently monitored sensors.")
            }
        }

        val detailed = buildString {
            appendLine("KOPILOT VEHICLE HEALTH REPORT (${snap.timestampIso})")
            appendLine("Vehicle: ${snap.vehicleIdentity}")
            appendLine("Monitoring: $monitoringStatus")
            appendLine()
            appendLine("ENGINE OBSERVATIONS:")
            engineObs.forEach { appendLine("• $it") }
            appendLine()
            appendLine("ELECTRICAL OBSERVATIONS:")
            electricalObs.forEach { appendLine("• $it") }
            appendLine()
            appendLine("FUEL EFFICIENCY OBSERVATIONS:")
            fuelObs.forEach { appendLine("• $it") }
            appendLine()
            appendLine("DIAGNOSTIC FINDINGS:")
            diagObs.forEach { appendLine("• $it") }
            if (trends.isNotEmpty()) {
                appendLine()
                appendLine("RECENT TRENDS:")
                trends.forEach { appendLine("• $it") }
            }
            if (concerns.isNotEmpty()) {
                appendLine()
                appendLine("POTENTIAL CONCERNS:")
                concerns.forEach { appendLine("• $it") }
            }
            appendLine()
            appendLine("RECOMMENDED CHECKS:")
            checks.forEach { appendLine("• $it") }
            if (missing.isNotEmpty()) {
                appendLine()
                appendLine("MISSING OR UNSUPPORTED SENSORS (${missing.size}):")
                missing.take(12).forEach { appendLine("• $it") }
            }
        }.trim()

        return FullVehicleHealthReport(
            generatedAtIso = snap.timestampIso,
            vehicleIdentity = snap.vehicleIdentity,
            monitoringStatus = monitoringStatus,
            engineObservations = engineObs,
            electricalObservations = electricalObs,
            fuelObservations = fuelObs,
            diagnosticFindings = diagObs,
            recentTrends = trends,
            potentialConcerns = concerns,
            recommendedChecks = checks,
            missingOrUnsupported = missing,
            spokenSummary = spoken,
            detailedText = detailed,
        )
    }

    // -----------------------------------------------------------------------
    // Offline Natural-Language Vehicle Assistant (Sections 1, 10, 12, Final)
    // -----------------------------------------------------------------------

    suspend fun answerVehicleQuestion(question: String): Pair<String, FullVehicleHealthReport?> {
        val q = question.lowercase().trim()
        val snap = telemetryRepo.getVehicleSnapshot()

        // Full health report / everything about my car / how is my car performing
        if (q.contains("full vehicle health") ||
            q.contains("health report") ||
            q.contains("tell me everything") ||
            q.contains("how is my car") ||
            q.contains("how is my engine")
        ) {
            val report = generateFullVehicleHealthReport(performFreshDtcScan = snap.obdConnected)
            return report.spokenSummary to report
        }

        // Historical comparison questions (Section 10)
        if (q.contains("increased") && (q.contains("temp") || q.contains("coolant"))) {
            val samples = telemetryRepo.getRecentHistory(snap.timestampMillis - 60 * 60_000L)
                .mapNotNull { it.coolantTempC }
            return if (samples.size < 2) {
                "Not enough recorded coolant temperature samples from this trip yet to determine a trend." to null
            } else {
                val delta = samples.last() - samples.first()
                val msg = when {
                    delta >= 3 -> "Yes, your coolant temperature increased by $delta degrees Celsius (from ${samples.first()}°C to ${samples.last()}°C)."
                    delta <= -3 -> "No, your coolant temperature decreased by ${abs(delta)} degrees Celsius (from ${samples.first()}°C to ${samples.last()}°C)."
                    else -> "Your coolant temperature has stayed steady around ${samples.last()} degrees Celsius (change of $delta°C)."
                }
                msg to null
            }
        }

        if (q.contains("hotter yesterday")) {
            val now = snap.timestampMillis
            val oneDayMs = 24L * 60L * 60L * 1000L
            val todayMax = telemetryRepo.getRecentHistory(now - oneDayMs).mapNotNull { it.coolantTempC }.maxOrNull()
            val yestMax = telemetryRepo.getHistoryBetween(now - 2 * oneDayMs, now - oneDayMs).mapNotNull { it.coolantTempC }.maxOrNull()
            return if (todayMax == null || yestMax == null) {
                "I don't have sufficient recorded coolant temperature data from both today and yesterday to compare." to null
            } else {
                val msg = when {
                    yestMax > todayMax -> "Yes, peak recorded coolant temperature yesterday was $yestMax°C compared to $todayMax°C today."
                    yestMax < todayMax -> "No, today's peak coolant temperature ($todayMax°C) is higher than yesterday's ($yestMax°C)."
                    else -> "Peak recorded coolant temperature was the same ($todayMax°C) both today and yesterday."
                }
                msg to null
            }
        }

        if (q.contains("highest rpm") || q.contains("max rpm")) {
            val now = snap.timestampMillis
            val maxRpm = telemetryRepo.getRecentHistory(now - 12 * 60 * 60_000L).mapNotNull { it.rpm }.maxOrNull()
            return if (maxRpm == null) {
                "No recorded OBD-II RPM history is available from today yet." to null
            } else {
                "Your highest recorded engine RPM today was $maxRpm RPM." to null
            }
        }

        if (q.contains("efficiency improved") || q.contains("fuel improved")) {
            val samples = telemetryRepo.getRecentHistory(snap.timestampMillis - 24 * 60 * 60_000L)
            return if (samples.size < 4) {
                "Not enough recorded fuel efficiency history yet to compare." to null
            } else {
                val firstHalf = samples.take(samples.size / 2).map { it.fuelScore }.average()
                val secondHalf = samples.drop(samples.size / 2).map { it.fuelScore }.average()
                val diff = (secondHalf - firstHalf).roundToInt()
                val msg = when {
                    diff >= 3 -> "Yes, your fuel efficiency score improved by $diff points (from ${firstHalf.roundToInt()} to ${secondHalf.roundToInt()}/100)."
                    diff <= -3 -> "Your fuel efficiency score dropped by ${abs(diff)} points (from ${firstHalf.roundToInt()} to ${secondHalf.roundToInt()}/100)."
                    else -> "Your fuel efficiency score has remained steady around ${secondHalf.roundToInt()}/100."
                }
                msg to null
            }
        }

        // Direct measurement queries
        if (q.contains("coolant") || q.contains("engine temp") || q.contains("overheat")) {
            val reading = snap.reading("0105")
            return if (reading?.validity != MeasurementValidity.AVAILABLE || reading.value == null) {
                "I can't retrieve your engine temperature at the moment. The sensor reading is ${reading?.validity?.label?.lowercase() ?: "unavailable"}." to null
            } else {
                val c = reading.value.roundToInt()
                val status = when {
                    c >= 108 -> "Warning: this is in the overheating range. Pull over safely when possible."
                    c >= 102 -> "This is slightly elevated; monitor closely."
                    c < 60 -> "The engine is still warming up."
                    else -> "This is within normal operating temperature."
                }
                "Your engine coolant temperature is $c degrees Celsius. $status" to null
            }
        }

        if (q.contains("rpm")) {
            val reading = snap.reading("010C")
            return if (reading?.validity != MeasurementValidity.AVAILABLE || reading.value == null) {
                "I can't retrieve your engine RPM right now. The OBD-II reading is ${reading?.validity?.label?.lowercase() ?: "unavailable"}." to null
            } else {
                "Your engine is currently running at ${reading.value.roundToInt()} RPM." to null
            }
        }

        if (q.contains("volt") || q.contains("battery") || q.contains("alternator") || q.contains("charging")) {
            val obs = analyzeElectrical(snap, emptyList())
            return obs.joinToString(" ") to null
        }

        if (q.contains("fault") || q.contains("dtc") || q.contains("error code") || q.contains("check engine") || q.contains("warning light") || q.contains("mil")) {
            val diag = snap.diagnostics
            val msg = when (diag.scanStatus) {
                DtcScanStatus.UNAVAILABLE -> "I cannot check for Check Engine Light codes because OBD-II is disconnected."
                DtcScanStatus.NOT_SCANNED -> "A Check Engine diagnostic scan has not completed yet. Connect OBD-II and wait a moment."
                DtcScanStatus.SCANNED_CLEAN -> "No Check Engine Light fault codes are stored right now. Regular maintenance checks still apply."
                DtcScanStatus.SCANNED_FAULTS_FOUND -> {
                    val codes = getDiagnosticCodes().joinToString("; ") { "${it.code}: ${it.description}" }
                    "Check Engine Light alert: ${diag.storedCodes.size} stored fault code(s) found — $codes. Please have your vehicle inspected."
                }
            }
            return msg to null
        }

        if (q.contains("fuel")) {
            return getFuelEfficiencySummary() to null
        }

        if (q.contains("sensor") && (q.contains("available") || q.contains("support"))) {
            val sup = getSupportedSensors()
            val avail = sup["available"].orEmpty()
            return if (avail.isEmpty()) {
                "No OBD-II PIDs are currently active. Phone GPS, accelerometer, gyroscope, and camera AI sensors are available locally." to null
            } else {
                "${avail.size} OBD-II sensors are active: ${avail.take(8).joinToString(", ")}." to null
            }
        }

        if (q.contains("check before driving") || q.contains("pre-trip")) {
            val checks = buildRecommendedChecks(snap, collectConcerns(snap, emptyList()))
            return "Pre-drive check: ${checks.joinToString(" ")}" to null
        }

        if (q.contains("last trip")) {
            return getTripSummary(null) to null
        }

        // Default fallback: concise live car summary
        val report = generateFullVehicleHealthReport(performFreshDtcScan = false)
        return report.spokenSummary to report
    }

    // -----------------------------------------------------------------------
    // Deterministic Automotive Rule Analyzers (Section 7)
    // -----------------------------------------------------------------------

    private fun analyzeEngine(snap: VehicleSnapshot, history: List<SensorHistoryEntity>): List<String> {
        if (!snap.obdConnected) {
            return listOf("OBD-II is disconnected; live engine telemetry is unavailable.")
        }
        val out = mutableListOf<String>()
        val rpm = snap.reading("010C")
        val coolant = snap.reading("0105")
        val load = snap.reading("0104")
        val throttle = snap.reading("0111")
        val iat = snap.reading("010F")
        val maf = snap.reading("0110")

        if (coolant?.validity == MeasurementValidity.AVAILABLE && coolant.value != null) {
            val c = coolant.value.roundToInt()
            val note = when {
                c >= 108 -> "CRITICAL: Potential overheating ($c°C)."
                c >= 102 -> "Elevated coolant temperature ($c°C)."
                c < 60 -> "Warming up ($c°C)."
                else -> "Normal operating temperature ($c°C)."
            }
            out.add("Coolant Temperature: $note")
        } else {
            out.add("Coolant Temperature: ${coolant?.validity?.label ?: "Unavailable"}.")
        }

        if (rpm?.validity == MeasurementValidity.AVAILABLE && rpm.value != null) {
            val r = rpm.value.roundToInt()
            val speed = snap.phoneSensors.gpsSpeedKmh ?: snap.reading("010D")?.value ?: 0f
            val rpmNote = when {
                r == 0 -> "Engine off / ignition on ($r RPM)."
                speed < 3f && r in 600..950 -> "Steady idle ($r RPM)."
                speed < 3f && r > 1100 -> "High idle ($r RPM)."
                r >= 4000 -> "High RPM load ($r RPM)."
                else -> "Normal running speed ($r RPM)."
            }
            out.add("Engine RPM: $rpmNote")
        }

        if (load?.validity == MeasurementValidity.AVAILABLE && load.value != null) {
            out.add("Calculated Engine Load: ${"%.1f".format(load.value)}%.")
        }
        if (throttle?.validity == MeasurementValidity.AVAILABLE && throttle.value != null) {
            out.add("Throttle Position: ${"%.1f".format(throttle.value)}%.")
        }
        if (iat?.validity == MeasurementValidity.AVAILABLE && iat.value != null) {
            out.add("Intake Air Temperature: ${iat.value.roundToInt()}°C.")
        }
        if (maf?.validity == MeasurementValidity.AVAILABLE && maf.value != null) {
            out.add("Mass Airflow (MAF): ${"%.2f".format(maf.value)} g/s.")
        }

        val coolantSeries = history.mapNotNull { it.coolantTempC }
        if (coolantSeries.size >= 3) {
            val rise = coolantSeries.last() - coolantSeries.first()
            if (rise >= 8) {
                out.add("Temperature trend: rose +$rise°C over recent samples.")
            }
        }
        return out
    }

    private fun analyzeElectrical(snap: VehicleSnapshot, history: List<SensorHistoryEntity>): List<String> {
        val volt = snap.reading("0142")
        if (volt?.validity != MeasurementValidity.AVAILABLE || volt.value == null) {
            return listOf("Control module voltage is ${volt?.validity?.label?.lowercase() ?: "unavailable"}.")
        }
        val v = volt.value
        val rpm = snap.reading("010C")?.value ?: 0f
        val status = when {
            rpm > 500f && v in 13.3f..14.9f -> "Normal charging-system voltage (${"%.2f".format(v)}V while running)."
            rpm > 500f && v < 12.8f -> "Low voltage while engine running (${"%.2f".format(v)}V) — possible charging-system concern (voltage alone is not a definitive alternator or battery diagnosis)."
            rpm <= 500f && v < 12.0f -> "Low resting voltage (${"%.2f".format(v)}V with engine off) — battery charge may be low (load test recommended for confirmation)."
            v > 15.2f -> "Elevated voltage (${"%.2f".format(v)}V) — voltage regulator check advised."
            else -> "Measured voltage is ${"%.2f".format(v)}V."
        }
        val volts = history.mapNotNull { it.voltageV }
        val trendNote = if (volts.size >= 3) {
            " Recent range: ${"%.2f".format(volts.min())}V–${"%.2f".format(volts.max())}V."
        } else {
            ""
        }
        return listOf(status + trendNote)
    }

    private fun analyzeFuel(snap: VehicleSnapshot): List<String> {
        val out = mutableListOf<String>()
        val fuelRate = snap.reading("015E")
        val maf = snap.reading("0110")
        val stft = snap.reading("0106")
        val ltft = snap.reading("0107")
        val fuelLevel = snap.reading("012F")
        val intel = drivingIntelligence.state.value

        if (fuelRate?.validity == MeasurementValidity.AVAILABLE && fuelRate.value != null) {
            out.add("Measured Fuel Rate (015E): ${"%.2f".format(fuelRate.value)} L/h.")
        } else if (maf?.validity == MeasurementValidity.AVAILABLE && maf.value != null) {
            // Petrol stoichiometric AFR ~14.7:1, fuel density ~0.745 kg/L -> L/h = (MAF * 3600) / (14.7 * 745)
            val estLph = (maf.value * 3600f) / (14.7f * 745f)
            out.add("Estimated Fuel Rate (calculated from MAF): ~${"%.2f".format(estLph)} L/h.")
        } else {
            out.add("Direct fuel rate (015E) and MAF (0110) are unavailable.")
        }

        if (fuelLevel?.validity == MeasurementValidity.AVAILABLE && fuelLevel.value != null) {
            out.add("Fuel Tank Level (measured): ${"%.1f".format(fuelLevel.value)}%.")
        }
        if (stft?.validity == MeasurementValidity.AVAILABLE && stft.value != null) {
            val s = stft.value
            val trimNote = if (abs(s) > 12f) " (elevated trim)" else ""
            out.add("Short-Term Fuel Trim (B1): ${"%.1f".format(s)}%$trimNote.")
        }
        if (ltft?.validity == MeasurementValidity.AVAILABLE && ltft.value != null) {
            out.add("Long-Term Fuel Trim (B1): ${"%.1f".format(ltft.value)}%.")
        }
        out.add("Driving Fuel Efficiency Score: ${intel.fuelEfficiencyScore}/100 (idling ${intel.idlingRatioPct}%, ${intel.hardAccelCount} hard accel events).")
        return out
    }

    private fun analyzeDiagnostics(snap: VehicleSnapshot): List<String> {
        val diag = snap.diagnostics
        return when (diag.scanStatus) {
            DtcScanStatus.UNAVAILABLE -> listOf("Diagnostic scan unavailable because OBD-II is disconnected.")
            DtcScanStatus.NOT_SCANNED -> listOf("DTC scan has not been completed yet. Empty list does not prove zero faults.")
            DtcScanStatus.SCANNED_CLEAN -> listOf("OBD-II Mode 03/07/0A scan completed: 0 stored, pending, or permanent fault codes found (routine inspection still recommended).")
            DtcScanStatus.SCANNED_FAULTS_FOUND -> {
                val list = mutableListOf<String>()
                getDiagnosticCodes().forEach { d ->
                    val kind = when {
                        d.isPending -> "Pending"
                        d.isPermanent -> "Permanent"
                        else -> "Stored"
                    }
                    list.add("$kind ${d.code}: ${d.description}. Possible causes: ${d.possibleCauses}. Suggested check: ${d.suggestedChecks}.")
                }
                list
            }
        }
    }

    private fun analyzeTrends(snap: VehicleSnapshot, history: List<SensorHistoryEntity>): List<String> {
        if (history.size < 2) return emptyList()
        val out = mutableListOf<String>()
        val temps = history.mapNotNull { it.coolantTempC }
        if (temps.size >= 2) {
            out.add("Coolant trend (recent): ${temps.first()}°C -> ${temps.last()}°C (peak ${temps.max()}°C).")
        }
        val rpms = history.mapNotNull { it.rpm }
        if (rpms.isNotEmpty()) {
            out.add("RPM peak in window: ${rpms.max()} RPM (avg ${rpms.average().roundToInt()} RPM).")
        }
        return out
    }

    private fun collectConcerns(snap: VehicleSnapshot, history: List<SensorHistoryEntity>): List<String> {
        val concerns = mutableListOf<String>()
        val coolant = snap.reading("0105")?.value
        if (coolant != null && coolant >= 102f) {
            concerns.add("Coolant temperature elevated at ${coolant.roundToInt()}°C.")
        }
        val volt = snap.reading("0142")?.value
        val rpm = snap.reading("010C")?.value ?: 0f
        if (volt != null && rpm > 500f && volt < 12.8f) {
            concerns.add("Running module voltage is low (${"%.2f".format(volt)}V).")
        }
        if (snap.diagnostics.scanStatus == DtcScanStatus.SCANNED_FAULTS_FOUND) {
            concerns.add("Diagnostic fault code(s) active: ${(snap.diagnostics.storedCodes + snap.diagnostics.pendingCodes).distinct().joinToString(", ")}.")
        }
        val intel = drivingIntelligence.state.value
        if (intel.safetyScore < 75) {
            concerns.add("Driving Safety Score dropped to ${intel.safetyScore}/100.")
        }
        return concerns
    }

    private fun buildRecommendedChecks(snap: VehicleSnapshot, concerns: List<String>): List<String> {
        val checks = mutableListOf<String>()
        if (!snap.obdConnected) {
            checks.add("Connect the HHOBD Bluetooth adapter with ignition ON to unlock live ECU diagnostics.")
        }
        if (concerns.any { it.contains("Coolant", ignoreCase = true) }) {
            checks.add("Inspect coolant reservoir level, radiator fan operation, and check for leaks once cool.")
        }
        if (concerns.any { it.contains("voltage", ignoreCase = true) }) {
            checks.add("Test battery state-of-health and alternator output under load.")
        }
        if (snap.diagnostics.scanStatus == DtcScanStatus.SCANNED_FAULTS_FOUND) {
            checks.add("Have a qualified technician inspect the reported DTCs before clearing or ignoring.")
        }
        if (checks.isEmpty()) {
            checks.add("Verify tyre pressures, fluid levels, and lights visually before departing.")
        }
        return checks
    }

    private fun interpretDtc(code: String, isPending: Boolean, isPermanent: Boolean): DtcInterpretation {
        val upper = code.uppercase()
        val (desc, causes, checks) = DTC_DICTIONARY[upper] ?: when {
            upper.startsWith("P07") || upper.startsWith("P08") || upper.startsWith("P09") -> Triple(
                "Transmission / CVT control circuit code ($upper)",
                "Transmission fluid condition, speed sensor, or solenoid circuit issue",
                "Check CVT fluid condition/level and consult Nissan/transmission specialist",
            )
            upper.startsWith("P03") -> Triple(
                "Ignition / misfire related code ($upper)",
                "Spark plug wear, ignition coil, fuel injector, or vacuum leak",
                "Inspect spark plugs, ignition coils, and fuel trim readings",
            )
            upper.startsWith("P01") || upper.startsWith("P02") -> Triple(
                "Fuel or air metering code ($upper)",
                "MAF/MAP sensor contamination, O2 sensor aging, or intake leak",
                "Inspect air filter, intake hoses, and MAF sensor",
            )
            upper.startsWith("P04") -> Triple(
                "Emission / EVAP or catalytic system code ($upper)",
                "Loose fuel cap, EVAP purge valve, or catalytic converter efficiency",
                "Verify fuel cap seal and inspect EVAP hoses",
            )
            else -> Triple(
                "Standard OBD-II diagnostic code ($upper)",
                "Sensor circuit fault, wiring issue, or mechanical component wear",
                "Use freeze-frame data and workshop inspection to confirm root cause",
            )
        }
        return DtcInterpretation(
            code = upper,
            severityLabel = if (isPending) "Pending" else if (isPermanent) "Permanent" else "Stored",
            description = desc,
            possibleCauses = causes,
            suggestedChecks = checks,
            isPending = isPending,
            isPermanent = isPermanent,
        )
    }

    companion object {
        private val DTC_DICTIONARY = mapOf(
            "P0100" to Triple("Mass or Volume Air Flow Circuit", "Dirty/faulty MAF sensor or wiring", "Inspect MAF connector and clean sensor"),
            "P0115" to Triple("Engine Coolant Temperature Sensor 1 Circuit", "ECT sensor fault or open wiring", "Verify ECT sensor plug and coolant level"),
            "P0128" to Triple("Coolant Thermostat Below Regulating Temp", "Stuck-open thermostat or faulty ECT", "Check warm-up time and thermostat"),
            "P0171" to Triple("System Too Lean (Bank 1)", "Vacuum leak, weak fuel delivery, or dirty MAF", "Inspect intake hoses and fuel trims"),
            "P0172" to Triple("System Too Rich (Bank 1)", "Leaking injector, faulty O2/MAF, high fuel pressure", "Check fuel trims and air filter"),
            "P0300" to Triple("Random/Multiple Cylinder Misfire Detected", "Spark plugs, coils, fuel pressure, or vacuum leak", "Inspect ignition coils and spark plugs"),
            "P0420" to Triple("Catalyst System Efficiency Below Threshold", "Aging catalytic converter, exhaust leak, or rear O2", "Inspect exhaust leaks and O2 sensor"),
            "P0562" to Triple("System Voltage Low", "Weak battery, loose terminals, or alternator undercharging", "Test battery and alternator under load"),
        )
    }
}
