package ke.co.kopilot.feature.sensors.fusion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Centralized Multi-Sensor Fusion Engine synchronizing GPS, barometer, IMU orientation,
 * and OBD-II telemetry using monotonic timestamps.
 */
class SensorFusionEngine(
    private val terrainEngine: TerrainIntelligenceEngine = TerrainIntelligenceEngine(),
    private val verificationEngine: EvidenceVerificationEngine = EvidenceVerificationEngine(),
) {
    private var elevationGainM = 0.0
    private var elevationLossM = 0.0
    private var uphillDistKm = 0.0
    private var downhillDistKm = 0.0
    private var uphillDurSec = 0L
    private var downhillDurSec = 0L
    private var suppressedCount = 0
    private var confirmedCount = 0

    private var prevLat: Double? = null
    private var prevLng: Double? = null
    private var prevAltM: Double? = null
    private var prevMonoMs: Long = 0L
    private var lastTimelineSampleMs: Long = 0L

    private val recentFindings = ArrayDeque<FusionFinding>()
    private val timelineSamples = ArrayDeque<FusionTimelineSample>()

    fun reset() {
        terrainEngine.reset()
        elevationGainM = 0.0
        elevationLossM = 0.0
        uphillDistKm = 0.0
        downhillDistKm = 0.0
        uphillDurSec = 0L
        downhillDurSec = 0L
        suppressedCount = 0
        confirmedCount = 0
        prevLat = null
        prevLng = null
        prevAltM = null
        prevMonoMs = 0L
        lastTimelineSampleMs = 0L
        recentFindings.clear()
        timelineSamples.clear()
    }

    fun process(input: SensorFusionInput): SensorFusionSnapshot {
        val ts = input.timestampMillis
        val mono = input.monotonicMillis

        // Reject stale GPS / OBD / Baro inputs
        val gpsFresh = input.gpsTimestampMillis != null && (ts - input.gpsTimestampMillis) <= 5_000L
        val baroFresh = input.baroTimestampMillis != null && (ts - input.baroTimestampMillis) <= 5_000L
        val obdFresh = input.obdConnected && input.obdTimestampMillis != null && (ts - input.obdTimestampMillis) <= 5_000L

        val sanitizedInput = input.copy(
            gpsAltitudeMeters = if (gpsFresh) input.gpsAltitudeMeters else null,
            gpsLatitude = if (gpsFresh) input.gpsLatitude else null,
            gpsLongitude = if (gpsFresh) input.gpsLongitude else null,
            gpsSpeedKmh = if (gpsFresh) input.gpsSpeedKmh else null,
            baroPressureHpa = if (baroFresh) input.baroPressureHpa else null,
            obdRpm = if (obdFresh) input.obdRpm else null,
            obdLoadPct = if (obdFresh) input.obdLoadPct else null,
            obdThrottlePct = if (obdFresh) input.obdThrottlePct else null,
            obdSpeedKmh = if (obdFresh) input.obdSpeedKmh else null,
            obdCoolantTempC = if (obdFresh) input.obdCoolantTempC else null,
        )

        val freshnessSummary = buildString {
            append(if (gpsFresh) "GPS:Fresh" else "GPS:Stale/Off")
            append(" | ")
            append(if (baroFresh) "Baro:Fresh" else "Baro:Off")
            append(" | ")
            append("Mount:${sanitizedInput.mountCalibrationStatus.name}")
            append(" | ")
            append(if (obdFresh) "OBD:Fresh" else "OBD:Off")
        }

        val terrain = terrainEngine.evaluate(sanitizedInput)
        val newFindings = verificationEngine.evaluateTerrainAndEngine(
            input = sanitizedInput,
            terrain = terrain,
            freshnessSummary = freshnessSummary,
        )

        for (f in newFindings) {
            if (f.suppressed) {
                suppressedCount++
            } else if (f.severity != FusionAlertSeverity.INFO) {
                confirmedCount++
            }
            val lastSame = recentFindings.lastOrNull { it.eventCategory == f.eventCategory && (mono - it.monotonicMillis) < 5_000L }
            if (lastSame == null) {
                recentFindings.addLast(f)
                while (recentFindings.size > 40) recentFindings.removeFirst()
            }
        }

        // Accumulate trip terrain distance, duration, and elevation gain/loss
        if (sanitizedInput.isTripActive && prevMonoMs > 0L) {
            val dtSec = ((mono - prevMonoMs).coerceIn(0L, 5_000L)) / 1000L
            val dKm = if (
                gpsFresh &&
                sanitizedInput.gpsLatitude != null &&
                sanitizedInput.gpsLongitude != null &&
                prevLat != null &&
                prevLng != null &&
                (sanitizedInput.gpsHorizontalAccuracyMeters ?: 999f) <= 35f
            ) {
                val meters = haversineMeters(prevLat!!, prevLng!!, sanitizedInput.gpsLatitude, sanitizedInput.gpsLongitude)
                if (meters in 0.5..300.0) meters / 1000.0 else 0.0
            } else 0.0

            when (terrain.category) {
                TerrainCategory.MILD_UPHILL, TerrainCategory.STEEP_UPHILL -> {
                    uphillDistKm += dKm
                    uphillDurSec += dtSec
                }
                TerrainCategory.MILD_DOWNHILL, TerrainCategory.STEEP_DOWNHILL -> {
                    downhillDistKm += dKm
                    downhillDurSec += dtSec
                }
                else -> Unit
            }

            val curAlt = sanitizedInput.gpsAltitudeMeters
            if (
                gpsFresh &&
                curAlt != null &&
                prevAltM != null &&
                (sanitizedInput.gpsVerticalAccuracyMeters ?: 999f) <= 25f
            ) {
                val dAlt = curAlt - prevAltM!!
                if (abs(dAlt) in 0.3..10.0) {
                    if (dAlt > 0) elevationGainM += dAlt else elevationLossM += abs(dAlt)
                }
            }
        }

        if (gpsFresh && sanitizedInput.gpsLatitude != null && sanitizedInput.gpsLongitude != null) {
            prevLat = sanitizedInput.gpsLatitude
            prevLng = sanitizedInput.gpsLongitude
            if (sanitizedInput.gpsAltitudeMeters != null && (sanitizedInput.gpsVerticalAccuracyMeters ?: 999f) <= 25f) {
                prevAltM = sanitizedInput.gpsAltitudeMeters
            }
        }
        prevMonoMs = mono

        val bestSpeed = sanitizedInput.obdSpeedKmh ?: sanitizedInput.gpsSpeedKmh
        if (sanitizedInput.isTripActive && (lastTimelineSampleMs == 0L || (mono - lastTimelineSampleMs) >= 2_000L)) {
            lastTimelineSampleMs = mono
            timelineSamples.addLast(
                FusionTimelineSample(
                    timestampMillis = ts,
                    gradientPct = terrain.gradientPct,
                    speedKmh = bestSpeed,
                    rpm = sanitizedInput.obdRpm,
                    loadPct = sanitizedInput.obdLoadPct,
                    terrainCategory = terrain.category,
                ),
            )
            while (timelineSamples.size > 1_800) timelineSamples.removeFirst()
        }

        return SensorFusionSnapshot(
            terrainCategory = terrain.category,
            gradientPct = terrain.gradientPct,
            confidence = terrain.confidence,
            supportingSources = terrain.supportingSources,
            conflictingSources = terrain.conflictingSources,
            gpsAltitudeMeters = sanitizedInput.gpsAltitudeMeters,
            gpsVerticalAccuracyMeters = sanitizedInput.gpsVerticalAccuracyMeters,
            baroPressureHpa = sanitizedInput.baroPressureHpa,
            baroElevationTrendMeters = terrain.baroElevationDeltaMeters,
            calibratedPitchDeg = sanitizedInput.calibratedPitchDeg,
            mountCalibrationStatus = sanitizedInput.mountCalibrationStatus,
            accelAvailable = sanitizedInput.accelAvailable,
            gyroAvailable = sanitizedInput.gyroAvailable,
            obdConnected = sanitizedInput.obdConnected,
            obdRpm = sanitizedInput.obdRpm,
            obdLoadPct = sanitizedInput.obdLoadPct,
            obdThrottlePct = sanitizedInput.obdThrottlePct,
            obdCoolantC = sanitizedInput.obdCoolantTempC,
            vehicleSpeedKmh = bestSpeed,
            elevationGainMeters = elevationGainM,
            elevationLossMeters = elevationLossM,
            uphillDistanceKm = uphillDistKm,
            downhillDistanceKm = downhillDistKm,
            uphillDurationSec = uphillDurSec,
            downhillDurationSec = downhillDurSec,
            recentFindings = recentFindings.toList().reversed(),
            suppressedAlertsCount = suppressedCount,
            confirmedAdvisoriesCount = confirmedCount,
            timelineSamples = timelineSamples.toList(),
            lastUpdatedMillis = ts,
        )
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
