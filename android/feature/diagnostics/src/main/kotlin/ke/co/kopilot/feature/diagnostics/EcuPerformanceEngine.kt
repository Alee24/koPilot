package ke.co.kopilot.feature.diagnostics

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class EcuSample(
    val monotonicMillis: Long,
    val timestampMillis: Long,
    val rpm: Int? = null,
    val obdSpeedKmh: Float? = null,
    val gpsSpeedKmh: Float? = null,
    val coolantTempC: Int? = null,
    val engineLoadPct: Float? = null,
    val throttlePct: Float? = null,
    val mafGramsPerSec: Float? = null,
    val mapKpa: Int? = null,
    val intakeAirTempC: Int? = null,
    val fuelLevelPct: Float? = null,
    val ecuFuelRateLph: Float? = null,
    val stftPct: Float? = null,
    val ltftPct: Float? = null,
    val o2VoltageV: Float? = null,
    val voltageV: Float? = null,
    val altitudeMeters: Double? = null,
    val gradientPct: Float? = null,
    val accelLongitudinalMps2: Float? = null,
) {
    val bestSpeedKmh: Float? get() = obdSpeedKmh ?: gpsSpeedKmh
    val isIdling: Boolean get() = (bestSpeedKmh ?: 0f) < 3f && (rpm ?: 0) >= 350
    val isAccelerating: Boolean get() = (accelLongitudinalMps2 ?: 0f) >= 1.2f || (throttlePct ?: 0f) >= 45f
    val isSteadyCruise: Boolean get() = (bestSpeedKmh ?: 0f) >= 20f && !isAccelerating && abs(accelLongitudinalMps2 ?: 0f) < 0.8f
}

data class SpeedEfficiencyBucket(
    val label: String,
    val minKmh: Int,
    val maxKmh: Int,
    val observationSeconds: Long,
    val avgLitersPer100Km: Double?,
    val avgKmPerLiter: Double?,
    val avgRpm: Int?,
    val avgLoadPct: Float?,
    val avgThrottlePct: Float?,
    val avgGradientPct: Float?,
    val sampleCount: Int,
    val confidence: String,
)

data class EcuInsight(
    val title: String,
    val evidence: String,
    val affectedTrips: String,
    val confidence: String,
    val recommendedAction: String,
    val inspectionAppropriate: Boolean = false,
)

data class EcuVehicleBaseline(
    val tripCount: Int = 0,
    val avgRpm: Int? = null,
    val avgLoadPct: Float? = null,
    val avgCoolantC: Int? = null,
    val avgLitersPer100Km: Double? = null,
    val avgLtftPct: Float? = null,
)

data class EcuPerformanceAnalysis(
    val sampleCount: Int,
    val durationSeconds: Long,
    val distanceKm: Double,
    val fuelProvenance: String,
    val avgFuelRateLph: Double?,
    val totalFuelUsedLiters: Double?,
    val litersPer100Km: Double?,
    val kmPerLiter: Double?,
    val idleFuelLiters: Double?,
    val accelFuelLiters: Double?,
    val cruiseFuelLiters: Double?,
    val speedBuckets: List<SpeedEfficiencyBucket>,
    val recommendedCruisingRange: String?,
    val minRpm: Int?,
    val avgRpm: Int?,
    val maxRpm: Int?,
    val avgLoadPct: Float?,
    val avgThrottlePct: Float?,
    val idleRpmStdDev: Float?,
    val minCoolantC: Int?,
    val avgCoolantC: Int?,
    val maxCoolantC: Int?,
    val warmupSecondsTo75C: Long?,
    val excursionCountAbove102C: Int,
    val avgMafGps: Float?,
    val avgMapKpa: Int?,
    val avgStftPct: Float?,
    val avgLtftPct: Float?,
    val uphillSampleCount: Int,
    val uphillAvgRpm: Int?,
    val uphillAvgLoadPct: Float?,
    val performanceScore: Int?,
    val insights: List<EcuInsight>,
)

/**
 * Strictly read-only, offline ECU performance & efficiency analytics engine.
 * Never fabricates unsupported PIDs, never computes L/100km while stationary,
 * and never assumes fixed gears on CVT-equipped vehicles.
 */
class EcuPerformanceEngine {

