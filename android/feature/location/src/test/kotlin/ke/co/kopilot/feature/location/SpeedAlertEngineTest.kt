package ke.co.kopilot.feature.location

import ke.co.kopilot.core.common.model.GpsFix
import ke.co.kopilot.core.common.model.GpsSignalState
import ke.co.kopilot.core.common.model.SpeedAlertDecision
import ke.co.kopilot.core.common.model.TrafficSignCategory
import ke.co.kopilot.core.common.model.TrafficSignObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedAlertEngineTest {

    private val engine = SpeedAlertEngine()

    @Test
    fun `stale gps fix returns speed data unavailable`() {
        val now = 100_000L
        val fix = GpsFix(
            latitude = -1.2921,
            longitude = 36.8219,
            speedMps = 20f,
            speedAccuracyMps = 1f,
            horizontalAccuracyMeters = 8f,
            bearingDegrees = 90f,
            timestampMillis = now - 10_000L,
        )
        val result = engine.evaluate(fix, null, thresholdKmh = 5, nowMillis = now)
        assertEquals(SpeedAlertDecision.SPEED_DATA_UNAVAILABLE, result.decision)
        assertEquals(GpsSignalState.UNAVAILABLE, result.signalState)
    }

    @Test
    fun `overspeed triggers warning with hysteresis and cooldown`() {
        engine.reset()
        val now = 200_000L
        val sign = TrafficSignObservation(
            id = "s1",
            category = TrafficSignCategory.SPEED_LIMIT_50,
            confidence = 0.9f,
            consecutiveFrames = 4,
            applicableToDriverLane = true,
            timestampMillis = now - 2_000L,
            modelVersion = "1.0.0",
        )
        // 65 km/h (~18.06 m/s) with 1 m/s (~3.6 km/h) uncertainty => 61.4 > 50 + 5
        val fastFix = GpsFix(
            latitude = -1.2921,
            longitude = 36.8219,
            speedMps = 18.06f,
            speedAccuracyMps = 1.0f,
            horizontalAccuracyMeters = 6f,
            bearingDegrees = 90f,
            timestampMillis = now,
        )
        val first = engine.evaluate(fastFix, sign, thresholdKmh = 5, nowMillis = now)
        assertEquals(SpeedAlertDecision.POSSIBLE_OVERSPEED_WARNING, first.decision)

        // Immediate next second should be suppressed by cooldown
        val second = engine.evaluate(fastFix.copy(timestampMillis = now + 2_000L), sign, thresholdKmh = 5, nowMillis = now + 2_000L)
        assertEquals(SpeedAlertDecision.NO_ALERT, second.decision)
    }
}
