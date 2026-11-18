package ke.co.kopilot.feature.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealTimeDrivingIntelligenceTest {

    @Test
    fun harshBraking_updatesScoresGeneratesAdviceAndRecordsEvent() {
        val engine = RealTimeDrivingIntelligence()
        engine.resetForNewJourney(1_000L)

        val event = engine.update(
            RealTimeIntelligenceInput(
                nowMillis = 1_500L,
                isJourneyActive = true,
                motionAnalysis = DrivingMotionAnalysis(
                    longitudinalState = LongitudinalMotionState.HARSH_BRAKING,
                    longitudinalAccelMps2 = -5.1f,
                ),
                gpsSpeedKmh = 52f,
                speedLimitKmh = 60,
                obdConnected = false,
                obdRpm = null,
                obdThrottlePct = null,
                obdEngineLoadPct = null,
                obdMafGramsPerSec = null,
            ),
        )

        assertNotNull(event)
        assertEquals(DrivingEventType.HARSH_BRAKING, event!!.type)
        val state = engine.state.value
        assertEquals(1, state.harshBrakeCount)
        assertTrue("Safety score should drop below 100", state.safetyScore < 100)
        assertTrue("Fuel score should drop below 100", state.fuelEfficiencyScore < 100)
        assertTrue(state.coachingAdvice.any { it.message.contains("harsh braking", ignoreCase = true) })
    }

    @Test
    fun overspeeding_requiresConfirmationWindowBeforeTriggering() {
        val engine = RealTimeDrivingIntelligence()
        engine.resetForNewJourney(1_000L)

        // First sample over limit: should NOT trigger immediately
        val t1 = engine.update(
            RealTimeIntelligenceInput(
                nowMillis = 2_000L,
                isJourneyActive = true,
                motionAnalysis = DrivingMotionAnalysis(),
                gpsSpeedKmh = 72f,
                speedLimitKmh = 50,
                obdConnected = false,
                obdRpm = null,
                obdThrottlePct = null,
                obdEngineLoadPct = null,
                obdMafGramsPerSec = null,
            ),
        )
        assertNull(t1)

        // Sustained > 3000ms over limit: should confirm
        val t2 = engine.update(
            RealTimeIntelligenceInput(
                nowMillis = 5_500L,
                isJourneyActive = true,
                motionAnalysis = DrivingMotionAnalysis(),
                gpsSpeedKmh = 74f,
                speedLimitKmh = 50,
                obdConnected = false,
                obdRpm = null,
                obdThrottlePct = null,
                obdEngineLoadPct = null,
                obdMafGramsPerSec = null,
            ),
        )
        assertNotNull(t2)
        assertEquals(DrivingEventType.EXCESSIVE_SPEED, t2!!.type)
        assertEquals(1, engine.state.value.overspeedEventCount)
    }

    @Test
    fun highRpmObd_andIdling_reduceFuelEfficiencyScore() {
        val engine = RealTimeDrivingIntelligence()
        engine.resetForNewJourney(1_000L)

        // High RPM sustained >= 1800ms
        engine.update(
            RealTimeIntelligenceInput(
                nowMillis = 2_000L,
                isJourneyActive = true,
                motionAnalysis = DrivingMotionAnalysis(),
                gpsSpeedKmh = 40f,
                speedLimitKmh = 60,
                obdConnected = true,
                obdRpm = 3_900,
                obdThrottlePct = 78f,
                obdEngineLoadPct = 88f,
                obdMafGramsPerSec = 25f,
            ),
        )
        val highRpmEvent = engine.update(
            RealTimeIntelligenceInput(
                nowMillis = 4_000L,
                isJourneyActive = true,
                motionAnalysis = DrivingMotionAnalysis(),
                gpsSpeedKmh = 42f,
                speedLimitKmh = 60,
                obdConnected = true,
                obdRpm = 4_100,
                obdThrottlePct = 80f,
                obdEngineLoadPct = 90f,
                obdMafGramsPerSec = 27f,
            ),
        )
        assertNotNull(highRpmEvent)
        assertEquals(DrivingEventType.HIGH_RPM_DRIVING, highRpmEvent!!.type)
        assertTrue(engine.state.value.fuelEfficiencyScore < 100)
    }
}