    fun analyze(
        samples: List<EcuSample>,
        distanceKm: Double,
        baseline: EcuVehicleBaseline = EcuVehicleBaseline(),
        isCvtVehicle: Boolean = true,
        speedLimitCapKmh: Int = 100,
    ): EcuPerformanceAnalysis {
        if (samples.isEmpty()) {
            return emptyAnalysis()
        }

        val sorted = samples.sortedBy { it.monotonicMillis }
        val durationSec = ((sorted.last().monotonicMillis - sorted.first().monotonicMillis) / 1000L).coerceAtLeast(0L)

        var ecuFuelCount = 0
        var mafFuelCount = 0
        var totalFuelL = 0.0
        var idleFuelL = 0.0
        var accelFuelL = 0.0
        var cruiseFuelL = 0.0
        var fuelRateSum = 0.0
        var fuelRateCount = 0

        for (i in sorted.indices) {
            val s = sorted[i]
            val dtHours = if (i > 0) {
                ((s.monotonicMillis - sorted[i - 1].monotonicMillis).coerceIn(0L, 10_000L)) / 3_600_000.0
            } else 0.0

            val lph: Double? = when {
                s.ecuFuelRateLph != null && s.ecuFuelRateLph >= 0f -> {
                    ecuFuelCount++
                    s.ecuFuelRateLph.toDouble()
                }
                s.mafGramsPerSec != null && s.mafGramsPerSec > 0f -> {
                    mafFuelCount++
                    // Stoichiometric gasoline 14.7:1, fuel density ~745 g/L
                    (s.mafGramsPerSec * 3600.0) / (14.7 * 745.0)
                }
                else -> null
            }

            if (lph != null) {
                fuelRateSum += lph
                fuelRateCount++
                val dFuel = lph * dtHours
                totalFuelL += dFuel
                when {
                    s.isIdling -> idleFuelL += dFuel
                    s.isAccelerating -> accelFuelL += dFuel
                    s.isSteadyCruise -> cruiseFuelL += dFuel
                }
            }
        }

        val fuelProvenance = when {
            ecuFuelCount > 0 && ecuFuelCount >= mafFuelCount -> "ECU Fuel Rate (PID 015E)"
            mafFuelCount > 0 -> "MAF-Based Estimate (14.7:1 AFR)"
            else -> "Unavailable"
        }

        val hasFuel = fuelRateCount > 0 && totalFuelL > 0.0
        val movingDistanceValid = distanceKm >= 0.25 && sorted.any { (it.bestSpeedKmh ?: 0f) >= 5f }
        val lPer100 = if (hasFuel && movingDistanceValid) (totalFuelL / distanceKm) * 100.0 else null
        val kmPerL = if (hasFuel && movingDistanceValid) distanceKm / totalFuelL else null

        val buckets = computeSpeedBuckets(sorted)
        val recommendedRange = buckets
            .filter { it.sampleCount >= 8 && it.avgLitersPer100Km != null && it.maxKmh <= speedLimitCapKmh }
            .minByOrNull { it.avgLitersPer100Km!! }
            ?.label

        val rpms = sorted.mapNotNull { it.rpm }
        val idleRpms = sorted.filter { it.isIdling }.mapNotNull { it.rpm }
        val idleStd = if (idleRpms.size >= 3) {
            val mean = idleRpms.average()
            sqrt(idleRpms.sumOf { (it - mean).pow(2) } / idleRpms.size).toFloat()
        } else null

        val loads = sorted.mapNotNull { it.engineLoadPct }
        val throttles = sorted.mapNotNull { it.throttlePct }
        val coolants = sorted.mapNotNull { it.coolantTempC }
        val firstTs = sorted.first().monotonicMillis
        val firstWarm = sorted.firstOrNull { (it.coolantTempC ?: 0) >= 75 }
        val warmupSec = if (coolants.isNotEmpty() && (coolants.first() < 75) && firstWarm != null) {
            (firstWarm.monotonicMillis - firstTs) / 1000L
        } else null
        val excursions = coolants.count { it >= 102 }

        val mafs = sorted.mapNotNull { it.mafGramsPerSec }
        val maps = sorted.mapNotNull { it.mapKpa }
        val stfts = sorted.mapNotNull { it.stftPct }
        val ltfts = sorted.mapNotNull { it.ltftPct }

        val uphill = sorted.filter { (it.gradientPct ?: 0f) >= 3.0f }
        val uphillRpms = uphill.mapNotNull { it.rpm }
        val uphillLoads = uphill.mapNotNull { it.engineLoadPct }

        val avgRpm = if (rpms.isNotEmpty()) rpms.average().roundToInt() else null
        val avgLoad = if (loads.isNotEmpty()) loads.average().toFloat() else null
        val avgCoolant = if (coolants.isNotEmpty()) coolants.average().roundToInt() else null
        val avgStft = if (stfts.isNotEmpty()) stfts.average().toFloat() else null
        val avgLtft = if (ltfts.isNotEmpty()) ltfts.average().toFloat() else null

        val insights = buildInsights(
            samples = sorted,
            durationSec = durationSec,
            lPer100 = lPer100,
            idleFuelL = if (hasFuel) idleFuelL else null,
            totalFuelL = if (hasFuel) totalFuelL else null,
            recommendedRange = recommendedRange,
            avgRpm = avgRpm,
            avgLoad = avgLoad,
            avgCoolant = avgCoolant,
            excursions = excursions,
            avgStft = avgStft,
            avgLtft = avgLtft,
            uphillCount = uphill.size,
            uphillAvgRpm = if (uphillRpms.isNotEmpty()) uphillRpms.average().roundToInt() else null,
            baseline = baseline,
            isCvtVehicle = isCvtVehicle,
        )

        val score = computePerformanceScore(
            lPer100 = lPer100,
            excursions = excursions,
            avgStft = avgStft,
            avgLtft = avgLtft,
            idleShare = if (hasFuel && totalFuelL > 0.0) (idleFuelL / totalFuelL).toFloat() else null,
            hasData = rpms.isNotEmpty() || coolants.isNotEmpty() || hasFuel,
        )

        return EcuPerformanceAnalysis(
            sampleCount = sorted.size,
            durationSeconds = durationSec,
            distanceKm = distanceKm,
            fuelProvenance = fuelProvenance,
            avgFuelRateLph = if (fuelRateCount > 0) fuelRateSum / fuelRateCount else null,
            totalFuelUsedLiters = if (hasFuel) totalFuelL else null,
            litersPer100Km = lPer100,
            kmPerLiter = kmPerL,
            idleFuelLiters = if (hasFuel) idleFuelL else null,
            accelFuelLiters = if (hasFuel) accelFuelL else null,
            cruiseFuelLiters = if (hasFuel) cruiseFuelL else null,
            speedBuckets = buckets,
            recommendedCruisingRange = recommendedRange,
            minRpm = rpms.minOrNull(),
            avgRpm = avgRpm,
            maxRpm = rpms.maxOrNull(),
            avgLoadPct = avgLoad,
            avgThrottlePct = if (throttles.isNotEmpty()) throttles.average().toFloat() else null,
            idleRpmStdDev = idleStd,
            minCoolantC = coolants.minOrNull(),
            avgCoolantC = avgCoolant,
            maxCoolantC = coolants.maxOrNull(),
            warmupSecondsTo75C = warmupSec,
            excursionCountAbove102C = excursions,
            avgMafGps = if (mafs.isNotEmpty()) mafs.average().toFloat() else null,
            avgMapKpa = if (maps.isNotEmpty()) maps.average().roundToInt() else null,
            avgStftPct = avgStft,
            avgLtftPct = avgLtft,
            uphillSampleCount = uphill.size,
            uphillAvgRpm = if (uphillRpms.isNotEmpty()) uphillRpms.average().roundToInt() else null,
            uphillAvgLoadPct = if (uphillLoads.isNotEmpty()) uphillLoads.average().toFloat() else null,
            performanceScore = score,
            insights = insights,
        )
    }

