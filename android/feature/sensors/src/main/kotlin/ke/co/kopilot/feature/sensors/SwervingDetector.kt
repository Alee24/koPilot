package ke.co.kopilot.feature.sensors

import kotlin.math.abs

data class SwerveAnalysisResult(
    val category: SwerveBehaviorCategory,
    val legacySwerveClass: SwerveClass,
    val peakLateralMps2: Float,
    val peakYawDegPerSec: Float,
    val cumulativeCourseChangeDeg: Float,
    val reversalCount: Int,
    val reason: String,
    val shouldPreserveEvidence: Boolean,
)

/**
 * Dedicated multi-feature swerving detector combining calibrated lateral acceleration,
 * gyroscope yaw rate, GPS course change (when reliable), and vehicle speed.
 *
 * Distinguishes normal road bends, intentional turns, roundabouts, lane changes,
 * repeated erratic swerving, and abrupt evasive movements.
 * Swerving alone never triggers an emergency SMS.
 */
class SwervingDetector {

    fun analyze(
        recentFrames: List<SensorFrame>,
        config: AccidentThresholdConfig = AccidentThresholdConfig(),
    ): SwerveAnalysisResult {
        if (recentFrames.size < 3) {
            return SwerveAnalysisResult(
                category = SwerveBehaviorCategory.NONE,
                legacySwerveClass = SwerveClass.NORMAL_TURNING,
                peakLateralMps2 = 0f,
                peakYawDegPerSec = 0f,
                cumulativeCourseChangeDeg = 0f,
                reversalCount = 0,
                reason = "Insufficient samples for swerve analysis",
                shouldPreserveEvidence = false,
            )
        }

        val speeds = recentFrames.mapNotNull { it.bestIndependentSpeedKmh() }
        val maxSpeedKmh = speeds.maxOrNull() ?: 0f
        if (maxSpeedKmh < MIN_SWERVE_SPEED_KMH) {
            return SwerveAnalysisResult(
                category = SwerveBehaviorCategory.NONE,
                legacySwerveClass = SwerveClass.NORMAL_TURNING,
                peakLateralMps2 = recentFrames.maxOf { abs(it.lateralAccelMps2) },
                peakYawDegPerSec = recentFrames.maxOf { abs(it.angularVelDegPerSec) },
                cumulativeCourseChangeDeg = 0f,
                reversalCount = 0,
                reason = "Vehicle speed below ${MIN_SWERVE_SPEED_KMH.toInt()} km/h",
                shouldPreserveEvidence = false,
            )
        }

        val peakLat = recentFrames.maxOf { abs(it.lateralAccelMps2) }
        val peakYaw = recentFrames.maxOf { abs(it.angularVelDegPerSec) }

        // Count significant lateral acceleration sign reversals (left <-> right oscillations)
        val significantThreshold = config.swerveLateralMps2 * 0.65f
        var lastSign = 0
        var reversalCount = 0
        var strongPositiveCount = 0
        var strongNegativeCount = 0
        var maxLatJerk = 0f

        for (i in recentFrames.indices) {
            val f = recentFrames[i]
            if (f.lateralAccelMps2 >= significantThreshold) {
                strongPositiveCount++
                if (lastSign == -1) reversalCount++
                lastSign = 1
            } else if (f.lateralAccelMps2 <= -significantThreshold) {
                strongNegativeCount++
                if (lastSign == 1) reversalCount++
                lastSign = -1
            }
            if (i > 0) {
                val prev = recentFrames[i - 1]
                val dtSec = ((f.monotonicMillis - prev.monotonicMillis).coerceAtLeast(20L)) / 1000f
                val jerk = abs(f.lateralAccelMps2 - prev.lateralAccelMps2) / dtSec
                if (jerk > maxLatJerk) maxLatJerk = jerk
            }
        }

        // Compute GPS course change when GPS accuracy is reliable (<= 25m) and speed >= 15 km/h
        val reliableCourses = recentFrames.filter {
            it.gpsCourseDeg != null && (it.gpsAccuracyMeters == null || it.gpsAccuracyMeters <= 25f) &&
                (it.gpsSpeedKmh ?: it.speedKmh ?: 0f) >= 15f
        }
        var netCourseDelta = 0f
        var absCourseDelta = 0f
        for (i in 1 until reliableCourses.size) {
            val d = shortestAngleDelta(reliableCourses[i - 1].gpsCourseDeg!!, reliableCourses[i].gpsCourseDeg!!)
            netCourseDelta += d
            absCourseDelta += abs(d)
        }

        val alternatingStrong = strongPositiveCount >= 1 && strongNegativeCount >= 1 && reversalCount >= 1
        val repeatedReversals = reversalCount >= 2 || (strongPositiveCount >= 2 && strongNegativeCount >= 2 && reversalCount >= 1)

        val category: SwerveBehaviorCategory
        val legacy: SwerveClass
        val reason: String

        when {
            // 1. Repeated erratic swerving: multiple opposing lateral/yaw reversals at speed
            repeatedReversals &&
                peakLat >= config.swerveLateralMps2 &&
                peakYaw >= config.swerveYawDegPerSec &&
                maxSpeedKmh >= 20f -> {
                category = SwerveBehaviorCategory.REPEATED_ERRATIC_SWERVING
                legacy = if (peakLat >= config.swerveLateralMps2 * 1.25f && peakYaw >= config.swerveYawDegPerSec * 1.4f) {
                    SwerveClass.LOSS_OF_CONTROL
                } else {
                    SwerveClass.POSSIBLE_SWERVING
                }
                reason = "Repeated lateral oscillations ($reversalCount reversals, ${"%.1f".format(peakLat)} m/s², ${"%.0f".format(peakYaw)}°/s at ${maxSpeedKmh.toInt()} km/h)"
            }

            // 2. Abrupt evasive movement: high-jerk single sharp avoidance maneuver at speed
            maxSpeedKmh >= 25f &&
                peakLat >= config.swerveLateralMps2 * 1.2f &&
                peakYaw >= config.swerveYawDegPerSec * 1.15f &&
                maxLatJerk >= 12.0f &&
                abs(netCourseDelta) < 55f -> {
                category = SwerveBehaviorCategory.ABRUPT_EVASIVE_MOVEMENT
                legacy = SwerveClass.POSSIBLE_SWERVING
                reason = "Abrupt evasive lateral maneuver (${"%.1f".format(peakLat)} m/s², jerk ${"%.1f".format(maxLatJerk)} m/s³, ${"%.0f".format(peakYaw)}°/s)"
            }

            // 3. Roundabout navigation: sustained single-direction turn with large cumulative GPS heading sweep
            reversalCount == 0 && abs(netCourseDelta) >= 110f -> {
                category = SwerveBehaviorCategory.ROUNDABOUT
                legacy = SwerveClass.NORMAL_TURNING
                reason = "Sustained roundabout curve (${"%.0f".format(abs(netCourseDelta))}° heading sweep)"
            }

            // 4. Intentional turn: single-direction heading change >= 45° at urban/turn speed
            reversalCount == 0 && abs(netCourseDelta) >= 45f && peakLat >= 2.2f -> {
                category = SwerveBehaviorCategory.INTENTIONAL_TURN
                legacy = if (peakLat >= config.swerveLateralMps2 * 0.85f) SwerveClass.SHARP_TURNING else SwerveClass.NORMAL_TURNING
                reason = "Intentional turn (${"%.0f".format(abs(netCourseDelta))}° course change)"
            }

            // 5. Lane change: brief alternating S-curve below erratic swerve threshold
            alternatingStrong && peakLat >= 2.8f && peakLat < config.swerveLateralMps2 * 1.15f -> {
                category = SwerveBehaviorCategory.LANE_CHANGE
                legacy = SwerveClass.LANE_CHANGE
                reason = "Brief lane-change lateral transition (${"%.1f".format(peakLat)} m/s²)"
            }

            // 6. Normal road bend: single-sign moderate lateral acceleration
            reversalCount == 0 && peakLat >= 2.0f -> {
                category = SwerveBehaviorCategory.NORMAL_ROAD_BEND
                legacy = if (peakLat >= config.swerveLateralMps2 * 0.85f) SwerveClass.SHARP_TURNING else SwerveClass.NORMAL_TURNING
                reason = "Smooth road bend (${"%.1f".format(peakLat)} m/s²)"
            }

            else -> {
                category = SwerveBehaviorCategory.NONE
                legacy = SwerveClass.NORMAL_TURNING
                reason = "Normal straight/gentle steering"
            }
        }

        return SwerveAnalysisResult(
            category = category,
            legacySwerveClass = legacy,
            peakLateralMps2 = peakLat,
            peakYawDegPerSec = peakYaw,
            cumulativeCourseChangeDeg = netCourseDelta,
            reversalCount = reversalCount,
            reason = reason,
            shouldPreserveEvidence = category.shouldPreserveEvidence,
        )
    }

    private fun shortestAngleDelta(fromDeg: Float, toDeg: Float): Float {
        var d = (toDeg - fromDeg) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    companion object {
        const val MIN_SWERVE_SPEED_KMH = 15f
    }
}
