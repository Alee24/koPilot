package ke.co.kopilot.feature.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingMotionAnalyzerTest {

    @Test
    fun `detects harsh braking, sharp cornering, and rough road vibration`() {
        val analyzer = DrivingMotionAnalyzer()

        val brake = analyzer.analyze(
            linearAccelX = 0.1f,
            linearAccelY = 0.2f,
            linearAccelZ = -4.8f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            azimuthDeg = 90f,
            pitchDeg = 5f,
            rollDeg = 0f,
            gpsSpeedKmh = 60f,
        )
        assertEquals(LongitudinalMotionState.HARSH_BRAKING, brake.longitudinalState)

        val corner = analyzer.analyze(
            linearAccelX = 5.0f,
            linearAccelY = 0.3f,
            linearAccelZ = 0f,
            gyroX = 0f,
            gyroY = 1.05f,
            gyroZ = 0f,
            azimuthDeg = 110f, // 20 degrees compass heading shift from 90f
            pitchDeg = 5f,
            rollDeg = 2f,
            gpsSpeedKmh = 45f,
        )
        assertEquals(LateralMotionState.SHARP_CORNERING, corner.lateralState)

        var vibResult = corner
        repeat(12) {
            vibResult = analyzer.analyze(
                linearAccelX = 0f,
                linearAccelY = 5.2f,
                linearAccelZ = 0f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = 0f,
                azimuthDeg = 110f,
                pitchDeg = 5f,
                rollDeg = 0f,
                gpsSpeedKmh = 35f,
            )
        }
        assertEquals(RoadVibrationLevel.VERY_ROUGH, vibResult.roadVibrationLevel)
        assertTrue(vibResult.verticalVibrationRmsMps2 >= DrivingMotionAnalyzer.VERY_ROUGH_RMS_MPS2)
    }

    @Test
    fun `detects left and right compass turns and lane shifts without inverting direction`() {
        val analyzer = DrivingMotionAnalyzer()

        // Establish initial heading North-East (45 deg)
        analyzer.analyze(
            linearAccelX = 0f,
            linearAccelY = 0f,
            linearAccelZ = 0f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            azimuthDeg = 45f,
            pitchDeg = 0f,
            rollDeg = 0f,
            gpsSpeedKmh = 50f,
        )

        // Small shake (< 20 deg compass change and below doubled lane-shift threshold) stays STRAIGHT
        val slightShake = analyzer.analyze(
            linearAccelX = 1.8f,
            linearAccelY = 0f,
            linearAccelZ = 0f,
            gyroX = 0f,
            gyroY = 0.25f,
            gyroZ = 0f,
            azimuthDeg = 38f, // only -7 deg change
            pitchDeg = 0f,
            rollDeg = 0f,
            gpsSpeedKmh = 50f,
        )
        assertEquals(LateralMotionState.STRAIGHT, slightShake.lateralState)

        // Turning from North-East (45 deg) to North (0 deg) => negative delta >= 20 deg => LEFT_TURN
        val turnLeftToNorth = analyzer.analyze(
            linearAccelX = 0.5f,
            linearAccelY = 0f,
            linearAccelZ = 0f,
            gyroX = 0f,
            gyroY = 0.2f,
            gyroZ = 0f,
            azimuthDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f,
            gpsSpeedKmh = 50f,
        )
        assertEquals(LateralMotionState.LEFT_TURN, turnLeftToNorth.lateralState)

        // Turning right to East (90 deg) => positive delta >= 20 deg => RIGHT_TURN
        val turnRightToEast = analyzer.analyze(
            linearAccelX = -0.5f,
            linearAccelY = 0f,
            linearAccelZ = 0f,
            gyroX = 0f,
            gyroY = -0.2f,
            gyroZ = 0f,
            azimuthDeg = 60f,
            pitchDeg = 0f,
            rollDeg = 0f,
            gpsSpeedKmh = 50f,
        )
        assertEquals(LateralMotionState.RIGHT_TURN, turnRightToEast.lateralState)

        val stationary = analyzer.analyze(
            linearAccelX = 5.0f,
            linearAccelY = 0f,
            linearAccelZ = 0f,
            gyroX = 0f,
            gyroY = 0.65f,
            gyroZ = 0f,
            azimuthDeg = 90f,
            pitchDeg = 0f,
            rollDeg = 0f,
            gpsSpeedKmh = 0f,
        )
        assertEquals(LateralMotionState.STRAIGHT, stationary.lateralState)
    }

    @Test
    fun `braking requires both speed reduction and sensor deceleration forces`() {
        val analyzer = DrivingMotionAnalyzer()
        // Cruising at 60 km/h
        analyzer.analyze(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, gpsSpeedKmh = 60f, nowMillis = 1_000L)

        // Sensor deceleration spike WITHOUT speed reduction (still 60 km/h) -> must NOT report braking
        val falseBrake = analyzer.analyze(
            linearAccelX = 0f,
            linearAccelY = 0f,
            linearAccelZ = -5.0f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            azimuthDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f,
            gpsSpeedKmh = 60f,
            nowMillis = 1_400L,
        )
        assertEquals(LongitudinalMotionState.STEADY, falseBrake.longitudinalState)

        // Speed reduced from 60 km/h to 52 km/h WITH braking force -> reports HARSH_BRAKING
        val realBrake = analyzer.analyze(
            linearAccelX = 0f,
            linearAccelY = 0f,
            linearAccelZ = -5.0f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            azimuthDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f,
            gpsSpeedKmh = 52f,
            nowMillis = 1_800L,
        )
        assertEquals(LongitudinalMotionState.HARSH_BRAKING, realBrake.longitudinalState)
    }

    @Test
    fun `tracks road smoothness score, bumps, braking, acceleration, and graph samples`() {
        val analyzer = DrivingMotionAnalyzer()
        var ts = 1_000L
        repeat(8) {
            analyzer.analyze(
                linearAccelX = 0f,
                linearAccelY = 0.2f,
                linearAccelZ = 2.6f, // accelerating above increased threshold
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = 0f,
                azimuthDeg = 0f,
                pitchDeg = 0f,
                rollDeg = 0f,
                gpsSpeedKmh = 30f,
                nowMillis = ts,
            )
            ts += 400L
        }
        val afterBrake = analyzer.analyze(
            linearAccelX = 0f,
            linearAccelY = 12.0f, // pothole bump spike above doubled 11.0f threshold
            linearAccelZ = -4.6f, // harsh braking with speed drop 30 -> 20 km/h
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            azimuthDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f,
            gpsSpeedKmh = 20f,
            nowMillis = ts + 3_000L,
        )
        val stats = afterBrake.behaviourStats
        assertTrue(stats.recentSamples.isNotEmpty())
        assertTrue(stats.accelerationCount >= 1)
        assertTrue(stats.harshBrakingCount >= 1)
        assertTrue(stats.potholeBumpCount >= 1)
        assertTrue(stats.driverBehaviourScore < 100)
    }
}