    private fun computeSpeedBuckets(sorted: List<EcuSample>): List<SpeedEfficiencyBucket> {
        val ranges = listOf(
            20 to 30, 30 to 40, 40 to 50, 50 to 60, 60 to 70, 70 to 80, 80 to 90, 90 to 110,
        )
        return ranges.mapNotNull { (lo, hi) ->
            val inBucket = sorted.filter { s ->
                val spd = s.bestSpeedKmh ?: return@filter false
                spd >= lo && spd < hi && s.isSteadyCruise
            }
            if (inBucket.isEmpty()) return@mapNotNull null

            val lPer100List = inBucket.mapNotNull { s ->
                val spd = s.bestSpeedKmh ?: return@mapNotNull null
                val lph = s.ecuFuelRateLph?.toDouble()
                    ?: s.mafGramsPerSec?.takeIf { it > 0f }?.let { (it * 3600.0) / (14.7 * 745.0) }
                    ?: return@mapNotNull null
                if (spd >= 5f) (lph / spd) * 100.0 else null
            }
            val avgL100 = if (lPer100List.isNotEmpty()) lPer100List.average() else null
            val avgKmL = avgL100?.takeIf { it > 0.0 }?.let { 100.0 / it }
            val rpms = inBucket.mapNotNull { it.rpm }
            val loads = inBucket.mapNotNull { it.engineLoadPct }
            val throttles = inBucket.mapNotNull { it.throttlePct }
            val grads = inBucket.mapNotNull { it.gradientPct }

            SpeedEfficiencyBucket(
                label = "$lo–$hi km/h",
                minKmh = lo,
                maxKmh = hi,
                observationSeconds = inBucket.size.toLong(),
                avgLitersPer100Km = avgL100,
                avgKmPerLiter = avgKmL,
                avgRpm = if (rpms.isNotEmpty()) rpms.average().roundToInt() else null,
                avgLoadPct = if (loads.isNotEmpty()) loads.average().toFloat() else null,
                avgThrottlePct = if (throttles.isNotEmpty()) throttles.average().toFloat() else null,
                avgGradientPct = if (grads.isNotEmpty()) grads.average().toFloat() else null,
                sampleCount = inBucket.size,
                confidence = when {
                    inBucket.size >= 20 -> "High"
                    inBucket.size >= 8 -> "Moderate"
                    else -> "Low (${inBucket.size} samples)"
                },
            )
        }
    }

