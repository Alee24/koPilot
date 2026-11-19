package ke.co.kopilot.feature.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTaskSchedulerTest {

    @Test
    fun `normal thermal mode schedules Tier 1 road signs and Tier 2 traffic lights every frame`() {
        val scheduler = VisionTaskScheduler()

        val oddFrame = scheduler.decideSchedule(
            frameIndex = 1,
            frameAgeMs = 15L,
            lastFrameLatencyMs = 85L,
            thermalMode = VisionThermalMode.NORMAL,
            hasSignRois = true,
        )
        assertTrue(oddFrame.runRoadSignYolo)
        assertTrue(oddFrame.runYoloHorizonZoomPass)
        assertTrue(oddFrame.runSignRoiExtractionAndOcr)
        assertTrue(oddFrame.runTrafficAndBrakeLightAnalysis)
        assertTrue(oddFrame.runObjectDetection)
        assertTrue(oddFrame.runFullFrameOcr)
        assertFalse(oddFrame.runDedicatedPlateOcr)
        assertNull(oddFrame.thermalWarningBanner)

        val evenFrame = scheduler.decideSchedule(
            frameIndex = 2,
            frameAgeMs = 15L,
            lastFrameLatencyMs = 85L,
            thermalMode = VisionThermalMode.NORMAL,
            hasSignRois = true,
        )
        assertTrue(evenFrame.runRoadSignYolo)
        assertTrue(evenFrame.runSignRoiExtractionAndOcr)
        assertFalse(evenFrame.runFullFrameOcr)
        assertTrue(evenFrame.runDedicatedPlateOcr)
    }

    @Test
    fun `frame latency budget guard defers Tier 4 plate OCR when previous frame exceeded budget`() {
        val scheduler = VisionTaskScheduler(heavyTaskLatencyBudgetMs = 175L)

        val overloadedFrame = scheduler.decideSchedule(
            frameIndex = 2,
            frameAgeMs = 20L,
            lastFrameLatencyMs = 230L, // > 175 ms budget
            thermalMode = VisionThermalMode.NORMAL,
            hasSignRois = true,
        )
        // Tier 1 Road-Sign & Speed-Limit recognition must stay active
        assertTrue(overloadedFrame.runRoadSignYolo)
        assertTrue(overloadedFrame.runSignRoiExtractionAndOcr)
        // Tier 4 Plate OCR is deferred to prevent queue buildup
        assertFalse(overloadedFrame.runDedicatedPlateOcr)
    }

    @Test
    fun `warm thermal mode keeps Tier 1 road signs at 100 percent and paces Tier 3 and Tier 4`() {
        val scheduler = VisionTaskScheduler()

        val f1 = scheduler.decideSchedule(1, 10L, 90L, VisionThermalMode.WARM, hasSignRois = true)
        val f2 = scheduler.decideSchedule(2, 10L, 90L, VisionThermalMode.WARM, hasSignRois = true)
        val f3 = scheduler.decideSchedule(3, 10L, 90L, VisionThermalMode.WARM, hasSignRois = true)
        val f4 = scheduler.decideSchedule(4, 10L, 90L, VisionThermalMode.WARM, hasSignRois = true)

        // Tier 1 and Tier 2 always active
        listOf(f1, f2, f3, f4).forEach {
            assertTrue(it.runRoadSignYolo)
            assertTrue(it.runYoloHorizonZoomPass)
            assertTrue(it.runSignRoiExtractionAndOcr)
            assertTrue(it.runTrafficAndBrakeLightAnalysis)
        }
        // Tier 3 objects run on even frames (50%)
        assertFalse(f1.runObjectDetection)
        assertTrue(f2.runObjectDetection)
        // Tier 4 full OCR on frame % 4 == 1, plate OCR on frame % 4 == 3
        assertTrue(f1.runFullFrameOcr)
        assertFalse(f1.runDedicatedPlateOcr)
        assertTrue(f3.runDedicatedPlateOcr)
    }

    @Test
    fun `hot thermal mode switches YOLO11n to single-pass letterbox and emits warning banner`() {
        val scheduler = VisionTaskScheduler()

        val hotSchedule = scheduler.decideSchedule(
            frameIndex = 1,
            frameAgeMs = 15L,
            lastFrameLatencyMs = 110L,
            thermalMode = VisionThermalMode.HOT,
            hasSignRois = true,
        )
        assertTrue(hotSchedule.runRoadSignYolo)
        assertFalse(hotSchedule.runYoloHorizonZoomPass) // Single-pass halves neural compute
        assertTrue(hotSchedule.runSignRoiExtractionAndOcr)
        assertNotNull(hotSchedule.thermalWarningBanner)
    }

    @Test
    fun `critical thermal mode suspends heavy Tier 4 OCR and shortens stale detection validity window`() {
        val scheduler = VisionTaskScheduler()

        val critEven = scheduler.decideSchedule(
            frameIndex = 2,
            frameAgeMs = 15L,
            lastFrameLatencyMs = 110L,
            thermalMode = VisionThermalMode.CRITICAL,
            hasSignRois = true,
        )
        assertTrue(critEven.runRoadSignYolo)
        assertTrue(critEven.runSignRoiExtractionAndOcr)
        assertFalse(critEven.runObjectDetection)
        assertFalse(critEven.runFullFrameOcr)
        assertFalse(critEven.runDedicatedPlateOcr)
        assertEquals(8_000L, critEven.maxDetectionValidityMs)
        assertNotNull(critEven.thermalWarningBanner)
    }

    @Test
    fun `recordFrameTimings computes accurate Avg P50 P95 P99 latency percentiles`() {
        val scheduler = VisionTaskScheduler()
        val samples = listOf(40L, 50L, 60L, 70L, 80L, 90L, 100L, 120L, 160L, 230L)
        var lastMetrics = VisionPerformanceMetrics()

        samples.forEachIndexed { idx, latency ->
            val sched = scheduler.decideSchedule(idx + 1, 10L, latency, VisionThermalMode.NORMAL, true)
            lastMetrics = scheduler.recordFrameTimings(
                nowMillis = 1000L + idx * 120L,
                thermalMode = VisionThermalMode.NORMAL,
                totalFrameMs = latency,
                yoloMs = 32L,
                roiOcrMs = 18L,
                fullOcrMs = 25L,
                plateOcrMs = 20L,
                objectDetectMs = 15L,
                trafficLightMs = 4L,
                laneMs = 5L,
                schedule = sched,
            )
        }

        assertEquals(10, lastMetrics.totalFramesAnalyzed)
        assertEquals(100.0f, lastMetrics.avgTotalLatencyMs, 0.5f)
        assertTrue(lastMetrics.p50TotalLatencyMs in 80f..90f)
        assertTrue(lastMetrics.p95TotalLatencyMs >= 160f)
        assertEquals(230f, lastMetrics.p99TotalLatencyMs, 0.5f)
        assertEquals(7, lastMetrics.moduleTelemetry.size)
    }
}
