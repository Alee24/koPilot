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
            linearAccelX = 4.2f,
            linearAccelY = 0.3f,
            linearAccelZ = 0f,
            gyroX = 0f,
            gyroY = 0.8f,
            gyroZ = 0f,
            azimuthDeg = 110f,
            pitchDeg = 5f,
            rollDeg = 2f,
            gpsSpeedKmh = 45f,
        )
        assertEquals(LateralMotionState.SHARP_CORNERING, corner.lateralState)

        var vibResult = corner
        repeat(8) {
            vibResult = analyzer.analyze(
                linearAccelX = 0f,
                linearAccelY = 3.0f,
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
        assertEquals(RoadVibrationLevel.ROUGH_ROAD_EXPERIMENTAL, vibResult.roadVibrationLevel)
        assertTrue(vibResult.verticalVibrationRmsMps2 >= DrivingMotionAnalyzer.ROUGH_ROAD_RMS_MPS2)
    }
}
