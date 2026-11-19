package ke.co.kopilot.feature.sensors

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure Kotlin driving motion intelligence engine combining linear acceleration,
 * gyroscope angular velocity, orientation/rotation angles, and GPS speed.
 * All thresholds are deterministic and run off the main UI thread.
 */
@Singleton
class DrivingMotionAnalyzer @Inject constructor() {

    private val verticalSamples = ArrayDeque<Float>(WINDOW_SIZE)
    private val graphSamples = ArrayDeque<RoadVibrationSample>(MAX_GRAPH_SAMPLES)
    private var lastLinearNorm = 0f
    private var lastGraphSampleMs = 0L
    private var totalScoredSamples = 0
    private var scoreSum = 0L
    private var smoothCount = 0
    private var moderateCount = 0
    private var roughCount = 0
    private var veryRoughCount = 0
    private var potholeBumpCount = 0
    private var lastBumpMs = 0L
    private var brakingCount = 0
    private var harshBrakingCount = 0
    private var lastBrakeEventMs = 0L
    private var accelerationCount = 0
    private var hardAccelerationCount = 0
    private var lastAccelEventMs = 0L
    private var sharpCornerCount = 0
    private var lastCornerEventMs = 0L

    fun analyze(
        linearAccelX: Float,
        linearAccelY: Float,
        linearAccelZ: Float,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        azimuthDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        gpsSpeedKmh: Float?,
        nowMillis: Long = System.currentTimeMillis(),
    ): DrivingMotionAnalysis {
        // In a dashboard/windscreen-mounted phone (portrait), phone -Z/Y axes capture longitudinal & vertical
        // motion; we combine horizontal plane magnitude + gyro yaw rate for mount-tolerant estimation.
        val longitudinal = linearAccelZ
        val lateral = linearAccelX
        val vertical = linearAccelY

        val longState = when {
            longitudinal <= HARSH_BRAKE_MPS2 -> LongitudinalMotionState.HARSH_BRAKING
            longitudinal <= BRAKE_MPS2 -> LongitudinalMotionState.BRAKING
            longitudinal >= HARD_ACCEL_MPS2 -> LongitudinalMotionState.HARD_ACCELERATION
            longitudinal >= ACCEL_MPS2 -> LongitudinalMotionState.ACCELERATING
            else -> LongitudinalMotionState.STEADY
        }

        val yawRate = gyroY // Vertical axis in upright portrait mount
        val isMoving = (gpsSpeedKmh ?: 0f) >= 8.0f
        val latState = when {
            !isMoving -> LateralMotionState.STRAIGHT
            abs(yawRate) >= SHARP_CORNER_RAD_S || abs(lateral) >= SHARP_LATERAL_MPS2 ->
                LateralMotionState.SHARP_CORNERING
            yawRate >= TURN_RAD_S -> LateralMotionState.LEFT_TURN
            yawRate <= -TURN_RAD_S -> LateralMotionState.RIGHT_TURN
            yawRate >= LANE_SHIFT_RAD_S || lateral >= LANE_SHIFT_LATERAL_MPS2 -> LateralMotionState.LANE_SHIFT_LEFT
            yawRate <= -LANE_SHIFT_RAD_S || lateral <= -LANE_SHIFT_LATERAL_MPS2 -> LateralMotionState.LANE_SHIFT_RIGHT
            else -> LateralMotionState.STRAIGHT
        }

        val gyroMagnitude = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
        val linearMagnitude = sqrt(
            linearAccelX * linearAccelX +
                linearAccelY * linearAccelY +
                linearAccelZ * linearAccelZ,
        )
        val jerkSpike = abs(linearMagnitude - lastLinearNorm)
        lastLinearNorm = linearMagnitude

        val phoneMoved = gyroMagnitude >= PHONE_ROTATION_THRESHOLD_RAD_S && abs(pitchDeg) < 75f
        val suddenMove = jerkSpike >= SUDDEN_JERK_MPS2 || linearMagnitude >= SUDDEN_IMPACT_MPS2

        if (verticalSamples.size >= WINDOW_SIZE) {
            verticalSamples.removeFirst()
        }
        verticalSamples.addLast(vertical)

        val rms = if (verticalSamples.size >= 5) {
            var sumSq = 0f
            for (v in verticalSamples) sumSq += v * v
            sqrt(sumSq / verticalSamples.size)
        } else {
            0f
        }

        val vibrationLevel = when {
            verticalSamples.size < 5 -> RoadVibrationLevel.UNKNOWN
            rms >= VERY_ROUGH_RMS_MPS2 -> RoadVibrationLevel.VERY_ROUGH
            rms >= ROUGH_ROAD_RMS_MPS2 -> RoadVibrationLevel.ROUGH_ROAD_EXPERIMENTAL
            rms >= MODERATE_VIBRATION_RMS_MPS2 -> RoadVibrationLevel.MODERATE_VIBRATION
            else -> RoadVibrationLevel.SMOOTH
        }

        val currentScore = (100f - (rms / 4.2f) * 100f).toInt().coerceIn(0, 100)

        // Update road-quality & driving-behaviour statistics (ignore stationary phone handling)
        var bumpInWindow = false
        if (!phoneMoved && verticalSamples.size >= 5) {
            if ((abs(vertical) >= POTHOLE_SPIKE_MPS2 || jerkSpike >= 5.5f) && (nowMillis - lastBumpMs) >= 1_500L) {
                lastBumpMs = nowMillis
                potholeBumpCount++
                bumpInWindow = true
            }
            if ((nowMillis - lastGraphSampleMs) >= 350L || bumpInWindow) {
                lastGraphSampleMs = nowMillis
                if (graphSamples.size >= MAX_GRAPH_SAMPLES) graphSamples.removeFirst()
                graphSamples.addLast(RoadVibrationSample(nowMillis, rms, currentScore, vibrationLevel, isBumpSpike = bumpInWindow))

                totalScoredSamples++
                scoreSum += currentScore
                when {
                    currentScore >= 80 -> smoothCount++
                    currentScore >= 55 -> moderateCount++
                    currentScore >= 30 -> roughCount++
                    else -> veryRoughCount++
                }
            }
        }

        if ((nowMillis - lastBrakeEventMs) >= 2_500L) {
            when (longState) {
                LongitudinalMotionState.HARSH_BRAKING -> {
                    lastBrakeEventMs = nowMillis
                    harshBrakingCount++
                    brakingCount++
                }
                LongitudinalMotionState.BRAKING -> {
                    lastBrakeEventMs = nowMillis
                    brakingCount++
                }
                else -> {}
            }
        }

        if ((nowMillis - lastAccelEventMs) >= 2_500L) {
            when (longState) {
                LongitudinalMotionState.HARD_ACCELERATION -> {
                    lastAccelEventMs = nowMillis
                    hardAccelerationCount++
                    accelerationCount++
                }
                LongitudinalMotionState.ACCELERATING -> {
                    lastAccelEventMs = nowMillis
                    accelerationCount++
                }
                else -> {}
            }
        }

        if (latState == LateralMotionState.SHARP_CORNERING && (nowMillis - lastCornerEventMs) >= 2_500L) {
            lastCornerEventMs = nowMillis
            sharpCornerCount++
        }

        val avgRoadScore = if (totalScoredSamples > 0) (scoreSum / totalScoredSamples).toInt().coerceIn(0, 100) else currentScore
        val smoothPct = if (totalScoredSamples > 0) (smoothCount * 100) / totalScoredSamples else 100
        val moderatePct = if (totalScoredSamples > 0) (moderateCount * 100) / totalScoredSamples else 0
        val roughPct = if (totalScoredSamples > 0) (roughCount * 100) / totalScoredSamples else 0
        val veryRoughPct = if (totalScoredSamples > 0) (100 - smoothPct - moderatePct - roughPct).coerceAtLeast(0) else 0
        val penalty = (harshBrakingCount * 4 + hardAccelerationCount * 3 + sharpCornerCount * 3).coerceAtMost(85)
        val driverScore = (100 - penalty).coerceIn(15, 100)

        val stats = DrivingBehaviourStats(
            roadSmoothnessScore = currentScore,
            averageRoadScore = avgRoadScore,
            smoothPct = smoothPct,
            moderatePct = moderatePct,
            roughPct = roughPct,
            veryRoughPct = veryRoughPct,
            potholeBumpCount = potholeBumpCount,
            brakingCount = brakingCount,
            harshBrakingCount = harshBrakingCount,
            accelerationCount = accelerationCount,
            hardAccelerationCount = hardAccelerationCount,
            sharpCornerCount = sharpCornerCount,
            driverBehaviourScore = driverScore,
            recentSamples = graphSamples.toList(),
        )

        return DrivingMotionAnalysis(
            longitudinalState = longState,
            lateralState = latState,
            longitudinalAccelMps2 = longitudinal,
            lateralAccelMps2 = lateral,
            verticalVibrationRmsMps2 = rms,
            yawRateRadPerSec = yawRate,
            roadVibrationLevel = vibrationLevel,
            suddenMovementDetected = suddenMove,
            phoneMovementDetected = phoneMoved,
            pitchDegrees = pitchDeg,
            rollDegrees = rollDeg,
            azimuthDegrees = azimuthDeg,
            behaviourStats = stats,
            updatedAtMillis = nowMillis,
        )
    }

