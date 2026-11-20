package ke.co.kopilot.feature.sensors

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

enum class MountCalibrationStatus(val label: String) {
    CALIBRATED("Calibrated"),
    CALIBRATION_REQUIRED("Calibration required"),
    MOUNT_MOVEMENT_SUSPECTED("Mount movement suspected"),
    SENSOR_UNAVAILABLE("Sensor unavailable"),
}

data class VehicleAxesSample(
    val forwardMps2: Float,
    val lateralMps2: Float,
    val verticalMps2: Float,
    val pitchDeltaDeg: Float,
    val rollDeltaDeg: Float,
    val yawDeltaDeg: Float,
    val mountStabilityScore: Float,
    val calibrationStatus: MountCalibrationStatus,
)

/**
 * Estimates gravity direction and transforms device-coordinate accelerometer readings into
 * vehicle-aligned coordinates (forward, lateral, vertical) while monitoring mount stability.
 *
 * Device orientation alone cannot uniquely determine the forward axis when stationary, so initial
 * gravity establishes the vertical axis and forward/lateral axes refine automatically during
 * initial straight-line acceleration/GPS movement or manual recalibration.
 */
class PhoneMountCalibrator {
    private var refGravity = floatArrayOf(0f, 9.81f, 0f)
    private var refForward = floatArrayOf(0f, 0f, -1f)
    private var refLateral = floatArrayOf(1f, 0f, 0f)
    private var refPitchDeg = 0f
    private var refRollDeg = 0f
    private var refYawDeg = 0f

    private var hasGravityRef = false
    private var hasForwardRef = false
    private var calibratedAtMillis = 0L
    private var mountShiftSamples = 0
    private var lastStatus = MountCalibrationStatus.CALIBRATION_REQUIRED

    fun getStatus(): MountCalibrationStatus = lastStatus
    fun getCalibratedAtMillis(): Long = calibratedAtMillis

    fun requestRecalibration() {
        hasGravityRef = false
        hasForwardRef = false
        mountShiftSamples = 0
        calibratedAtMillis = 0L
        lastStatus = MountCalibrationStatus.CALIBRATION_REQUIRED
    }

