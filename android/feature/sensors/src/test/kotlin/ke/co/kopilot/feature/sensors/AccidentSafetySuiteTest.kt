package ke.co.kopilot.feature.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive offline unit & replay test suite covering all 16 false-positive scenarios,
 * independent 4-group confirmation, severe fallback rules, swerving classification,
 * cancellation suppression, and missing-sensor handling.
 */
class AccidentSafetySuiteTest {

    private fun frame(
        t: Long,
        fwd: Float = 0f,
        lat: Float = 0f,
        vert: Float = 0f,
        mag: Float = 0.5f,
        gyro: Float = 4f,
        pitch: Float = 0f,
        roll: Float = 0f,
        gpsSpeed: Float? = 50f,
        gpsCourse: Float? = 90f,
        acc: Float? = 6f,
        obdSpeed: Float? = gpsSpeed,
        rpm: Int? = 1800,
        obdConnected: Boolean = true,
        obdEcuLost: Boolean = false,
        watchBpm: Int? = null,
        watchBaseline: Float? = null,
        watchSamples: Int = 0,
        watchAgeMs: Long? = null,
        watchContact: Boolean? = null,
    ) = SensorFrame(
        timestampMillis = t,
        monotonicMillis = t,
        forwardAccelMps2 = fwd,
        lateralAccelMps2 = lat,
        verticalAccelMps2 = vert,
        linearMagMps2 = mag,
        angularVelDegPerSec = gyro,
        pitchDeltaDeg = pitch,
        rollDeltaDeg = roll,
        speedKmh = gpsSpeed ?: obdSpeed,
        gpsAccuracyMeters = acc,
        obdRpm = rpm,
        gpsSpeedKmh = gpsSpeed,
        gpsCourseDeg = gpsCourse,
        obdSpeedKmh = obdSpeed,
        obdConnected = obdConnected,
        obdEcuLost = obdEcuLost,
        watchHeartRateBpm = watchBpm,
        watchBaselineBpm = watchBaseline,
        watchBaselineSampleCount = watchSamples,
        watchReadingAgeMs = watchAgeMs,
        watchSensorContact = watchContact,
    )

    private fun feed(
        engine: AccidentDetectionEngine,
        frames: List<SensorFrame>,
        mount: MountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
        obdConnected: Boolean = true,
    ): AccidentEngineOutput {
        var out: AccidentEngineOutput? = null
        frames.forEach { f ->
            out = engine.processFrame(
                frame = f,
                mountStatus = mount,
                accelAvailable = true,
                gyroAvailable = true,
                obdConnected = obdConnected && f.obdConnected,
            )
        }
        return out!!
    }

    // ---- FALSE POSITIVE SCENARIOS (1 through 15) ----