    fun reset() {
        verticalSamples.clear()
        graphSamples.clear()
        lastLinearNorm = 0f
        lastGraphSampleMs = 0L
        totalScoredSamples = 0
        scoreSum = 0L
        smoothCount = 0
        moderateCount = 0
        roughCount = 0
        veryRoughCount = 0
        potholeBumpCount = 0
        lastBumpMs = 0L
        brakingCount = 0
        harshBrakingCount = 0
        lastBrakeEventMs = 0L
        accelerationCount = 0
        hardAccelerationCount = 0
        lastAccelEventMs = 0L
        sharpCornerCount = 0
        lastCornerEventMs = 0L
    }

    companion object {
        const val WINDOW_SIZE = 20
        const val MAX_GRAPH_SAMPLES = 250
        const val POTHOLE_SPIKE_MPS2 = 5.5f
        const val MODERATE_VIBRATION_RMS_MPS2 = 1.15f
        const val ROUGH_ROAD_RMS_MPS2 = 2.10f
        const val VERY_ROUGH_RMS_MPS2 = 3.20f
        const val ACCEL_MPS2 = 1.8f
        const val HARD_ACCEL_MPS2 = 3.8f
        const val BRAKE_MPS2 = -2.0f
        const val HARSH_BRAKE_MPS2 = -4.5f
        const val LANE_SHIFT_RAD_S = 0.28f
        const val LANE_SHIFT_LATERAL_MPS2 = 2.2f
        const val TURN_RAD_S = 0.48f
        const val SHARP_CORNER_RAD_S = 0.95f
        const val SHARP_LATERAL_MPS2 = 4.8f
        const val PHONE_ROTATION_THRESHOLD_RAD_S = 1.6f
        const val SUDDEN_JERK_MPS2 = 7.5f
        const val SUDDEN_IMPACT_MPS2 = 12.0f
    }
}
