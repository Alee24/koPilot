package ke.co.kopilot.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuPerformanceEngineTest {

    private val engine = EcuPerformanceEngine()

    @Test
    fun `empty samples returns unavailable metrics without crashing`() {
        val result = engine.analyze(samples = emptyList(), distanceKm = 0.0)
        assertEquals(0, result.sampleCount)
        assertEquals("Unavailable", result.fuelProvenance)
        assertNull(result.litersPer100Km)
        assertNull(result.kmPerLiter)
        assertNull(result.performanceScore)
        assertTrue(result.speedBuckets.isEmpty())
        assertTrue(result.insights.isEmpty())
    }

    @Test
    fun `stationary idling never computes liters per 100km`() {
        val samples = (0 until 15).map { i ->
            EcuSample(
                monotonicMillis = i * 1000L,
                timestampMillis = 1_700_000_000_000L + i * 1000L,
                rpm = 735,
                obdSpeedKmh = 0f,
                coolantTempC = 86,
                engineLoadPct = 18f,
                ecuFuelRateLph = 0.72f,
            )
        }
        val result = engine.analyze(samples = samples, distanceKm = 0.0)
        assertEquals("ECU Fuel Rate (PID 015E)", result.fuelProvenance)
        assertNull(result.litersPer100Km)
        assertNull(result.kmPerLiter)
        assertNotNull(result.idleFuelLiters)
        assertTrue((result.idleFuelLiters ?: 0.0) > 0.0)
    }

    @Test
    fun `prefers ecu fuel rate 015E over maf estimate and falls back to maf when 015E absent`() {
        val ecuSamples = (0 until 10).map { i ->
            EcuSample(
                monotonicMillis = i * 1000L,
                timestampMillis = 1_700_000_000_000L + i * 1000L,
                rpm = 1900,
                obdSpeedKmh = 55f,
                ecuFuelRateLph = 4.5f,
                mafGramsPerSec = 10.0f,
            )
        }
        val ecuResult = engine.analyze(samples = ecuSamples, distanceKm = 5.0)
        assertEquals("ECU Fuel Rate (PID 015E)", ecuResult.fuelProvenance)

        val mafSamples = (0 until 10).map { i ->
            EcuSample(
                monotonicMillis = i * 1000L,
                timestampMillis = 1_700_000_000_000L + i * 1000L,
                rpm = 1900,
                obdSpeedKmh = 55f,
                ecuFuelRateLph = null,
                mafGramsPerSec = 10.0f,
            )
        }
        val mafResult = engine.analyze(samples = mafSamples, distanceKm = 5.0)
        assertEquals("MAF-Based Estimate (14.7:1 AFR)", mafResult.fuelProvenance)
        assertNotNull(mafResult.litersPer100Km)
    }

    @Test
    fun `selects optimal cruising speed bucket within speed limit cap`() {
        val samples = mutableListOf<EcuSample>()
        var t = 0L
        // 10 samples at 35 km/h (high consumption 8.0 L/h => ~22 L/100km)
        repeat(10) {
            samples += EcuSample(
                monotonicMillis = t,
                timestampMillis = 1_700_000_000_000L + t,
                rpm = 2200,
                obdSpeedKmh = 35f,
                engineLoadPct = 42f,
                ecuFuelRateLph = 4.2f,
            )
            t += 1000L
        }
        // 12 samples at 55 km/h (best efficiency 3.3 L/h => 6.0 L/100km)
        repeat(12) {
            samples += EcuSample(
                monotonicMillis = t,
                timestampMillis = 1_700_000_000_000L + t,
                rpm = 1850,
                obdSpeedKmh = 55f,
                engineLoadPct = 28f,
                ecuFuelRateLph = 3.3f,
            )
            t += 1000L
        }
        // 12 samples at 110 km/h (above speedLimitCapKmh = 100)
        repeat(12) {
            samples += EcuSample(
                monotonicMillis = t,
                timestampMillis = 1_700_000_000_000L + t,
                rpm = 3100,
                obdSpeedKmh = 110f,
                engineLoadPct = 55f,
                ecuFuelRateLph = 5.5f,
            )
            t += 1000L
        }

        val result = engine.analyze(
            samples = samples,
            distanceKm = 8.0,
            speedLimitCapKmh = 100,
        )
        assertEquals("50–60 km/h", result.recommendedCruisingRange)
        assertTrue(result.speedBuckets.any { it.label == "50–60 km/h" && it.sampleCount == 12 })
    }

    @Test
    fun `detects uphill cvt rpm load and avoids fixed gear assumptions`() {
        val samples = (0 until 15).map { i ->
            EcuSample(
                monotonicMillis = i * 1000L,
                timestampMillis = 1_700_000_000_000L + i * 1000L,
                rpm = 3650,
                obdSpeedKmh = 50f,
                engineLoadPct = 78f,
                gradientPct = 6.5f,
                ecuFuelRateLph = 6.8f,
            )
        }
        val result = engine.analyze(
            samples = samples,
            distanceKm = 3.5,
            isCvtVehicle = true,
        )
        assertEquals(15, result.uphillSampleCount)
        assertEquals(3650, result.uphillAvgRpm)
        assertNotNull(result.uphillAvgLoadPct)
        assertTrue(result.insights.any { it.title.contains("CVT", ignoreCase = true) || it.evidence.contains("uphill", ignoreCase = true) })
    }

    @Test
    fun `tracks coolant warmup time and high temperature excursions above 102C`() {
        val samples = listOf(
            EcuSample(0L, 1_700_000_000_000L, rpm = 1200, obdSpeedKmh = 30f, coolantTempC = 45),
            EcuSample(60_000L, 1_700_000_060_000L, rpm = 1800, obdSpeedKmh = 50f, coolantTempC = 65),
            EcuSample(120_000L, 1_700_000_120_000L, rpm = 1900, obdSpeedKmh = 55f, coolantTempC = 76),
            EcuSample(180_000L, 1_700_000_180_000L, rpm = 2400, obdSpeedKmh = 60f, coolantTempC = 104),
            EcuSample(240_000L, 1_700_000_240_000L, rpm = 2500, obdSpeedKmh = 60f, coolantTempC = 105),
        )
        val result = engine.analyze(samples = samples, distanceKm = 3.0)
        assertEquals(120L, result.warmupSecondsTo75C)
        assertEquals(2, result.excursionCountAbove102C)
        assertTrue(result.insights.any { it.title.contains("Temperature", ignoreCase = true) && it.inspectionAppropriate })
    }

    @Test
    fun `flags persistent fuel trim deviation and multi-trip baseline regression`() {
        val samples = (0 until 15).map { i ->
            EcuSample(
                monotonicMillis = i * 1000L,
                timestampMillis = 1_700_000_000_000L + i * 1000L,
                rpm = 2600,
                obdSpeedKmh = 60f,
                coolantTempC = 88,
                engineLoadPct = 35f,
                ltftPct = 14.5f,
                stftPct = 3.2f,
                ecuFuelRateLph = 5.4f,
            )
        }
        val baseline = EcuVehicleBaseline(
            tripCount = 6,
            avgRpm = 1950,
            avgLoadPct = 32f,
            avgCoolantC = 87,
            avgLitersPer100Km = 6.8,
            avgLtftPct = 1.5f,
        )
        val result = engine.analyze(
            samples = samples,
            distanceKm = 3.0,
            baseline = baseline,
        )
        assertTrue(result.insights.any { it.title.contains("Fuel Trim", ignoreCase = true) && it.inspectionAppropriate })
        assertTrue(result.insights.any { it.title.contains("Baseline", ignoreCase = true) })
    }
}
