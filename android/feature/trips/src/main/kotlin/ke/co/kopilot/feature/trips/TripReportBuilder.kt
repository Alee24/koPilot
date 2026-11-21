package ke.co.kopilot.feature.trips

import com.google.android.gms.maps.model.LatLng
import ke.co.kopilot.core.database.entity.TripEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reconstructs a complete, honest TripIntelligenceReport from Room TripEntity + per-trip .txt log.
 * Never fabricates missing GPS, OBD, HR, Altitude, or Weather readings.
 */
object TripReportBuilder {
    private val trackRegex = Regex("""\[GPS_TRACK\]\s+([-+]?\d+\.\d+),([-+]?\d+\.\d+)""")
    private val gpsRegex = Regex("""GPS=([-+]?\d+\.\d+),([-+]?\d+\.\d+)""")
    private val timePrefixRegex = Regex("""^\[(\d{2}:\d{2}:\d{2})\]\s+\[([A-Z0-9_]+)\]\s+(.*)$""")

    fun build(
        trip: TripEntity,
        logFile: File?,
        vehicleName: String = "Nissan Tiida 2012 (1.5L CVT)",
    ): TripIntelligenceReport {
        val lines = if (logFile != null && logFile.exists()) {
            runCatching { logFile.readLines() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val routePoints = mutableListOf<LatLng>()
        val samples = mutableListOf<TripReportSample>()
        val events = mutableListOf<TripTimelineEvent>()

        var lastLat: Double? = null
        var lastLng: Double? = null
        var lastGpsSpeed: Float? = null
        var lastObdSpeed: Float? = null
        var lastRpm: Int? = null
        var lastCoolant: Int? = null
        var lastMaf: Float? = null
        var lastVolt: Float? = null
        var lastHr: Int? = null
        var lastWeatherSummary: String? = null
        var lastWeatherTemp: String? = null
        var lastWeatherSource: String? = null
        var batteryStart: Int? = null
        var batteryEnd: Int? = null
        var batteryTempC: Float? = null
        var roadQualityLine: String? = null
        var drivingBehaviourLine: String? = null
        var tripSummaryLine: String? = null
        var accidentCandidates = 0
        var dismissedRoadEvents = 0
        var canceledCount = 0
        var obdDisconnects = 0
        var watchDisconnects = 0
        var safetyMonitored = false

        val dayBaseFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayPrefix = dayBaseFmt.format(Date(trip.startedAtMillis))
        val fullTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        for ((idx, raw) in lines.withIndex()) {
            val tm = trackRegex.find(raw)
            if (tm != null) {
                val lat = tm.groupValues[1].toDoubleOrNull()
                val lng = tm.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null) {
                    val pt = LatLng(lat, lng)
                    routePoints.add(pt)
                    lastLat = lat
                    lastLng = lng
                }
            } else {
                val gm = gpsRegex.find(raw)
                if (gm != null) {
                    val lat = gm.groupValues[1].toDoubleOrNull()
                    val lng = gm.groupValues[2].toDoubleOrNull()
                    if (lat != null && lng != null) {
                        routePoints.add(LatLng(lat, lng))
                        lastLat = lat
                        lastLng = lng
                    }
                }
            }

            val parsed = timePrefixRegex.find(raw) ?: continue
            val timeStr = parsed.groupValues[1]
            val type = parsed.groupValues[2]
            val details = parsed.groupValues[3]
            val tsMillis = runCatching { fullTimeFmt.parse("$dayPrefix $timeStr")?.time }.getOrNull()
                ?: (trip.startedAtMillis + idx * 1000L)

            if (type == "SENSORS_ACTIVE" && details.contains("Motion=true")) {
                safetyMonitored = true
            }

            when (type) {
                "GPS_TRACK" -> {
                    val spd = extractFloat(details, "spd=")
                    val alt = extractDouble(details, "alt=")
                    val acc = extractFloat(details, "acc=")
                    val brg = extractFloat(details, "brg=")
                    if (spd != null) lastGpsSpeed = spd
                    samples.add(
                        TripReportSample(
                            timestampMillis = tsMillis,
                            latitude = lastLat,
                            longitude = lastLng,
                            altitudeMeters = alt,
                            gpsAccuracyMeters = acc,
                            gpsSpeedKmh = spd ?: lastGpsSpeed,
                            bearingDeg = brg,
                            obdSpeedKmh = lastObdSpeed,
                            engineRpm = lastRpm,
                            coolantTempC = lastCoolant,
                            mafGramsPerSec = lastMaf,
                            voltageV = lastVolt,
                            heartRateBpm = lastHr,
                        ),
                    )
                }
                "OBD_SAMPLE" -> {
                    lastRpm = extractInt(details, "RPM=") ?: lastRpm
                    lastObdSpeed = extractFloat(details, "SPD=") ?: lastObdSpeed
                    lastCoolant = extractInt(details, "CLT=") ?: lastCoolant
                    lastMaf = extractFloat(details, "MAF=") ?: lastMaf
                    lastVolt = extractFloat(details, "VOLT=") ?: lastVolt
                    val load = extractFloat(details, "LOAD=")
                    val thr = extractFloat(details, "THR=")
                    val iat = extractInt(details, "IAT=")
                    samples.add(
                        TripReportSample(
                            timestampMillis = tsMillis,
                            latitude = lastLat,
                            longitude = lastLng,
                            gpsSpeedKmh = lastGpsSpeed,
                            obdSpeedKmh = lastObdSpeed,
                            engineRpm = lastRpm,
                            coolantTempC = lastCoolant,
                            engineLoadPct = load,
                            throttlePct = thr,
                            mafGramsPerSec = lastMaf,
                            intakeTempC = iat,
                            voltageV = lastVolt,
                            heartRateBpm = lastHr,
                        ),
                    )
                }
                "HR_SAMPLE" -> {
                    lastHr = extractInt(details, "BPM=") ?: lastHr
                    samples.add(
                        TripReportSample(
                            timestampMillis = tsMillis,
                            latitude = lastLat,
                            longitude = lastLng,
                            gpsSpeedKmh = lastGpsSpeed,
                            obdSpeedKmh = lastObdSpeed,
                            engineRpm = lastRpm,
                            heartRateBpm = lastHr,
                        ),
                    )
                }
                "WEATHER" -> {
                    lastWeatherSummary = details.substringAfter("Cond=", "").substringBefore("|").trim().ifBlank { null }
                    lastWeatherTemp = details.substringAfter("Temp=", "").substringBefore("|").trim().ifBlank { null }
                    lastWeatherSource = details.substringAfter("Source=", "").trim().ifBlank { null }
                }
                "DEVICE_PERF" -> {
                    val b = extractInt(details, "Bat=")
                    if (b != null) {
                        if (batteryStart == null) batteryStart = b
                        batteryEnd = b
                    }
                    batteryTempC = extractFloat(details, "Temp=") ?: batteryTempC
                }
                "ROAD_QUALITY" -> roadQualityLine = details
                "DRIVING_BEHAVIOUR" -> drivingBehaviourLine = details
                "TRIP_SUMMARY" -> tripSummaryLine = details
                "SIGN_DETECTED" -> {
                    events.add(
                        TripTimelineEvent(
                            id = "ev_$idx",
                            timestampMillis = tsMillis,
                            category = TimelineEventCategory.ROAD_INTELLIGENCE,
                            title = "Road Sign Detected",
                            description = details,
                            severity = "Info",
                            latitude = lastLat,
                            longitude = lastLng,
                        ),
                    )
                }
                "DRIVING_EVENT" -> {
                    events.add(
                        TripTimelineEvent(
                            id = "ev_$idx",
                            timestampMillis = tsMillis,
                            category = TimelineEventCategory.DRIVING_BEHAVIOR,
                            title = details.substringBefore(":").trim(),
                            description = details,
                            severity = if (details.contains("Harsh", true) || details.contains("Hard", true)) "Caution" else "Info",
                            latitude = lastLat,
                            longitude = lastLng,
                        ),
                    )
                }
                "SAFETY_EVENT" -> {
                    safetyMonitored = true
                    accidentCandidates++
                    events.add(
                        TripTimelineEvent(
                            id = "ev_$idx",
                            timestampMillis = tsMillis,
                            category = TimelineEventCategory.SAFETY_INCIDENT,
                            title = "Safety Incident",
                            description = details,
                            severity = "Critical",
                            latitude = lastLat,
                            longitude = lastLng,
                        ),
                    )
                }
                "ACCIDENT_CANCELLED" -> {
                    canceledCount++
                    events.add(
                        TripTimelineEvent(
                            id = "ev_$idx",
                            timestampMillis = tsMillis,
                            category = TimelineEventCategory.SAFETY_INCIDENT,
                            title = "Emergency Alert Canceled",
                            description = details,
                            severity = "Caution",
                            latitude = lastLat,
                            longitude = lastLng,
                        ),
                    )
                }
                "ROAD_DISMISSED" -> {
                    safetyMonitored = true
                    dismissedRoadEvents++
                    events.add(
                        TripTimelineEvent(
                            id = "ev_$idx",
                            timestampMillis = tsMillis,
                            category = TimelineEventCategory.ROAD_INTELLIGENCE,
                            title = "Dismissed Road Bump / Rough Road",
                            description = details,
                            severity = "Info",
                            latitude = lastLat,
                            longitude = lastLng,
                        ),
                    )
                }
                "ECU_ALERT" -> {
                    if (details.contains("disconnect", true)) obdDisconnects++
                    events.add(
                        TripTimelineEvent(
                            id = "ev_$idx",
                            timestampMillis = tsMillis,
                            category = TimelineEventCategory.VEHICLE_DIAGNOSTICS,
                            title = "Vehicle Diagnostic Alert",
                            description = details,
                            severity = "Caution",
                            latitude = lastLat,
                            longitude = lastLng,
                        ),
                    )
                }
            }
        }

        val statusLabel = when {
            trip.endedAtMillis == null -> "In Progress"
            trip.durationSeconds < 10L && trip.distanceKm < 0.05 -> "Interrupted"
            else -> "Completed"
        }

        val accidentBannerTitle = when {
            accidentCandidates > 0 -> "$accidentCandidates Suspected Accident Event(s) Recorded"
            safetyMonitored || lines.isNotEmpty() -> "No suspected accidents recorded during monitored periods"
            else -> "Accident monitoring coverage unavailable"
        }
        val accidentBannerDetail = when {
            accidentCandidates > 0 -> "Canceled by driver: $canceledCount • Dismissed road bumps: $dismissedRoadEvents"
            safetyMonitored -> "5-second post-impact verification active • Dismissed road events: $dismissedRoadEvents"
            else -> "Detailed motion log was not recorded for this trip."
        }

        // Moving vs Idle estimation from samples or trip totals
        val movingSamples = samples.count { (it.gpsSpeedKmh ?: it.obdSpeedKmh ?: 0f) >= 3f }
        val idleSamples = samples.count { (it.gpsSpeedKmh ?: it.obdSpeedKmh ?: 0f) < 3f }
        val totalSamples = movingSamples + idleSamples
        val movingSec = if (totalSamples > 0) (trip.durationSeconds * movingSamples) / totalSamples else trip.durationSeconds
        val idleSec = (trip.durationSeconds - movingSec).coerceAtLeast(0L)
        val avgTripSpeed = if (trip.durationSeconds > 0L) ((trip.distanceKm / (trip.durationSeconds / 3600.0))).toFloat() else null
        val movingSpeeds = samples.mapNotNull { it.gpsSpeedKmh ?: it.obdSpeedKmh }.filter { it >= 3f }
        val avgMovingSpeed = if (movingSpeeds.isNotEmpty()) movingSpeeds.average().toFloat() else if (trip.avgSpeedKmh > 0f) trip.avgSpeedKmh else null

        val alts = samples.mapNotNull { it.altitudeMeters }
        var gain = 0.0
        var loss = 0.0
        if (alts.size >= 2) {
            for (i in 1 until alts.size) {
                val d = alts[i] - alts[i - 1]
                if (d > 2.0) gain += d else if (d < -2.0) loss += -d
            }
        }

        // OBD Metrics (never substitute 0 for unavailable)
        val rpms = samples.mapNotNull { it.engineRpm }
        val coolants = samples.mapNotNull { it.coolantTempC }
        val mafs = samples.mapNotNull { it.mafGramsPerSec }
        val volts = samples.mapNotNull { it.voltageV }
        val loads = samples.mapNotNull { it.engineLoadPct }
        val throttles = samples.mapNotNull { it.throttlePct }
        val iats = samples.mapNotNull { it.intakeTempC }
        val fuelFromSummary = tripSummaryLine?.substringAfter("Fuel=", "")?.substringBefore("|")?.trim()?.takeIf { it.isNotBlank() }
        val voltFromSummary = tripSummaryLine?.substringAfter("Battery=", "")?.substringBefore("|")?.trim()?.takeIf { it.isNotBlank() }

        val obdMetrics = listOf(
            MeasuredMetric(
                "Engine RPM (Min / Avg / Max)",
                if (rpms.isNotEmpty()) "${rpms.min()} / ${rpms.average().roundToInt()} / ${rpms.max()}" else null,
                if (rpms.isNotEmpty()) "${rpms.min()} / ${rpms.average().roundToInt()} / ${rpms.max()} RPM" else "Unavailable",
                "RPM",
                "OBD-II PID 010C",
                if (rpms.isNotEmpty()) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Coolant Temperature",
                coolants.lastOrNull()?.toString(),
                coolants.lastOrNull()?.let { "$it °C (Max ${coolants.max()} °C)" } ?: "Unavailable",
                "°C",
                "OBD-II PID 0105",
                if (coolants.isNotEmpty()) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Mass Airflow (MAF)",
                mafs.lastOrNull()?.let { "%.2f".format(it) },
                if (mafs.isNotEmpty()) "Avg ${"%.2f".format(mafs.average())} g/s" else "Unavailable",
                "g/s",
                "OBD-II PID 0110",
                if (mafs.isNotEmpty()) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Control Module / Battery Voltage",
                voltFromSummary ?: volts.lastOrNull()?.let { "%.1fV".format(it) },
                voltFromSummary ?: volts.lastOrNull()?.let { "${"%.1f".format(it)} V" } ?: "Unavailable",
                "V",
                "OBD-II ATRV / PID 0142",
                if (voltFromSummary != null || volts.isNotEmpty()) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Engine Load / Throttle",
                loads.lastOrNull()?.let { "%.0f%%".format(it) },
                if (loads.isNotEmpty() || throttles.isNotEmpty()) {
                    "Load ${loads.averageOrNull()?.let { "%.0f%%".format(it) } ?: "--"} • Throttle ${throttles.averageOrNull()?.let { "%.0f%%".format(it) } ?: "--"}"
                } else "Unavailable",
                "%",
                "OBD-II PID 0104 / 0111",
                if (loads.isNotEmpty() || throttles.isNotEmpty()) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Intake Air Temperature",
                iats.lastOrNull()?.toString(),
                iats.lastOrNull()?.let { "$it °C" } ?: "Unavailable",
                "°C",
                "OBD-II PID 010F",
                if (iats.isNotEmpty()) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Fuel Consumption (MAF-Derived)",
                fuelFromSummary,
                fuelFromSummary ?: "Unavailable (insufficient MAF data)",
                "L",
                "Calculated from MAF + Distance",
                if (fuelFromSummary != null && fuelFromSummary != "OBD Offline") MeasurementProvenance.CALCULATED_FROM_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
        )

        val motionMetrics = listOf(
            MeasuredMetric(
                "Road Quality Summary",
                roadQualityLine,
                roadQualityLine ?: "Unavailable",
                "",
                "Phone Accelerometer + Gyroscope",
                if (roadQualityLine != null) MeasurementProvenance.CALCULATED_FROM_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Driving Behaviour Summary",
                drivingBehaviourLine,
                drivingBehaviourLine ?: "Unavailable",
                "",
                "Phone IMU Fusion",
                if (drivingBehaviourLine != null) MeasurementProvenance.CALCULATED_FROM_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Dismissed Road Bumps / Potholes",
                dismissedRoadEvents.toString(),
                "$dismissedRoadEvents event(s)",
                "events",
                "5s Verification + RoughRoadClassifier",
                MeasurementProvenance.CALCULATED_FROM_MEASURED,
            ),
        )

        val safetyMetrics = listOf(
            MeasuredMetric(
                "Suspected Accident Candidates",
                accidentCandidates.toString(),
                "$accidentCandidates",
                "events",
                "AccidentDetectionEngine",
                if (safetyMonitored) MeasurementProvenance.CALCULATED_FROM_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Driver Canceled Alerts",
                canceledCount.toString(),
                "$canceledCount",
                "events",
                "EmergencyCountdown",
                MeasurementProvenance.DIRECTLY_MEASURED,
            ),
        )

        val wellnessMetrics = listOf(
            MeasuredMetric(
                "Heart Rate (Min / Avg / Max)",
                trip.avgHeartRateBpm?.toString(),
                if (trip.avgHeartRateBpm != null) "${trip.minHeartRateBpm ?: "--"} / ${trip.avgHeartRateBpm} / ${trip.maxHeartRateBpm ?: "--"} BPM" else "Unavailable",
                "BPM",
                "BLE Smartwatch (${trip.heartRateSamplesCount} samples)",
                if (trip.heartRateSamplesCount > 0) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Pre-Drive Sleep Duration",
                trip.preDriveSleepMinutes?.toString(),
                trip.preDriveSleepMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "Unavailable (not provided by standard BLE HR)",
                "min",
                "Authorized Wellness Source",
                if (trip.preDriveSleepMinutes != null) MeasurementProvenance.PROVIDER_REPORTED else MeasurementProvenance.UNAVAILABLE,
            ),
        )

        val cameraMetrics = listOf(
            MeasuredMetric(
                "Traffic Signs Recognized",
                trip.signsDetectedCount.toString(),
                "${trip.signsDetectedCount} sign(s)",
                "signs",
                "On-Device Camera Vision",
                MeasurementProvenance.DIRECTLY_MEASURED,
            ),
            MeasuredMetric(
                "Road Alerts Triggered",
                trip.alertsTriggeredCount.toString(),
                "${trip.alertsTriggeredCount} alert(s)",
                "alerts",
                "LocalAlertCoordinator",
                MeasurementProvenance.DIRECTLY_MEASURED,
            ),
        )

        val weatherMetrics = listOf(
            MeasuredMetric(
                "Weather Condition",
                lastWeatherSummary,
                if (lastWeatherSummary != null) "$lastWeatherSummary ${lastWeatherTemp ?: ""}".trim() else "Unavailable",
                "",
                lastWeatherSource ?: "Weather Provider / Cache",
                if (lastWeatherSummary != null) MeasurementProvenance.PROVIDER_REPORTED else MeasurementProvenance.UNAVAILABLE,
            ),
        )

        val performanceMetrics = listOf(
            MeasuredMetric(
                "Phone Battery (Start → End)",
                batteryEnd?.toString(),
                if (batteryStart != null && batteryEnd != null) "$batteryStart% → $batteryEnd%" else "Unavailable",
                "%",
                "Android BatteryManager",
                if (batteryStart != null) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "Battery Temperature",
                batteryTempC?.toString(),
                batteryTempC?.let { "${"%.1f".format(it)} °C" } ?: "Unavailable",
                "°C",
                "Android BatteryManager",
                if (batteryTempC != null) MeasurementProvenance.DIRECTLY_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            MeasuredMetric(
                "OBD / Watch Disconnect Gaps",
                "$obdDisconnects / $watchDisconnects",
                "OBD: $obdDisconnects • Watch: $watchDisconnects",
                "gaps",
                "Connectivity Monitors",
                MeasurementProvenance.DIRECTLY_MEASURED,
            ),
        )

        val scoreBreakdown = computeScoreBreakdown(
            roadQualityLine = roadQualityLine,
            drivingBehaviourLine = drivingBehaviourLine,
            accidentCandidates = accidentCandidates,
            safetyMonitored = safetyMonitored,
        )

        val coverage = listOf(
            "GPS Track" to if (routePoints.isNotEmpty()) "${routePoints.size} pts" else "Unavailable",
            "OBD-II" to if (rpms.isNotEmpty() || fuelFromSummary != null) "Active" else "Offline",
            "Phone IMU" to if (roadQualityLine != null || drivingBehaviourLine != null) "Active" else "Unavailable",
            "Smartwatch HR" to if (trip.heartRateSamplesCount > 0) "${trip.heartRateSamplesCount} samples" else "Offline",
            "Camera AI" to if (trip.signsDetectedCount > 0) "${trip.signsDetectedCount} signs" else "Limited/Standby",
        )

        val availableCount = coverage.count { it.second != "Unavailable" && it.second != "Offline" }
        val completenessPct = ((availableCount.toFloat() / coverage.size.toFloat()) * 100f).roundToInt()

        return TripIntelligenceReport(
            tripId = trip.id,
            statusLabel = statusLabel,
            accidentBannerTitle = accidentBannerTitle,
            accidentBannerDetail = accidentBannerDetail,
            warnings = buildList {
                if (routePoints.isEmpty()) add("GPS route coordinates were not captured for this trip.")
                if (rpms.isEmpty() && fuelFromSummary == null) add("OBD-II telemetry unavailable during this trip.")
                if (accidentCandidates > 0) add("$accidentCandidates suspected safety event(s) logged.")
            },
            sensorCoverageSummary = coverage,
            originLabel = trip.originLabel?.takeIf { it.isNotBlank() } ?: "Current Location",
            destinationLabel = trip.destinationLabel?.takeIf { it.isNotBlank() } ?: "Local Drive",
            startedAtMillis = trip.startedAtMillis,
            endedAtMillis = trip.endedAtMillis,
            durationSeconds = trip.durationSeconds,
            vehicleName = vehicleName,
            distanceKm = trip.distanceKm,
            movingTimeSeconds = movingSec,
            idleTimeSeconds = idleSec,
            stopCount = 0,
            avgMovingSpeedKmh = avgMovingSpeed,
            avgTripSpeedKmh = avgTripSpeed,
            maxSpeedKmh = if (trip.maxSpeedKmh > 0f) trip.maxSpeedKmh else null,
            gpsQualityLabel = if (routePoints.size >= 5) "Good (${routePoints.size} fixes)" else if (routePoints.isNotEmpty()) "Limited (${routePoints.size} fixes)" else "Unavailable",
            routePoints = routePoints,
            elevationGainMeters = if (alts.size >= 2) gain else null,
            elevationLossMeters = if (alts.size >= 2) loss else null,
            scoreBreakdown = scoreBreakdown,
            obdMetrics = obdMetrics,
            motionMetrics = motionMetrics,
            safetyMetrics = safetyMetrics,
            wellnessMetrics = wellnessMetrics,
            cameraMetrics = cameraMetrics,
            weatherMetrics = weatherMetrics,
            performanceMetrics = performanceMetrics,
            samples = samples,
            events = events.sortedBy { it.timestampMillis },
            mediaItems = events.filter { it.category == TimelineEventCategory.ROAD_INTELLIGENCE || it.mediaPath != null },
            dataCompletenessPercent = completenessPct,
        )
    }

    private fun computeScoreBreakdown(
        roadQualityLine: String?,
        drivingBehaviourLine: String?,
        accidentCandidates: Int,
        safetyMonitored: Boolean,
    ): TripScoreBreakdown {
        val driverScore = drivingBehaviourLine?.substringAfter("Driver Score=", "")?.substringBefore("/")?.trim()?.toIntOrNull()
        val roadScore = roadQualityLine?.substringAfter("Avg Score=", "")?.substringBefore("/")?.trim()?.toIntOrNull()
        val safetyScore = if (safetyMonitored) {
            if (accidentCandidates == 0) 100 else (100 - accidentCandidates * 25).coerceAtLeast(20)
        } else null
        val components = listOf(
            ScoreComponent(
                name = "Driving Behaviour (Braking, Accel, Cornering)",
                score = driverScore,
                weightPercent = 50,
                explanation = if (driverScore != null) "Calculated from recorded harsh braking, rapid acceleration, and sharp cornering events." else "Unavailable (motion telemetry not recorded)",
                provenance = if (driverScore != null) MeasurementProvenance.CALCULATED_FROM_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            ScoreComponent(
                name = "Road Surface & Smoothness",
                score = roadScore,
                weightPercent = 25,
                explanation = if (roadScore != null) "Calculated from vertical vibration RMS and pothole/bump frequency." else "Unavailable (road vibration not recorded)",
                provenance = if (roadScore != null) MeasurementProvenance.CALCULATED_FROM_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
            ScoreComponent(
                name = "Safety Event Frequency",
                score = safetyScore,
                weightPercent = 25,
                explanation = if (safetyScore != null) "Based on $accidentCandidates suspected safety incident(s); not a certified safety rating." else "Unavailable (safety monitoring log not recorded)",
                provenance = if (safetyScore != null) MeasurementProvenance.CALCULATED_FROM_MEASURED else MeasurementProvenance.UNAVAILABLE,
            ),
        )
        val available = components.filter { it.score != null }
        val overall = if (available.isNotEmpty()) {
            val totalWeight = available.sumOf { it.weightPercent }.coerceAtLeast(1)
            available.sumOf { (it.score!! * it.weightPercent) } / totalWeight
        } else null
        return TripScoreBreakdown(
            overallScore = overall,
            summaryNote = if (overall != null) {
                "Calculated from ${available.size}/${components.size} available components (missing sensors are excluded, not treated as 100%)."
            } else {
                "Insufficient telemetry to calculate a driving score."
            },
            components = components,
        )
    }

    private fun extractFloat(text: String, prefix: String): Float? =
        text.substringAfter(prefix, "").substringBefore(" ").substringBefore("|").substringBefore(",").trim().toFloatOrNull()

    private fun extractDouble(text: String, prefix: String): Double? =
        text.substringAfter(prefix, "").substringBefore(" ").substringBefore("|").substringBefore(",").trim().toDoubleOrNull()

    private fun extractInt(text: String, prefix: String): Int? =
        text.substringAfter(prefix, "").substringBefore(" ").substringBefore("|").substringBefore(",").trim().toIntOrNull()

    private fun List<Float>.averageOrNull(): Float? = if (isEmpty()) null else average().toFloat()
}
