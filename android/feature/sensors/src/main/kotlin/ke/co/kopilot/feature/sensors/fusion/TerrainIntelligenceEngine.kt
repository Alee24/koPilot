package ke.co.kopilot.feature.sensors.fusion

import ke.co.kopilot.feature.sensors.MountCalibrationStatus
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class TerrainConfig(
    val flatThresholdPct: Float = 1.0f,
    val steepThresholdPct: Float = 5.0f,
    val hysteresisPct: Float = 0.4f,
    val minDistanceMeters: Double = 30.0,
    val minDurationMillis: Long = 3_000L,
    val minMovingSpeedKmh: Float = 5.0f,
    val maxGpsVerticalAccuracyM: Float = 25.0f,
    val maxGpsHorizontalAccuracyM: Float = 35.0f,
    val maxAltitudeJumpMetersPerSec: Double = 6.0,
)

private data class GeoElevSample(
    val monotonicMillis: Long,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double,
    val cumDistanceM: Double,
)

private data class BaroSample(
    val monotonicMillis: Long,
    val pressureHpa: Float,
    val cumDistanceM: Double,
)

/**
 * Estimates sustained road gradient and terrain category using independent GPS elevation,
 * calibrated barometric pressure trends, and calibrated vehicle-relative pitch.
 */