    fun update(
        nowMillis: Long,
        accelAvailable: Boolean,
        gyroAvailable: Boolean,
        rawAx: Float,
        rawAy: Float,
        rawAz: Float,
        linearAx: Float,
        linearAy: Float,
        linearAz: Float,
        pitchDeg: Float,
        rollDeg: Float,
        yawDeg: Float,
        gpsSpeedKmh: Float?,
    ): VehicleAxesSample {
        if (!accelAvailable) {
            lastStatus = MountCalibrationStatus.SENSOR_UNAVAILABLE
            return VehicleAxesSample(0f, 0f, 0f, 0f, 0f, 0f, 0f, lastStatus)
        }

        // Low-pass gravity estimate from raw accelerometer when linear motion is modest
        val rawMag = sqrt(rawAx * rawAx + rawAy * rawAy + rawAz * rawAz)
        val linMag = sqrt(linearAx * linearAx + linearAy * linearAy + linearAz * linearAz)
        if (!hasGravityRef && rawMag in 7.5f..12.2f && linMag < 2.2f) {
            val inv = 1f / rawMag
            refGravity = floatArrayOf(rawAx * inv, rawAy * inv, rawAz * inv)
            refPitchDeg = pitchDeg
            refRollDeg = rollDeg
            refYawDeg = yawDeg
            // Build an initial orthonormal basis orthogonal to gravity
            val up = refGravity
            val candidate = if (abs(up[2]) < 0.85f) floatArrayOf(0f, 0f, -1f) else floatArrayOf(0f, 1f, 0f)
            refLateral = normalize(cross(candidate, up))
            refForward = normalize(cross(up, refLateral))
            hasGravityRef = true
            calibratedAtMillis = nowMillis
            lastStatus = MountCalibrationStatus.CALIBRATED
        }

        // Refine forward axis automatically when vehicle accelerates cleanly under GPS speed >= 8 km/h
        if (hasGravityRef && !hasForwardRef && (gpsSpeedKmh ?: 0f) >= 8f && linMag in 1.1f..4.5f) {
            val gUnit = refGravity
            val dotUp = linearAx * gUnit[0] + linearAy * gUnit[1] + linearAz * gUnit[2]
            val horiz = floatArrayOf(
                linearAx - dotUp * gUnit[0],
                linearAy - dotUp * gUnit[1],
                linearAz - dotUp * gUnit[2],
            )
            val horizNorm = norm(horiz)
            if (horizNorm >= 1.0f) {
                refForward = floatArrayOf(horiz[0] / horizNorm, horiz[1] / horizNorm, horiz[2] / horizNorm)
                refLateral = normalize(cross(refForward, gUnit))
                hasForwardRef = true
            }
        }

        if (!hasGravityRef) {
            lastStatus = MountCalibrationStatus.CALIBRATION_REQUIRED
            return VehicleAxesSample(linearAz, linearAx, linearAy, 0f, 0f, 0f, 0.5f, lastStatus)
        }

        // Project linear acceleration onto vehicle-aligned axes
        val forward = dot(linearAx, linearAy, linearAz, refForward)
        val lateral = dot(linearAx, linearAy, linearAz, refLateral)
        val vertical = dot(linearAx, linearAy, linearAz, refGravity)

        val dPitch = normalizeAngleDelta(pitchDeg - refPitchDeg)
        val dRoll = normalizeAngleDelta(rollDeg - refRollDeg)
        val dYaw = normalizeAngleDelta(yawDeg - refYawDeg)

        // Check gravity-vector drift to distinguish mount slip / phone handling from genuine driving
        // Even while moving, a phone picked up or rotated in hand shows >24° gravity drift with modest linear motion
        val currentGravityAngleDeg = if (rawMag in 7.8f..12.2f && linMag < 5.5f) {
            val cosTheta = ((rawAx * refGravity[0] + rawAy * refGravity[1] + rawAz * refGravity[2]) / rawMag)
                .coerceIn(-1f, 1f)
            Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
        } else {
            0f
        }

        val speedLow = (gpsSpeedKmh ?: 0f) < 5f
        if ((speedLow && currentGravityAngleDeg > 20f) || (!speedLow && currentGravityAngleDeg > 28f && linMag < 4.0f)) {
            mountShiftSamples++
        } else if (currentGravityAngleDeg < 10f) {
            mountShiftSamples = (mountShiftSamples - 1).coerceAtLeast(0)
        }

        lastStatus = when {
            !gyroAvailable && !hasGravityRef -> MountCalibrationStatus.SENSOR_UNAVAILABLE
            mountShiftSamples >= 4 -> MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED
            else -> MountCalibrationStatus.CALIBRATED
        }

        val stability = (1f - (currentGravityAngleDeg / 60f)).coerceIn(0f, 1f)
        return VehicleAxesSample(
            forwardMps2 = forward,
            lateralMps2 = lateral,
            verticalMps2 = vertical,
            pitchDeltaDeg = dPitch,
            rollDeltaDeg = dRoll,
            yawDeltaDeg = dYaw,
            mountStabilityScore = stability,
            calibrationStatus = lastStatus,
        )
    }

    private fun normalizeAngleDelta(deg: Float): Float {
        var d = deg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    private fun dot(x: Float, y: Float, z: Float, v: FloatArray): Float = x * v[0] + y * v[1] + z * v[2]

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun norm(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun normalize(v: FloatArray): FloatArray {
        val n = norm(v)
        return if (n > 1e-4f) floatArrayOf(v[0] / n, v[1] / n, v[2] / n) else floatArrayOf(1f, 0f, 0f)
    }
}
