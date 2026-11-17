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
    private var lastLinearNorm = 0f

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
        val isMoving = (gpsSpeedKmh ?: 0f) >= 5f
        val latState = when {
            isMoving && (abs(yawRate) >= SHARP_CORNER_RAD_S || abs(lateral) >= SHARP_LATERAL_MPS2) ->
                LateralMotionState.SHARP_CORNERING
            yawRate >= TURN_RAD_S -> LateralMotionState.LEFT_TURN
            yawRate <= -TURN_RAD_S -> LateralMotionState.RIGHT_TURN
            yawRate >= LANE_SHIFT_RAD_S || lateral <= -LANE_SHIFT_LATERAL_MPS2 -> LateralMotionState.LANE_SHIFT_LEFT
            yawRate <= -LANE_SHIFT_RAD_S || lateral >= LANE_SHIFT_LATERAL_MPS2 -> LateralMotionState.LANE_SHIFT_RIGHT
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
            rms >= ROUGH_ROAD_RMS_MPS2 -> RoadVibrationLevel.ROUGH_ROAD_EXPERIMENTAL
            rms >= MODERATE_VIBRATION_RMS_MPS2 -> RoadVibrationLevel.MODERATE_VIBRATION
            else -> RoadVibrationLevel.SMOOTH
        }

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
            updatedAtMillis = nowMillis,
        )
    }

    fun reset() {
        verticalSamples.clear()
        lastLinearNorm = 0f
    }

    companion object {
        const val WINDOW_SIZE = 20
        const val ACCEL_MPS2 = 1.6f
        const val HARD_ACCEL_MPS2 = 3.5f
        const val BRAKE_MPS2 = -1.8f
        const val HARSH_BRAKE_MPS2 = -4.2f
        const val LANE_SHIFT_RAD_S = 0.12f
        const val LANE_SHIFT_LATERAL_MPS2 = 0.95f
        const val TURN_RAD_S = 0.25f
        const val SHARP_CORNER_RAD_S = 0.65f
        const val SHARP_LATERAL_MPS2 = 3.8f
        const val PHONE_ROTATION_THRESHOLD_RAD_S = 1.4f
        const val SUDDEN_JERK_MPS2 = 6.5f
        const val SUDDEN_IMPACT_MPS2 = 11.0f
        const val MODERATE_VIBRATION_RMS_MPS2 = 1.1f
        const val ROUGH_ROAD_RMS_MPS2 = 2.4f
    }
}
