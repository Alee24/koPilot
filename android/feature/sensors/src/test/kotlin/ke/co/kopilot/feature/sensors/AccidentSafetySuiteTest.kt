package ke.co.kopilot.feature.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentSafetySuiteTest {

    private fun frame(
        t: Long,
        fwd: Float = 0f,
        lat: Float = 0f,
        vert: Float = 0f,
        mag: Float = 0.5f,
        gyro: Float = 5f,
        pitch: Float = 0f,
        roll: Float = 0f,
        speed: Float? = 50f,
        acc: Float? = 6f,
        rpm: Int? = 1800,
    ) = SensorFrame(t, fwd, lat, vert, mag, gyro, pitch, roll, speed, acc, rpm)

    @Test
    fun `normal acceleration and sharp cornering do not trigger emergency countdown`() {
        val engine = AccidentDetectionEngine()
        var lastOut: AccidentEngineOutput? = null
        for (i in 0..10) {
            lastOut = engine.processFrame(
                frame = frame(t = i * 500L, fwd = 2.5f, lat = 3.2f, mag = 4.1f, gyro = 28f, speed = 42f),
                mountStatus = MountCalibrationStatus.CALIBRATED,
                accelAvailable = true,
                gyroAvailable = true,
                obdConnected = true,
                sensitivity = AccidentSensitivityProfile.STANDARD,
                impactEnabled = true,
                rolloverEnabled = true,
                swerveEnabled = true,
            )
        }
        assertFalse(lastOut!!.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `pothole or speed bump without speed drop does not trigger emergency SMS`() {
        val engine = AccidentDetectionEngine()
        engine.processFrame(
            frame = frame(t = 1_000L, vert = 12f, mag = 12.5f, speed = 35f),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
            sensitivity = AccidentSensitivityProfile.STANDARD,
            impactEnabled = true,
            rolloverEnabled = true,
            swerveEnabled = true,
        )
        val out = engine.processFrame(
            frame = frame(t = 2_000L, vert = 1.0f, mag = 1.2f, speed = 34f),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
            sensitivity = AccidentSensitivityProfile.STANDARD,
            impactEnabled = true,
            rolloverEnabled = true,
            swerveEnabled = true,
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `phone mount movement or removal is suppressed and not treated as rollover`() {
        val engine = AccidentDetectionEngine()
        val out = engine.processFrame(
            frame = frame(t = 5_000L, mag = 10f, gyro = 140f, roll = 65f, speed = 3f),
            mountStatus = MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = false,
            sensitivity = AccidentSensitivityProfile.STANDARD,
            impactEnabled = true,
            rolloverEnabled = true,
            swerveEnabled = true,
        )
        assertTrue(out.assessment.suppressedAsPhoneHandling)
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `simulated collision with severe impulse and speed drop triggers countdown and suppresses duplicate`() {
        val engine = AccidentDetectionEngine()
        // Pre-event cruising at 65 km/h
        for (i in 0..4) {
            engine.processFrame(
                frame = frame(t = i * 1_000L, mag = 0.8f, speed = 65f),
                mountStatus = MountCalibrationStatus.CALIBRATED,
                accelAvailable = true,
                gyroAvailable = true,
                obdConnected = true,
                sensitivity = AccidentSensitivityProfile.STANDARD,
                impactEnabled = true,
                rolloverEnabled = true,
                swerveEnabled = true,
            )
        }
        // Severe impact + sudden speed drop to 0
        val crashOut = engine.processFrame(
            frame = frame(t = 6_000L, fwd = -27f, lat = 8f, mag = 28.5f, gyro = 80f, speed = 0f),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
            sensitivity = AccidentSensitivityProfile.STANDARD,
            impactEnabled = true,
            rolloverEnabled = true,
            swerveEnabled = true,
        )
        assertTrue(crashOut.assessment.eventClass.triggersCountdown)
        assertTrue(crashOut.postImpactMonitoringActive)

        // Immediate next frame should be duplicate-suppressed from re-triggering countdown
        val dupOut = engine.processFrame(
            frame = frame(t = 6_500L, fwd = -26f, mag = 26f, speed = 0f),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
            sensitivity = AccidentSensitivityProfile.STANDARD,
            impactEnabled = true,
            rolloverEnabled = true,
            swerveEnabled = true,
        )
        assertFalse(dupOut.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `simulated rollover pattern triggers suspected rollover`() {
        val engine = AccidentDetectionEngine()
        engine.processFrame(
            frame = frame(t = 1_000L, speed = 55f),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
            sensitivity = AccidentSensitivityProfile.STANDARD,
            impactEnabled = true,
            rolloverEnabled = true,
            swerveEnabled = true,
        )
        val out = engine.processFrame(
            frame = frame(t = 4_000L, lat = 19f, mag = 21f, gyro = 135f, roll = 72f, speed = 15f),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
            sensitivity = AccidentSensitivityProfile.STANDARD,
            impactEnabled = true,
            rolloverEnabled = true,
            swerveEnabled = true,
        )
        assertTrue(out.assessment.eventClass.triggersCountdown)
        assertTrue(
            out.assessment.eventClass == AccidentEventClass.SUSPECTED_ROLLOVER ||
                out.assessment.eventClass == AccidentEventClass.HIGH_CONFIDENCE_POSSIBLE_ACCIDENT,
        )
    }

    @Test
    fun `engine stop monitor distinguishes ordinary stall and OBD disconnect from collision stop`() {
        val monitor = EngineStopMonitor()
        // Running at 1600 RPM
        monitor.evaluate(1_000L, true, 1600, 35f, 1.0f, 17.5f, MountCalibrationStatus.CALIBRATED)
        // Ordinary stall without impact
        val stall = monitor.evaluate(2_000L, true, 0, 22f, 2.0f, 17.5f, MountCalibrationStatus.CALIBRATED)
        assertEquals(EngineStopClassification.ENGINE_STALL, stall.classification)
        assertFalse(stall.classification.isEmergencyCandidate)

        // OBD disconnect is never treated as engine stop
        val disc = monitor.evaluate(3_000L, false, null, 20f, 2.0f, 17.5f, MountCalibrationStatus.CALIBRATED)
        assertEquals(EngineStopClassification.OBD_DISCONNECTED, disc.classification)
        assertFalse(disc.classification.isEmergencyCandidate)

        // Collision engine stop
        monitor.evaluate(4_000L, true, 1800, 50f, 1.0f, 17.5f, MountCalibrationStatus.CALIBRATED)
        val crashStop = monitor.evaluate(5_000L, true, 0, 0f, 26.0f, 17.5f, MountCalibrationStatus.CALIBRATED)
        assertEquals(EngineStopClassification.POSSIBLE_COLLISION_ENGINE_STOP, crashStop.classification)
        assertTrue(crashStop.classification.isEmergencyCandidate)
    }

    @Test
    fun `compass heading estimator uses GPS course when moving and formats cardinals`() {
        val est = CompassHeadingEstimator()
        val moving = est.update(gpsSpeedKmh = 40f, gpsBearingDeg = 45f, sensorAzimuthDeg = 180f)
        assertEquals("NE", moving.cardinal)
        assertEquals("NE · 045°", moving.formatted)
        assertEquals("GPS", moving.source)
    }
}
