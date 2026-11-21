package ke.co.kopilot.feature.sensors.fusion

data class VerificationConfig(
    val minIndependentTerrainSources: Int = 2,
    val elevatedLoadThresholdPct: Float = 65f,
    val coolantWarningThresholdC: Int = 102,
)

/**
 * Evaluates independent corroborating evidence before allowing ordinary driving/terrain advisories,
 * while ensuring urgent directly measured engine/safety faults are never suppressed by missing secondary sensors.
 */
class EvidenceVerificationEngine(
    private val config: VerificationConfig = VerificationConfig(),
) {
    fun evaluateTerrainAndEngine(
        input: SensorFusionInput,
        terrain: TerrainEvidenceResult,
        freshnessSummary: String,
    ): List<FusionFinding> {
        val findings = mutableListOf<FusionFinding>()
        val ts = input.timestampMillis
        val mono = input.monotonicMillis

        val obdFresh = input.obdConnected &&
            input.obdTimestampMillis != null &&
            (ts - input.obdTimestampMillis) <= 5_000L

        val load = if (obdFresh) input.obdLoadPct else null
        val rpm = if (obdFresh) input.obdRpm else null
        val throttle = if (obdFresh) input.obdThrottlePct else null
        val speed = input.obdSpeedKmh ?: input.gpsSpeedKmh
        val coolant = if (obdFresh) input.obdCoolantTempC else null

        // 1. Urgent directly measured engine fault (never suppressed by missing secondary sensors)
        if (coolant != null && coolant >= config.coolantWarningThresholdC) {
            val values = mutableMapOf("Coolant" to "$coolant °C")
            load?.let { values["Engine Load"] = "${it.toInt()}%" }
            speed?.let { values["Speed"] = "${it.toInt()} km/h" }
            findings += FusionFinding(
                eventCategory = "ENGINE_TEMPERATURE_HIGH",
                timestampMillis = ts,
                monotonicMillis = mono,
                measuredValues = values,
                supportingSources = listOf(EvidenceSource.OBD_ENGINE_RESPONSE),
                conflictingSources = emptyList(),
                sensorFreshnessSummary = freshnessSummary,
                confidence = "High",
                explanation = "Directly measured ECU coolant temperature reached $coolant°C.",
                severity = FusionAlertSeverity.URGENT,
                recommendedAction = "Reduce load and pull over safely to allow the engine to cool.",
                suppressed = false,
            )
        }

        // 2. Terrain-coupled advisory verification (requires >= 2 independent terrain sources)
        val hasTwoIndependentTerrainSources =
            terrain.supportingSources.distinct().size >= config.minIndependentTerrainSources &&
                terrain.category != TerrainCategory.UNCERTAIN

        when (terrain.category) {
            TerrainCategory.STEEP_UPHILL -> {
                val values = buildMeasuredMap(terrain, speed, rpm, load, throttle)
                val sources = terrain.supportingSources.toMutableList()
                val engineElevated = load != null && load >= config.elevatedLoadThresholdPct
                if (engineElevated) sources += EvidenceSource.OBD_ENGINE_RESPONSE

                val explanation = when {
                    engineElevated && input.isCvtVehicle ->
                        "Steep uphill (${"%.1f".format(terrain.gradientPct)}%) confirmed by ${terrain.supportingSources.size} independent sensors; CVT RPM and engine load elevated as expected on climb."
                    engineElevated ->
                        "Steep uphill (${"%.1f".format(terrain.gradientPct)}%) confirmed with elevated engine load."
                    else ->
                        "Steep uphill (${"%.1f".format(terrain.gradientPct)}%) confirmed by ${terrain.supportingSources.size} independent sensors; engine load remains moderate."
                }
                findings += FusionFinding(
                    eventCategory = "TERRAIN_STEEP_UPHILL",
                    timestampMillis = ts,
                    monotonicMillis = mono,
                    measuredValues = values,
                    supportingSources = sources.distinct(),
                    conflictingSources = terrain.conflictingSources,
                    sensorFreshnessSummary = freshnessSummary,
                    confidence = terrain.confidence,
                    explanation = explanation,
                    severity = FusionAlertSeverity.ADVISORY,
                    recommendedAction = "Maintain steady progressive throttle on the climb.",
                    suppressed = !hasTwoIndependentTerrainSources,
                    suppressionReason = if (!hasTwoIndependentTerrainSources) "Insufficient independent terrain corroboration." else null,
                )
            }

            TerrainCategory.STEEP_DOWNHILL -> {
                val values = buildMeasuredMap(terrain, speed, rpm, load, throttle)
                val sources = terrain.supportingSources.toMutableList()
                if (throttle != null && throttle <= 12f) sources += EvidenceSource.OBD_ENGINE_RESPONSE
                findings += FusionFinding(
                    eventCategory = "TERRAIN_STEEP_DOWNHILL",
                    timestampMillis = ts,
                    monotonicMillis = mono,
                    measuredValues = values,
                    supportingSources = sources.distinct(),
                    conflictingSources = terrain.conflictingSources,
                    sensorFreshnessSummary = freshnessSummary,
                    confidence = terrain.confidence,
                    explanation = "Sustained steep downhill (${"%.1f".format(terrain.gradientPct)}%) confirmed by ${terrain.supportingSources.size} independent sensors.",
                    severity = FusionAlertSeverity.ADVISORY,
                    recommendedAction = "Descending a sustained slope. Maintain a safe speed.",
                    suppressed = !hasTwoIndependentTerrainSources,
                    suppressionReason = if (!hasTwoIndependentTerrainSources) "Insufficient independent terrain corroboration." else null,
                )
            }

            TerrainCategory.UNCERTAIN -> {
                // If OBD shows high load or sensors disagree, explicitly log why terrain advisory was suppressed
                if (terrain.conflictingSources.isNotEmpty() || (load != null && load >= config.elevatedLoadThresholdPct)) {
                    val reason = if (terrain.conflictingSources.isNotEmpty()) {
                        "Sensors disagreed (${terrain.conflictingSources.joinToString { it.label }} conflicting)."
                    } else {
                        "High engine load (${load?.toInt()}%) without >= 2 corroborating terrain sensors."
                    }
                    findings += FusionFinding(
                        eventCategory = "TERRAIN_ADVISORY_SUPPRESSED",
                        timestampMillis = ts,
                        monotonicMillis = mono,
                        measuredValues = buildMeasuredMap(terrain, speed, rpm, load, throttle),
                        supportingSources = terrain.supportingSources,
                        conflictingSources = terrain.conflictingSources,
                        sensorFreshnessSummary = freshnessSummary,
                        confidence = "Uncertain",
                        explanation = terrain.explanation,
                        severity = FusionAlertSeverity.INFO,
                        recommendedAction = "No terrain warning issued; waiting for corroborated evidence.",
                        suppressed = true,
                        suppressionReason = reason,
                    )
                }
            }

            TerrainCategory.FLAT -> {
                // High load on flat road -> record contextual finding, never misclassify as uphill
                if (load != null && load >= 75f && (speed ?: 0f) >= 20f) {
                    findings += FusionFinding(
                        eventCategory = "FLAT_HIGH_LOAD",
                        timestampMillis = ts,
                        monotonicMillis = mono,
                        measuredValues = buildMeasuredMap(terrain, speed, rpm, load, throttle),
                        supportingSources = (terrain.supportingSources + EvidenceSource.OBD_ENGINE_RESPONSE).distinct(),
                        conflictingSources = terrain.conflictingSources,
                        sensorFreshnessSummary = freshnessSummary,
                        confidence = terrain.confidence,
                        explanation = "High engine load (${load.toInt()}%) observed on flat terrain (acceleration or payload demand), not an uphill grade.",
                        severity = FusionAlertSeverity.INFO,
                        recommendedAction = "Smooth throttle modulation improves fuel economy on level roads.",
                        suppressed = false,
                    )
                }
            }

            TerrainCategory.MILD_UPHILL, TerrainCategory.MILD_DOWNHILL -> Unit
        }

        return findings
    }

    private fun buildMeasuredMap(
        terrain: TerrainEvidenceResult,
        speed: Float?,
        rpm: Int?,
        load: Float?,
        throttle: Float?,
    ): Map<String, String> = buildMap {
        terrain.gradientPct?.let { put("Gradient", "${"%.1f".format(it)}%") }
        speed?.let { put("Speed", "${it.toInt()} km/h") }
        rpm?.let { put("RPM", "$it rpm") }
        load?.let { put("Engine Load", "${it.toInt()}%") }
        throttle?.let { put("Throttle", "${it.toInt()}%") }
    }
}
