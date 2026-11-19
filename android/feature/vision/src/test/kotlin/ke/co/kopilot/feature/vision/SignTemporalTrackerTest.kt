package ke.co.kopilot.feature.vision

import ke.co.kopilot.core.common.model.TrafficSignCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignTemporalTrackerTest {

    @Test
    fun `requires two confident frames with grace miss for medium confidence and suppresses duplicates`() {
        val tracker = SignTemporalTracker()
        val det = listOf(RawSignDetection(TrafficSignCategory.SPEED_LIMIT_50, 0.78f, 0.45f))

        assertNull(tracker.processFrame(det, "1.0.0", nowMillis = 1_000L))
        // 1 blurred frame miss should not reset
        assertNull(tracker.processFrame(emptyList(), "1.0.0", nowMillis = 1_200L))
        val confirmed = tracker.processFrame(det, "1.0.0", nowMillis = 1_400L)
        assertNotNull(confirmed)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_50, confirmed?.category)
        assertEquals(true, confirmed?.applicableToDriverLane)

        // Immediate next frame within 20s cooldown is suppressed
        assertNull(tracker.processFrame(det, "1.0.0", nowMillis = 1_600L))
    }

    @Test
    fun `confirms high confidence sign on first frame across full camera view without exclusion zones`() {
        val tracker = SignTemporalTracker()
        // Far-left edge of camera view (normalizedCenterX = 0.04f) must NOT be excluded
        val detLeft = listOf(RawSignDetection(TrafficSignCategory.SPEED_LIMIT_50, 0.88f, 0.04f))
        val confirmedLeft = tracker.processFrame(detLeft, "1.0.0", nowMillis = 1_000L)
        assertNotNull(confirmedLeft)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_50, confirmedLeft?.category)
        assertEquals(true, confirmedLeft?.applicableToDriverLane)

        // Far-right edge of camera view (normalizedCenterX = 0.94f) must also be confirmed
        val detRight = listOf(RawSignDetection(TrafficSignCategory.STOP, 0.90f, 0.94f))
        val confirmedRight = tracker.processFrame(detRight, "1.0.0", nowMillis = 1_100L)
        assertNotNull(confirmedRight)
        assertEquals(TrafficSignCategory.STOP, confirmedRight?.category)
        assertEquals(true, confirmedRight?.applicableToDriverLane)
    }

    @Test
    fun `tracks multiple simultaneous signs across frames without resetting competing streaks`() {
        val tracker = SignTemporalTracker()
        // Frame 1: both SPEED_LIMIT_60 (0.76) and SPEED_BUMP (0.79) appear
        val frame1 = listOf(
            RawSignDetection(TrafficSignCategory.SPEED_LIMIT_60, 0.76f, 0.25f),
            RawSignDetection(TrafficSignCategory.SPEED_BUMP, 0.79f, 0.30f),
        )
        assertNull(tracker.processFrame(frame1, "1.0.0", nowMillis = 2_000L))

        // Frame 2: both appear again; SPEED_LIMIT_60 is prioritized and confirmed on frame 2
        val confirmedFirst = tracker.processFrame(frame1, "1.0.0", nowMillis = 2_120L)
        assertNotNull(confirmedFirst)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_60, confirmedFirst?.category)

        // Frame 3: SPEED_LIMIT_60 is now in 20s dedup cooldown, so SPEED_BUMP confirms immediately on the next frame
        val confirmedSecond = tracker.processFrame(frame1, "1.0.0", nowMillis = 2_240L)
        assertNotNull(confirmedSecond)
        assertEquals(TrafficSignCategory.SPEED_BUMP, confirmedSecond?.category)
        assertTrue((confirmedSecond?.consecutiveFrames ?: 0) >= 2)
    }
}