    private fun buildInsights(
        samples: List<EcuSample>,
        durationSec: Long,
        lPer100: Double?,
        idleFuelL: Double?,
        totalFuelL: Double?,
        recommendedRange: String?,
        avgRpm: Int?,
        avgLoad: Float?,
        avgCoolant: Int?,
        excursions: Int,
        avgStft: Float?,
        avgLtft: Float?,
        uphillCount: Int,
        uphillAvgRpm: Int?,
        baseline: EcuVehicleBaseline,
        isCvtVehicle: Boolean,
    ): List<EcuInsight> {
        val out = mutableListOf<EcuInsight>()
        if (recommendedRange != null) {
            out.add(
                EcuInsight(
                    title = "Efficient cruising range observed at $recommendedRange",
                    evidence = "Lowest steady-cruise fuel consumption within speed limits.",
                    affectedTrips = "Current analysis (${samples.size} samples)",
                    confidence = "Moderate",
                    recommendedAction = "Maintain smooth throttle within $recommendedRange where road conditions permit.",
                ),
            )
        }
        if (idleFuelL != null && totalFuelL != null && totalFuelL > 0.05 && (idleFuelL / totalFuelL) >= 0.25) {
            val pct = ((idleFuelL / totalFuelL) * 100.0).roundToInt()
            out.add(
                EcuInsight(
                    title = "Extended idling contributed $pct% of estimated fuel use",
                    evidence = "${"%.2f".format(idleFuelL)} L consumed while stationary.",
                    affectedTrips = "Current journey (${durationSec / 60}m)",
                    confidence = "Moderate",
                    recommendedAction = "Shut off engine during prolonged stationary waits when safe.",
                ),
            )
        }
        if (excursions > 0) {
            out.add(
                EcuInsight(
                    title = "Coolant temperature reached >= 102°C ($excursions samples)",
                    evidence = "Elevated coolant observed; temperature alone does not confirm thermostat or pump fault.",
                    affectedTrips = "Current journey",
                    confidence = "High",
                    recommendedAction = "Ease engine load on inclines and inspect coolant level when engine is cool.",
                    inspectionAppropriate = true,
                ),
            )
        }
        if (avgLtft != null && abs(avgLtft) >= 10f && samples.size >= 10) {
            out.add(
                EcuInsight(
                    title = "Long-term fuel trim averaged ${"%.1f".format(avgLtft)}%",
                    evidence = "Persistent deviation from 0% stoichiometric trim (STFT avg ${avgStft?.let { "%.1f%%".format(it) } ?: "--"}).",
                    affectedTrips = "Current journey",
                    confidence = "Moderate",
                    recommendedAction = "Monitor across multiple trips; consider intake/O2 inspection if persistent.",
                    inspectionAppropriate = true,
                ),
            )
        }
        if (uphillCount >= 5 && uphillAvgRpm != null) {
            val cvtNote = if (isCvtVehicle) " CVT naturally raises ratio on climbs;" else ""
            out.add(
                EcuInsight(
                    title = "Uphill climbing averaged $uphillAvgRpm RPM",
                    evidence = "$uphillCount uphill samples (>= 3% grade).$cvtNote",
                    affectedTrips = "Current journey",
                    confidence = "Moderate",
                    recommendedAction = "Apply steady progressive throttle on inclines rather than sudden pedal spikes.",
                ),
            )
        }
        if (baseline.tripCount >= 3 && baseline.avgRpm != null && avgRpm != null && avgRpm > baseline.avgRpm + 450) {
            out.add(
                EcuInsight(
                    title = "Engine RPM was higher than your multi-trip baseline",
                    evidence = "Current avg $avgRpm RPM vs ${baseline.avgRpm} RPM baseline (${baseline.tripCount} trips).",
                    affectedTrips = "Comparison vs ${baseline.tripCount} prior trips",
                    confidence = "Moderate",
                    recommendedAction = "Check route gradient, A/C load, and tyre pressure.",
                ),
            )
        }
        return out
    }