class TerrainIntelligenceEngine(
    private val config: TerrainConfig = TerrainConfig(),
) {
    private val gpsWindow = ArrayDeque<GeoElevSample>()
    private val baroWindow = ArrayDeque<BaroSample>()
    private val pitchWindow = ArrayDeque<Pair<Long, Float>>()

    private var cumDistanceMeters = 0.0
    private var lastGeoSample: GeoElevSample? = null
    private var lastValidAltM: Double? = null
    private var lastValidAltMonoMs: Long = 0L

    private var currentCategory: TerrainCategory = TerrainCategory.UNCERTAIN
    private var pendingCategory: TerrainCategory? = null
    private var pendingSinceMonoMs: Long = 0L

    fun reset() {
        gpsWindow.clear()
        baroWindow.clear()
        pitchWindow.clear()
        cumDistanceMeters = 0.0
        lastGeoSample = null
        lastValidAltM = null
        lastValidAltMonoMs = 0L
        currentCategory = TerrainCategory.UNCERTAIN
        pendingCategory = null
        pendingSinceMonoMs = 0L
    }

    fun evaluate(input: SensorFusionInput): TerrainEvidenceResult {
        val monoMs = input.monotonicMillis
        val bestSpeedKmh = input.obdSpeedKmh ?: input.gpsSpeedKmh ?: 0f

        // 1. Update cumulative horizontal distance from valid GPS fixes
        val lat = input.gpsLatitude
        val lng = input.gpsLongitude
        val hAccOk = (input.gpsHorizontalAccuracyMeters ?: 999f) <= config.maxGpsHorizontalAccuracyM
        if (lat != null && lng != null && hAccOk) {
            val prev = lastGeoSample
            if (prev != null) {
                val d = haversineMeters(prev.lat, prev.lng, lat, lng)
                val dtSec = ((monoMs - prev.monotonicMillis).coerceAtLeast(100L)) / 1000.0
                if (d in 0.5..(65.0 * dtSec)) {
                    cumDistanceMeters += d
                }
            }
        }

        // 2. GPS Elevation Gradient (independent source #1)
        val vAccOk = (input.gpsVerticalAccuracyMeters ?: 999f) <= config.maxGpsVerticalAccuracyM
        val alt = input.gpsAltitudeMeters
        if (lat != null && lng != null && alt != null && hAccOk && vAccOk && bestSpeedKmh >= config.minMovingSpeedKmh) {
            val prevAlt = lastValidAltM
            val dtSec = ((monoMs - lastValidAltMonoMs).coerceAtLeast(250L)) / 1000.0
            val jumpRate = if (prevAlt != null) abs(alt - prevAlt) / dtSec else 0.0
            if (prevAlt == null || jumpRate <= config.maxAltitudeJumpMetersPerSec) {
                lastValidAltM = alt
                lastValidAltMonoMs = monoMs
                val sample = GeoElevSample(monoMs, lat, lng, alt, cumDistanceMeters)
                lastGeoSample = sample
                gpsWindow.addLast(sample)
                while (gpsWindow.size > 40 || (gpsWindow.isNotEmpty() && monoMs - gpsWindow.first().monotonicMillis > 25_000L)) {
                    gpsWindow.removeFirst()
                }
            }
        } else if (lat != null && lng != null && hAccOk && lastGeoSample == null) {
            lastGeoSample = GeoElevSample(monoMs, lat, lng, alt ?: 0.0, cumDistanceMeters)
        }

        val gpsGradientPct: Float? = if (gpsWindow.size >= 2 && bestSpeedKmh >= config.minMovingSpeedKmh) {
            val first = gpsWindow.first()
            val last = gpsWindow.last()
            val dist = last.cumDistanceM - first.cumDistanceM
            val dur = last.monotonicMillis - first.monotonicMillis
            if (dist >= config.minDistanceMeters && dur >= config.minDurationMillis) {
                ((last.altitudeM - first.altitudeM) / dist * 100.0).toFloat().coerceIn(-35f, 35f)
            } else null
        } else null

        // 3. Barometric Elevation Trend (independent source #2)
        val pressure = input.baroPressureHpa
        if (pressure != null && pressure in 500f..1100f) {
            baroWindow.addLast(BaroSample(monoMs, pressure, cumDistanceMeters))
            while (baroWindow.size > 40 || (baroWindow.isNotEmpty() && monoMs - baroWindow.first().monotonicMillis > 25_000L)) {
                baroWindow.removeFirst()
            }
        }

        var baroElevDeltaM: Float? = null
        val baroGradientPct: Float? = if (baroWindow.size >= 2 && bestSpeedKmh >= config.minMovingSpeedKmh) {
            val first = baroWindow.first()
            val last = baroWindow.last()
            val dist = last.cumDistanceM - first.cumDistanceM
            val dur = last.monotonicMillis - first.monotonicMillis
            val dElevM = -8.43f * (last.pressureHpa - first.pressureHpa)
            baroElevDeltaM = dElevM
            if (dist >= config.minDistanceMeters && dur >= config.minDurationMillis) {
                (dElevM / dist.toFloat() * 100f).coerceIn(-35f, 35f)
            } else null
        } else null

        // 4. Calibrated Vehicle-Relative Pitch (independent source #3)
        if (input.mountCalibrationStatus == MountCalibrationStatus.CALIBRATED && !input.phoneMovedInMount && input.calibratedPitchDeg != null) {
            // Suppress single bump/pothole spikes via sliding median-like average
            if (input.verticalVibrationRmsMps2 < 2.2f) {
                pitchWindow.addLast(monoMs to input.calibratedPitchDeg)
                while (pitchWindow.size > 20 || (pitchWindow.isNotEmpty() && monoMs - pitchWindow.first().first > 6_000L)) {
                    pitchWindow.removeFirst()
                }
            }
        } else {
            pitchWindow.clear()
        }

        val pitchGradientPct: Float? = if (
            pitchWindow.size >= 3 &&
            input.mountCalibrationStatus == MountCalibrationStatus.CALIBRATED &&
            !input.phoneMovedInMount
        ) {
            val avgPitchDeg = pitchWindow.map { it.second }.average()
            (tan(Math.toRadians(avgPitchDeg)) * 100.0).toFloat().coerceIn(-35f, 35f)
        } else null

        // 5. Evaluate independent sources & check agreement
        val candidates = mutableListOf<Pair<EvidenceSource, Float>>()
        gpsGradientPct?.let { candidates += EvidenceSource.GPS_ELEVATION to it }
        baroGradientPct?.let { candidates += EvidenceSource.BAROMETER_TREND to it }
        pitchGradientPct?.let { candidates += EvidenceSource.VEHICLE_PITCH to it }

        if (bestSpeedKmh < config.minMovingSpeedKmh) {
            // Stationary / very low speed: do not emit unsupported moving gradient estimates
            return TerrainEvidenceResult(
                category = TerrainCategory.UNCERTAIN,
                gradientPct = null,
                gpsGradientPct = gpsGradientPct,
                baroGradientPct = baroGradientPct,
                pitchGradientPct = pitchGradientPct,
                baroElevationDeltaMeters = baroElevDeltaM,
                supportingSources = emptyList(),
                conflictingSources = emptyList(),
                confidence = "Uncertain",
                explanation = "Vehicle stationary or below ${config.minMovingSpeedKmh.toInt()} km/h; sustained gradient requires movement.",
            )
        }

        if (candidates.size < 2) {
            return TerrainEvidenceResult(
                category = TerrainCategory.UNCERTAIN,
                gradientPct = candidates.firstOrNull()?.second,
                gpsGradientPct = gpsGradientPct,
                baroGradientPct = baroGradientPct,
                pitchGradientPct = pitchGradientPct,
                baroElevationDeltaMeters = baroElevDeltaM,
                supportingSources = candidates.map { it.first },
                conflictingSources = emptyList(),
                confidence = "Uncertain",
                explanation = "Fewer than two independent terrain sources available (${candidates.size}/3).",
            )
        }

        val medianGrad = candidates.map { it.second }.sorted()[candidates.size / 2]
        val supporting = mutableListOf<EvidenceSource>()
        val conflicting = mutableListOf<EvidenceSource>()

        for ((src, grad) in candidates) {
            val diff = abs(grad - medianGrad)
            val signDisagree = (grad > 1.5f && medianGrad < -1.5f) || (grad < -1.5f && medianGrad > 1.5f)
            if (signDisagree || diff > 4.5f) {
                conflicting += src
            } else {
                supporting += src
            }
        }

        if (supporting.size < 2 || conflicting.isNotEmpty() && supporting.size <= conflicting.size) {
            currentCategory = TerrainCategory.UNCERTAIN
            return TerrainEvidenceResult(
                category = TerrainCategory.UNCERTAIN,
                gradientPct = null,
                gpsGradientPct = gpsGradientPct,
                baroGradientPct = baroGradientPct,
                pitchGradientPct = pitchGradientPct,
                baroElevationDeltaMeters = baroElevDeltaM,
                supportingSources = supporting,
                conflictingSources = conflicting,
                confidence = "Uncertain",
                explanation = "Independent terrain sensors disagree (${supporting.size} supporting vs ${conflicting.size} conflicting).",
            )
        }

        val fusedGrad = candidates.filter { it.first in supporting }.map { it.second }.average().toFloat()
        val rawCat = classifyWithHysteresis(fusedGrad, currentCategory)
        if (rawCat != currentCategory) {
            if (pendingCategory != rawCat) {
                pendingCategory = rawCat
                pendingSinceMonoMs = monoMs
            } else if (monoMs - pendingSinceMonoMs >= 1_500L) {
                currentCategory = rawCat
                pendingCategory = null
            }
        } else {
            pendingCategory = null
        }

        val finalCat = if (currentCategory == TerrainCategory.UNCERTAIN) rawCat else currentCategory
        currentCategory = finalCat
        val conf = if (supporting.size >= 3) "High" else "Moderate"

        return TerrainEvidenceResult(
            category = finalCat,
            gradientPct = fusedGrad,
            gpsGradientPct = gpsGradientPct,
            baroGradientPct = baroGradientPct,
            pitchGradientPct = pitchGradientPct,
            baroElevationDeltaMeters = baroElevDeltaM,
            supportingSources = supporting,
            conflictingSources = conflicting,
            confidence = conf,
            explanation = "Corroborated by ${supporting.joinToString(", ") { it.label }} (${"%.1f".format(fusedGrad)}% grade).",
        )
    }

    private fun classifyWithHysteresis(grad: Float, prev: TerrainCategory): TerrainCategory {
        val f = config.flatThresholdPct
        val s = config.steepThresholdPct
        val h = config.hysteresisPct
        return when (prev) {
            TerrainCategory.STEEP_UPHILL -> if (grad >= s - h) TerrainCategory.STEEP_UPHILL else classifyBase(grad, f, s)
            TerrainCategory.MILD_UPHILL -> when {
                grad > s + h -> TerrainCategory.STEEP_UPHILL
                grad < f - h -> classifyBase(grad, f, s)
                else -> TerrainCategory.MILD_UPHILL
            }
            TerrainCategory.STEEP_DOWNHILL -> if (grad <= -s + h) TerrainCategory.STEEP_DOWNHILL else classifyBase(grad, f, s)
            TerrainCategory.MILD_DOWNHILL -> when {
                grad < -s - h -> TerrainCategory.STEEP_DOWNHILL
                grad > -f + h -> classifyBase(grad, f, s)
                else -> TerrainCategory.MILD_DOWNHILL
            }
            else -> classifyBase(grad, f, s)
        }
    }

    private fun classifyBase(grad: Float, f: Float, s: Float): TerrainCategory = when {
        grad > s -> TerrainCategory.STEEP_UPHILL
        grad > f -> TerrainCategory.MILD_UPHILL
        grad < -s -> TerrainCategory.STEEP_DOWNHILL
        grad < -f -> TerrainCategory.MILD_DOWNHILL
        else -> TerrainCategory.FLAT
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