    @Test
    fun `01 picking up the phone while stationary does not trigger emergency SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.3f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 800),
                frame(1200L, fwd = 2f, lat = 3f, vert = 5f, mag = 6.2f, gyro = 95f, pitch = 42f, roll = 20f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 800),
                frame(1400L, fwd = 1f, lat = 2f, vert = 3f, mag = 3.8f, gyro = 85f, pitch = 55f, roll = 25f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 800),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
        assertTrue(out.assessment.suppressedAsPhoneHandling)
    }

    @Test
    fun `02 rotating the phone in hand while stationary does not trigger rollover or SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.2f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 0, obdConnected = false),
                frame(1250L, mag = 4.5f, gyro = 160f, pitch = 20f, roll = 78f, gpsSpeed = 0f, obdSpeed = null, rpm = null, obdConnected = false),
                frame(1500L, mag = 3.2f, gyro = 140f, pitch = 25f, roll = 85f, gpsSpeed = 0f, obdSpeed = null, rpm = null, obdConnected = false),
            ),
            obdConnected = false,
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
        assertTrue(out.assessment.suppressedAsPhoneHandling)
    }

    @Test
    fun `03 adjusting the phone mount while driving is suppressed and requests recalibration`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.6f, gpsSpeed = 45f),
                frame(1250L, fwd = 2f, lat = 3f, mag = 5.5f, gyro = 125f, roll = 48f, gpsSpeed = 44f),
                frame(1500L, fwd = 1f, lat = 2f, mag = 4.0f, gyro = 110f, roll = 52f, gpsSpeed = 44f),
            ),
            mount = MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED,
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
        assertTrue(out.assessment.recalibrationRecommended)
    }

    @Test
    fun `04 placing the phone firmly on a table (single spike) does not trigger SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.2f, gpsSpeed = 0f, obdConnected = false),
                frame(1050L, vert = 31f, mag = 31.5f, gyro = 20f, gpsSpeed = 0f, obdConnected = false),
                frame(1200L, mag = 0.1f, gyro = 1f, gpsSpeed = 0f, obdConnected = false),
            ),
            obdConnected = false,
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `05 closing a car door while parked does not trigger SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.2f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 800),
                frame(1040L, lat = 18f, vert = 16f, mag = 25.5f, gyro = 18f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 800),
                frame(1200L, mag = 0.3f, gyro = 2f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 800),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `06 driving over a speed bump does not trigger SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.5f, gpsSpeed = 30f),
                frame(1200L, fwd = 2f, vert = 26f, mag = 26.2f, gpsSpeed = 28f),
                frame(1350L, fwd = 1f, vert = 22f, mag = 22.1f, gpsSpeed = 27f),
                frame(1600L, mag = 0.8f, gpsSpeed = 28f),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `07 driving through a deep pothole without speed loss does not trigger SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.6f, gpsSpeed = 55f),
                frame(1150L, fwd = 3f, lat = 2f, vert = 33f, mag = 33.4f, gpsSpeed = 53f),
                frame(1300L, fwd = 1f, vert = 25f, mag = 25.2f, gpsSpeed = 52f),
                frame(1600L, mag = 1.0f, gpsSpeed = 52f),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `08 harsh braking is recorded as DRIVING_EVENT_ONLY and does not trigger emergency SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, fwd = -0.5f, mag = 0.5f, gpsSpeed = 70f, obdSpeed = 70f, rpm = 2400),
                frame(2500L, fwd = -7.2f, mag = 7.3f, gpsSpeed = 42f, obdSpeed = 42f, rpm = 1400),
                frame(2800L, fwd = -7.8f, mag = 7.9f, gpsSpeed = 24f, obdSpeed = 24f, rpm = 1000),
                frame(3100L, fwd = -6.8f, mag = 6.9f, gpsSpeed = 12f, obdSpeed = 12f, rpm = 850),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
        assertEquals(AccidentState.DRIVING_EVENT_ONLY, out.assessment.state)
    }

    @Test
    fun `09 sudden acceleration and 10 sharp cornering do not trigger emergency SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, fwd = 4.5f, lat = 4.2f, mag = 6.2f, gyro = 32f, gpsSpeed = 42f, gpsCourse = 90f),
                frame(1500L, fwd = 4.8f, lat = 4.6f, mag = 6.6f, gyro = 35f, gpsSpeed = 48f, gpsCourse = 115f),
                frame(2000L, fwd = 3.5f, lat = 4.4f, mag = 5.6f, gyro = 30f, gpsSpeed = 52f, gpsCourse = 145f),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `11 engine stalling and 12 stopping at traffic lights do not trigger emergency SMS`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, fwd = -2.0f, mag = 2.0f, gpsSpeed = 30f, obdSpeed = 30f, rpm = 1600),
                frame(3000L, fwd = -2.2f, mag = 2.2f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 0),
                frame(6000L, fwd = 0f, mag = 0.2f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 0),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    @Test
    fun `13 OBD disconnect, 14 watch disconnect, and 15 GPS loss never trigger emergency SMS alone`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.5f, gpsSpeed = 60f, obdSpeed = 60f, rpm = 2200, obdConnected = true, watchBpm = 78, watchBaseline = 76f, watchSamples = 20, watchAgeMs = 1000L, watchContact = true),
                frame(2000L, mag = 0.8f, gpsSpeed = null, acc = null, obdSpeed = null, rpm = null, obdConnected = false, obdEcuLost = true, watchBpm = null, watchAgeMs = 30_000L, watchContact = false),
                frame(3000L, mag = 0.7f, gpsSpeed = null, acc = null, obdSpeed = null, rpm = null, obdConnected = false, obdEcuLost = true, watchBpm = null, watchAgeMs = 31_000L, watchContact = false),
            ),
            obdConnected = false,
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
    }

    // ---- TRUE POSITIVE, FALLBACK, SWERVING & CANCELLATION SCENARIOS ----

    @Test
    fun `16 real impact sequence with independent GPS and OBD corroboration triggers POSSIBLE_ACCIDENT and suppresses duplicates`() {
        val engine = AccidentDetectionEngine()
        // Cruising at 68 km/h
        for (i in 0..3) {
            engine.processFrame(
                frame(t = i * 1000L, mag = 0.6f, gpsSpeed = 68f, obdSpeed = 68f, rpm = 2300),
                mountStatus = MountCalibrationStatus.CALIBRATED,
                accelAvailable = true,
                gyroAvailable = true,
                obdConnected = true,
            )
        }
        // Sustained 3.4g collision + abrupt speed drop + RPM drop
        engine.processFrame(
            frame(t = 5000L, fwd = -32f, lat = 9f, mag = 33.4f, gyro = 85f, gpsSpeed = 24f, obdSpeed = 22f, rpm = 400),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
        )
        // During the 5-second verification window, engine stays in VERIFYING and does NOT fire SMS prematurely
        val verifyingOut = engine.processFrame(
            frame(t = 5150L, fwd = -34f, lat = 8f, mag = 35.1f, gyro = 92f, gpsSpeed = 2f, obdSpeed = 0f, rpm = 0),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
        )
        assertFalse(verifyingOut.assessment.eventClass.triggersCountdown)
        assertEquals(AccidentState.VERIFYING, verifyingOut.assessment.state)

        // After 5s verification window with vehicle still stationary -> escalates to POSSIBLE_ACCIDENT
        val crashOut = engine.processFrame(
            frame(t = 10200L, mag = 0.2f, gyro = 1f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 0),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
        )
        assertTrue(crashOut.assessment.eventClass.triggersCountdown)
        assertEquals(AccidentState.POSSIBLE_ACCIDENT, crashOut.assessment.state)
        assertTrue(crashOut.assessment.independentGroupCount >= 2)
        assertTrue(crashOut.assessment.incidentId.isNotBlank())

        // Driver cancels -> state transitions to CANCELED and immediately suppresses retriggering
        engine.markCanceled(crashOut.assessment.incidentId, 10500L)
        assertEquals(AccidentState.CANCELED, engine.getCurrentState())

        val afterCancel = engine.processFrame(
            frame(t = 11000L, fwd = -30f, mag = 30.5f, gyro = 60f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 0),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = true,
        )
        assertFalse(afterCancel.assessment.eventClass.triggersCountdown)
        assertEquals(AccidentState.CANCELED, afterCancel.assessment.state)
    }

    @Test
    fun `severe event fallback requires calibrated mount, sustained severe impact, and post-impact stillness when independent sensors unavailable`() {
        val engine = AccidentDetectionEngine()
        // Sustained >4.0g impact with NO GPS/OBD/Watch, followed by 2.5s stillness
        engine.processFrame(
            frame(t = 1000L, fwd = -41f, lat = 6f, mag = 41.5f, gyro = 90f, gpsSpeed = null, acc = null, obdSpeed = null, rpm = null, obdConnected = false),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = false,
        )
        engine.processFrame(
            frame(t = 1200L, fwd = -42f, lat = 5f, mag = 42.3f, gyro = 88f, gpsSpeed = null, acc = null, obdSpeed = null, rpm = null, obdConnected = false),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = false,
        )
        engine.processFrame(
            frame(t = 2000L, mag = 0.2f, gyro = 1f, gpsSpeed = null, acc = null, obdSpeed = null, rpm = null, obdConnected = false),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = false,
        )
        val fallbackOut = engine.processFrame(
            frame(t = 4500L, mag = 0.1f, gyro = 1f, gpsSpeed = null, acc = null, obdSpeed = null, rpm = null, obdConnected = false),
            mountStatus = MountCalibrationStatus.CALIBRATED,
            accelAvailable = true,
            gyroAvailable = true,
            obdConnected = false,
        )
        assertTrue(fallbackOut.assessment.eventClass.triggersCountdown)
        assertEquals(AccidentState.SEVERE_EVENT_FALLBACK, fallbackOut.assessment.state)
        assertTrue(fallbackOut.assessment.isFallbackWarning)
    }

    @Test
    fun `erratic swerving preserves rolling evidence without triggering emergency SMS`() {
        val engine = AccidentDetectionEngine()
        val frames = listOf(
            frame(1000L, lat = 6.2f, mag = 6.2f, gyro = 48f, gpsSpeed = 58f, gpsCourse = 90f),
            frame(1400L, lat = -6.4f, mag = 6.4f, gyro = 52f, gpsSpeed = 56f, gpsCourse = 82f),
            frame(1800L, lat = 6.5f, mag = 6.5f, gyro = 55f, gpsSpeed = 54f, gpsCourse = 96f),
            frame(2200L, lat = -6.1f, mag = 6.1f, gyro = 50f, gpsSpeed = 52f, gpsCourse = 85f),
        )
        val outputs = frames.map { f ->
            engine.processFrame(
                frame = f,
                mountStatus = MountCalibrationStatus.CALIBRATED,
                accelAvailable = true,
                gyroAvailable = true,
                obdConnected = true,
            )
        }
        val lastOut = outputs.last()
        assertFalse("Swerving alone must never trigger emergency SMS", outputs.any { it.assessment.eventClass.triggersCountdown })
        assertEquals(SwerveBehaviorCategory.REPEATED_ERRATIC_SWERVING, lastOut.swerveAnalysis.category)
        assertTrue(lastOut.swerveAnalysis.shouldPreserveEvidence)
        assertNotNull("Rolling evidence must be captured once during erratic swerving", outputs.firstOrNull { it.evidenceSnapshot != null }?.evidenceSnapshot)
    }

    @Test
    fun `roundabout navigation is distinguished from erratic swerving`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, lat = 3.5f, mag = 3.5f, gyro = 28f, gpsSpeed = 28f, gpsCourse = 0f),
                frame(2000L, lat = 3.6f, mag = 3.6f, gyro = 30f, gpsSpeed = 27f, gpsCourse = 60f),
                frame(3000L, lat = 3.5f, mag = 3.5f, gyro = 29f, gpsSpeed = 26f, gpsCourse = 130f),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
        assertEquals(SwerveBehaviorCategory.ROUNDABOUT, out.swerveAnalysis.category)
        assertFalse(out.swerveAnalysis.shouldPreserveEvidence)
    }

    @Test
    fun `missing smartwatch data does not suppress a genuine confirmed collision`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.5f, gpsSpeed = 65f, obdSpeed = 65f, rpm = 2200, watchBpm = null, watchAgeMs = null),
                frame(3000L, fwd = -30f, mag = 31f, gyro = 80f, gpsSpeed = 18f, obdSpeed = 16f, rpm = 300, watchBpm = null, watchAgeMs = null),
                frame(3150L, fwd = -32f, mag = 33f, gyro = 85f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 0, watchBpm = null, watchAgeMs = null),
                frame(8200L, mag = 0.2f, gyro = 1f, gpsSpeed = 0f, obdSpeed = 0f, rpm = 0, watchBpm = null, watchAgeMs = null),
            ),
        )
        assertTrue(out.assessment.eventClass.triggersCountdown)
        assertEquals(AccidentState.POSSIBLE_ACCIDENT, out.assessment.state)
    }

    @Test
    fun `rough gravel road with recurring vertical peaks is classified as ROUGH_ROAD with zero false alarms`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, vert = 9.5f, fwd = 1.0f, mag = 9.6f, gpsSpeed = 35f, obdSpeed = 35f),
                frame(1400L, vert = -11.2f, fwd = 1.2f, mag = 11.3f, gpsSpeed = 34f, obdSpeed = 34f),
                frame(1800L, vert = 12.8f, fwd = 1.5f, mag = 12.9f, gpsSpeed = 34f, obdSpeed = 34f),
                frame(2300L, vert = -10.5f, fwd = 1.1f, mag = 10.6f, gpsSpeed = 33f, obdSpeed = 33f),
                frame(2800L, vert = 8.9f, fwd = 0.9f, mag = 9.0f, gpsSpeed = 33f, obdSpeed = 33f),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
        assertEquals(RoadEventClassification.ROUGH_ROAD, out.assessment.roadClassification)
    }

    @Test
    fun `5-second verification dismisses impact candidate when vehicle continues moving normally`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.6f, gpsSpeed = 45f, obdSpeed = 45f, rpm = 1800),
                // Strong bump/pothole candidate at 3000ms
                frame(3000L, fwd = 4f, lat = 3f, vert = 25f, mag = 25.5f, gyro = 18f, gpsSpeed = 42f, obdSpeed = 42f, rpm = 1750),
                frame(3150L, fwd = 3f, lat = 2f, vert = 24f, mag = 24.3f, gyro = 15f, gpsSpeed = 41f, obdSpeed = 41f, rpm = 1720),
                // Vehicle continues driving normally over the 5s window
                frame(6500L, mag = 0.8f, gpsSpeed = 43f, obdSpeed = 43f, rpm = 1800),
                frame(8200L, mag = 0.7f, gpsSpeed = 44f, obdSpeed = 44f, rpm = 1820),
            ),
        )
        assertFalse(out.assessment.eventClass.triggersCountdown)
        assertEquals(AccidentState.DISMISSED_ROAD_EVENT, out.assessment.state)
    }

    @Test
    fun `severe collision is retained after 5s verification even if vehicle keeps rolling`() {
        val out = feed(
            AccidentDetectionEngine(),
            listOf(
                frame(1000L, mag = 0.6f, gpsSpeed = 75f, obdSpeed = 75f, rpm = 2500),
                frame(3000L, fwd = -39f, lat = 10f, mag = 40.3f, gyro = 75f, gpsSpeed = 30f, obdSpeed = 28f, rpm = 600),
                frame(3150L, fwd = -40f, lat = 9f, mag = 41.0f, gyro = 78f, gpsSpeed = 20f, obdSpeed = 19f, rpm = 500),
                // Vehicle still rolling slowly at 16 km/h after 5s
                frame(7000L, mag = 0.5f, gpsSpeed = 16f, obdSpeed = 16f, rpm = 450),
                frame(8200L, mag = 0.4f, gpsSpeed = 15f, obdSpeed = 15f, rpm = 450),
            ),
        )
        assertTrue(out.assessment.eventClass.triggersCountdown)
        assertEquals(AccidentState.POSSIBLE_ACCIDENT, out.assessment.state)
    }
}