    private fun computePerformanceScore(
        lPer100: Double?,
        excursions: Int,
        avgStft: Float?,
        avgLtft: Float?,
        idleShare: Float?,
        hasData: Boolean,
    ): Int? {
        if (!hasData) return null
        var score = 92
        if (excursions > 0) score -= (excursions * 4).coerceAtMost(20)
        if (avgLtft != null && abs(avgLtft) >= 10f) score -= 10
        if (avgStft != null && abs(avgStft) >= 12f) score -= 6
        if (idleShare != null && idleShare >= 0.3f) score -= 8
        if (lPer100 != null && lPer100 > 12.0) score -= 6
        return score.coerceIn(30, 100)
    }

    private fun emptyAnalysis() = EcuPerformanceAnalysis(
        sampleCount = 0, durationSeconds = 0L, distanceKm = 0.0, fuelProvenance = "Unavailable",
        avgFuelRateLph = null, totalFuelUsedLiters = null, litersPer100Km = null, kmPerLiter = null,
        idleFuelLiters = null, accelFuelLiters = null, cruiseFuelLiters = null, speedBuckets = emptyList(),
        recommendedCruisingRange = null, minRpm = null, avgRpm = null, maxRpm = null, avgLoadPct = null,
        avgThrottlePct = null, idleRpmStdDev = null, minCoolantC = null, avgCoolantC = null, maxCoolantC = null,
        warmupSecondsTo75C = null, excursionCountAbove102C = 0, avgMafGps = null, avgMapKpa = null,
        avgStftPct = null, avgLtftPct = null, uphillSampleCount = 0, uphillAvgRpm = null,
        uphillAvgLoadPct = null, performanceScore = null, insights = emptyList(),
    )

    companion object {
        fun parseBaseline(raw: String): EcuVehicleBaseline {
            if (raw.isBlank()) return EcuVehicleBaseline()
            val parts = raw.split("|")
            if (parts.size < 6) return EcuVehicleBaseline()
            return EcuVehicleBaseline(
                tripCount = parts[0].toIntOrNull() ?: 0,
                avgRpm = parts[1].toIntOrNull(),
                avgLoadPct = parts[2].toFloatOrNull(),
                avgCoolantC = parts[3].toIntOrNull(),
                avgLitersPer100Km = parts[4].toDoubleOrNull(),
                avgLtftPct = parts[5].toFloatOrNull(),
            )
        }

        fun serializeBaseline(b: EcuVehicleBaseline): String = listOf(
            b.tripCount.toString(),
            b.avgRpm?.toString() ?: "",
            b.avgLoadPct?.toString() ?: "",
            b.avgCoolantC?.toString() ?: "",
            b.avgLitersPer100Km?.toString() ?: "",
            b.avgLtftPct?.toString() ?: "",
        ).joinToString("|")

        fun mergeBaseline(current: EcuVehicleBaseline, trip: EcuPerformanceAnalysis): EcuVehicleBaseline {
            if (trip.sampleCount < 5) return current
            val n = current.tripCount.coerceAtLeast(0)
            val nextCount = n + 1
            fun avgInt(prev: Int?, cur: Int?): Int? = when {
                cur == null -> prev
                prev == null || n == 0 -> cur
                else -> ((prev.toDouble() * n + cur.toDouble()) / nextCount).roundToInt()
            }
            fun avgFloat(prev: Float?, cur: Float?): Float? = when {
                cur == null -> prev
                prev == null || n == 0 -> cur
                else -> ((prev * n + cur) / nextCount)
            }
            fun avgDouble(prev: Double?, cur: Double?): Double? = when {
                cur == null -> prev
                prev == null || n == 0 -> cur
                else -> ((prev * n + cur) / nextCount)
            }
            return EcuVehicleBaseline(
                tripCount = nextCount,
                avgRpm = avgInt(current.avgRpm, trip.avgRpm),
                avgLoadPct = avgFloat(current.avgLoadPct, trip.avgLoadPct),
                avgCoolantC = avgInt(current.avgCoolantC, trip.avgCoolantC),
                avgLitersPer100Km = avgDouble(current.avgLitersPer100Km, trip.litersPer100Km),
                avgLtftPct = avgFloat(current.avgLtftPct, trip.avgLtftPct),
            )
        }
    }
}
