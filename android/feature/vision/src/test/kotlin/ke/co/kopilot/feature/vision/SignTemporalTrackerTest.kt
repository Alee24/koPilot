package ke.co.kopilot.feature.vision

import ke.co.kopilot.core.common.model.TrafficSignCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignTemporalTrackerTest {

    @Test
    fun `requires three consecutive confident frames and suppresses duplicates`() {
        val tracker = SignTemporalTracker()
        val det = listOf(RawSignDetection(TrafficSignCategory.SPEED_LIMIT_50, 0.88f, 0.45f))

        assertNull(tracker.processFrame(det, "1.0.0", nowMillis = 1_000L))
        assertNull(tracker.processFrame(det, "1.0.0", nowMillis = 1_200L))
        val confirmed = tracker.processFrame(det, "1.0.0", nowMillis = 1_400L)
        assertNotNull(confirmed)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_50, confirmed?.category)

        // Immediate 4th frame within 20s cooldown is suppressed
        assertNull(tracker.processFrame(det, "1.0.0", nowMillis = 1_600L))
    }
}
