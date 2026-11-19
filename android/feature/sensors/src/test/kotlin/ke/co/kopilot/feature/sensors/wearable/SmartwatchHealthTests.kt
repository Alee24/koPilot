package ke.co.kopilot.feature.sensors.wearable

import ke.co.kopilot.core.database.entity.SleepSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleHeartRateParserTest {

    @Test
    fun `decodes standard 8-bit heart rate with sensor contact detected`() {
        // Flags: 0x06 (0000_0110) => 8-bit HR, Sensor Contact supported & detected
        val bytes = byteArrayOf(0x06, 74)
        val res = BleHeartRateParser.decodeHeartRateMeasurement(bytes)
        assertTrue(res.isValid)
        assertFalse(res.is16BitFormat)
        assertEquals(74, res.bpm)
        assertTrue(res.sensorContactSupported)
        assertEquals(true, res.sensorContactDetected)
        assertNull(res.energyExpendedKj)
        assertTrue(res.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `decodes 16-bit heart rate with energy expended and RR intervals`() {
        // Flags: 0x1F (0001_1111) => 16-bit HR, Contact supported & detected, Energy present, RR present
        // BPM = 132 (0x84, 0x00), Energy = 420 kJ (0xA4, 0x01), RR = 819 ticks (~800 ms: 0x33, 0x03)
        val bytes = byteArrayOf(
            0x1F,
            0x84.toByte(), 0x00,
            0xA4.toByte(), 0x01,
            0x33, 0x03,
        )
        val res = BleHeartRateParser.decodeHeartRateMeasurement(bytes)
        assertTrue(res.isValid)
        assertTrue(res.is16BitFormat)
        assertEquals(132, res.bpm)
        assertEquals(420, res.energyExpendedKj)
        assertEquals(1, res.rrIntervalsMs.size)
        assertEquals(800, res.rrIntervalsMs.first())
    }

    @Test
    fun `marks reading invalid when watch reports no skin contact`() {
        // Flags: 0x04 (0000_0100) => 8-bit HR, Sensor Contact supported but NOT detected
        val bytes = byteArrayOf(0x04, 72)
        val res = BleHeartRateParser.decodeHeartRateMeasurement(bytes)
        assertFalse(res.isValid)
        assertTrue(res.sensorContactSupported)
        assertEquals(false, res.sensorContactDetected)
        assertNotNull(res.invalidReason)
    }

    @Test
    fun `rejects out-of-range BPM values`() {
        val zeroBpm = BleHeartRateParser.decodeHeartRateMeasurement(byteArrayOf(0x00, 0))
        assertFalse(zeroBpm.isValid)
        val extremeBpm = BleHeartRateParser.decodeHeartRateMeasurement(byteArrayOf(0x00, 250.toByte()))
        assertFalse(extremeBpm.isValid)
    }

    @Test
    fun `decodes body sensor location`() {
        assertEquals("Chest", BleHeartRateParser.decodeBodySensorLocation(byteArrayOf(1)))
        assertEquals("Wrist", BleHeartRateParser.decodeBodySensorLocation(byteArrayOf(2)))
    }
}

class SleepAnalyticsCalculatorTest {

    @Test
    fun `computes sleep stages, efficiency, and interruptions accurately`() {
        val base = 1_700_000_000_000L
        val min = 60_000L
        // Total in bed: 420 min (7h)
        // Stages: 120m Light, 90m Deep, 15m Awake (interruption), 90m REM, 105m Light
        val stages = listOf(
            RawSleepStageInput(SleepAnalyticsCalculator.STAGE_LIGHT, base, base + 120 * min),
            RawSleepStageInput(SleepAnalyticsCalculator.STAGE_DEEP, base + 120 * min, base + 210 * min),
            RawSleepStageInput(SleepAnalyticsCalculator.STAGE_AWAKE, base + 210 * min, base + 225 * min),
            RawSleepStageInput(SleepAnalyticsCalculator.STAGE_REM, base + 225 * min, base + 315 * min),
            RawSleepStageInput(SleepAnalyticsCalculator.STAGE_LIGHT, base + 315 * min, base + 420 * min),
        )

        val metrics = SleepAnalyticsCalculator.computeSessionMetrics(base, base + 420 * min, stages)
        assertTrue(metrics.stagesAvailable)
        assertEquals(420, metrics.timeInBedMinutes)
        assertEquals(405, metrics.totalSleepDurationMinutes)
        assertEquals(90, metrics.deepSleepMinutes)
        assertEquals(225, metrics.lightSleepMinutes)
        assertEquals(90, metrics.remSleepMinutes)
        assertEquals(15, metrics.awakeMinutes)
        assertEquals(1, metrics.interruptionsCount)
        assertEquals(96, metrics.sleepEfficiencyPercent)
    }

    @Test
    fun `distinguishes unavailable sleep stages from zero duration`() {
        val base = 1_700_000_000_000L
        val end = base + 360 * 60_000L // 6h duration-only session
        val metrics = SleepAnalyticsCalculator.computeSessionMetrics(base, end, emptyList())

        assertFalse(metrics.stagesAvailable)
        assertEquals(360, metrics.totalSleepDurationMinutes)
        assertNull(metrics.deepSleepMinutes)
        assertNull(metrics.lightSleepMinutes)
        assertNull(metrics.remSleepMinutes)
        assertNull(metrics.awakeMinutes)
    }

    @Test
    fun `computes 7-day sleep trend and non-diagnostic pre-drive context`() {
        val now = 1_700_000_000_000L
        val sessions = listOf(
            SleepSessionEntity(
                sessionId = "s1",
                startTimeMillis = now - 8 * 3600_000L,
                endTimeMillis = now,
                timeZoneOffsetSeconds = 10800,
                totalSleepDurationMinutes = 320, // 5h 20m
                timeInBedMinutes = 340,
                sleepEfficiencyPercent = 94,
                deepSleepMinutes = 65,
                lightSleepMinutes = 190,
                remSleepMinutes = 65,
                awakeMinutes = 20,
                interruptionsCount = 1,
                stagesAvailable = true,
                sourcePackage = "com.sec.android.app.shealth",
                sourceAppName = "Samsung Health",
                title = null,
                notes = null,
                syncedAtMillis = now,
            ),
            SleepSessionEntity(
                sessionId = "s2",
                startTimeMillis = now - 32 * 3600_000L,
                endTimeMillis = now - 24 * 3600_000L,
                timeZoneOffsetSeconds = 10800,
                totalSleepDurationMinutes = 440, // 7h 20m
                timeInBedMinutes = 450,
                sleepEfficiencyPercent = 98,
                deepSleepMinutes = 90,
                lightSleepMinutes = 250,
                remSleepMinutes = 100,
                awakeMinutes = 10,
                interruptionsCount = 0,
                stagesAvailable = true,
                sourcePackage = "com.sec.android.app.shealth",
                sourceAppName = "Samsung Health",
                title = null,
                notes = null,
                syncedAtMillis = now,
            ),
        )

        val trend = SleepAnalyticsCalculator.computeSevenDayTrend(sessions)
        assertEquals(2, trend.sessionsCountLast7Days)
        assertEquals(380, trend.averageDurationMinutesLast7Days)
        assertEquals(320, trend.shortestDurationMinutesLast7Days)
        assertEquals(440, trend.longestDurationMinutesLast7Days)
        assertEquals("Last recorded sleep: 5h 20m (Source: Samsung Health)", trend.preDriveContextText)
    }
}
