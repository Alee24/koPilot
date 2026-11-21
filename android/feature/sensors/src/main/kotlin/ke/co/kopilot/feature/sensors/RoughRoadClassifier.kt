package ke.co.kopilot.feature.sensors

import kotlin.math.abs
import kotlin.math.sqrt

enum class RoadEventClassification(val label: String, val isRoadDisturbance: Boolean) {
    SMOOTH_DRIVING("Smooth driving", false),
    ORDINARY_ROAD_BUMP("Ordinary road bump", true),
    POTHOLE("Pothole impact", true),
    ROUGH_ROAD("Rough / corrugated road", true),
    POSSIBLE_COLLISION("Possible collision pattern", false),
}

data class RoughRoadResult(
    val classification: RoadEventClassification,
    val verticalPeakCount: Int,
    val verticalToHorizontalRatio: Float,
    val reason: String,
)

/**
 * Signal-processing classifier that distinguishes ordinary bumps, potholes, and recurring
 * rough-road vibrations (e.g. gravel, speed bumps, uneven tarmac) from collision impulses.
 */
class RoughRoadClassifier {

    fun classify(
        frames: List<SensorFrame>,
        cfg: AccidentThresholdConfig,
        orientationDeltaDeg: Float,
        abruptSpeedDropKmh: Float,
        vehicleStillMoving: Boolean,
    ): RoughRoadResult {
        if (frames.isEmpty()) {
            return RoughRoadResult(RoadEventClassification.SMOOTH_DRIVING, 0, 1f, "No frames")
        }

        val maxVert = frames.maxOf { abs(it.verticalAccelMps2) }
        val maxHoriz = frames.maxOf {
            sqrt(it.forwardAccelMps2 * it.forwardAccelMps2 + it.lateralAccelMps2 * it.lateralAccelMps2)
        }
        val ratio = maxVert / maxHoriz.coerceAtLeast(0.5f)

        // Count distinct vertical disturbance peaks (>= 7.5 m/s²) separated by >= 150ms
        var vertPeaks = 0
        var lastPeakMs = -10_000L
        for (f in frames) {
            if (abs(f.verticalAccelMps2) >= 7.5f && (f.monotonicMillis - lastPeakMs) >= 150L) {
                vertPeaks++
                lastPeakMs = f.monotonicMillis
            }
        }

        val hasStrongHorizCollision = maxHoriz >= cfg.strongImpactMps2 && ratio < 1.25f
        val hasSevereCollisionEvidence = hasStrongHorizCollision &&
            (abruptSpeedDropKmh >= cfg.abruptSpeedLossKmh || orientationDeltaDeg >= cfg.rolloverAngleDeg || !vehicleStillMoving)

        return when {
            hasSevereCollisionEvidence -> RoughRoadResult(
                classification = RoadEventClassification.POSSIBLE_COLLISION,
                verticalPeakCount = vertPeaks,
                verticalToHorizontalRatio = ratio,
                reason = "Strong horizontal impulse (${"%.1f".format(maxHoriz)} m/s², V/H=${"%.2f".format(ratio)}) with collision corroboration",
            )
            vertPeaks >= 3 && ratio >= 1.05f && orientationDeltaDeg < cfg.rolloverAngleDeg -> RoughRoadResult(
                classification = RoadEventClassification.ROUGH_ROAD,
                verticalPeakCount = vertPeaks,
                verticalToHorizontalRatio = ratio,
                reason = "Recurring vertical vibration ($vertPeaks peaks, V/H=${"%.2f".format(ratio)}) with stable orientation",
            )
            vertPeaks == 2 && ratio >= 1.1f && orientationDeltaDeg < cfg.rolloverAngleDeg -> RoughRoadResult(
                classification = RoadEventClassification.POTHOLE,
                verticalPeakCount = vertPeaks,
                verticalToHorizontalRatio = ratio,
                reason = "Short vertical disturbance + rebound ($vertPeaks peaks, V/H=${"%.2f".format(ratio)})",
            )
            (maxVert >= 7.5f || ratio >= 1.25f) && !hasStrongHorizCollision && orientationDeltaDeg < cfg.rolloverAngleDeg -> RoughRoadResult(
                classification = RoadEventClassification.ORDINARY_ROAD_BUMP,
                verticalPeakCount = vertPeaks.coerceAtLeast(1),
                verticalToHorizontalRatio = ratio,
                reason = "Brief vertical bump (${"%.1f".format(maxVert)} m/s², V/H=${"%.2f".format(ratio)})",
            )
            else -> RoughRoadResult(
                classification = RoadEventClassification.SMOOTH_DRIVING,
                verticalPeakCount = vertPeaks,
                verticalToHorizontalRatio = ratio,
                reason = "Normal road profile",
            )
        }
    }
}
