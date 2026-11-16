package ke.co.kopilot.feature.vision

import ke.co.kopilot.core.common.model.TrafficSignCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        // Immediate next frame within 20s cooldown is suppressed
        assertNull(tracker.processFrame(det, "1.0.0", nowMillis = 1_600L))
    }

    @Test
    fun `confirms high confidence sign on first frame and suppresses duplicates`() {
        val tracker = SignTemporalTracker()
        val det = listOf(RawSignDetection(TrafficSignCategory.SPEED_LIMIT_50, 0.88f, 0.45f))

        val confirmed = tracker.processFrame(det, "1.0.0", nowMillis = 1_000L)
        assertNotNull(confirmed)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_50, confirmed?.category)

        // Immediate next frame within 20s cooldown is suppressed
        assertNull(tracker.processFrame(det, "1.0.0", nowMillis = 1_200L))
    }
}
