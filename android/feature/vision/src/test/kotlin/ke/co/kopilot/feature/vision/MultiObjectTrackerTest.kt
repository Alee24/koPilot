package ke.co.kopilot.feature.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MultiObjectTrackerTest {

    @Test
    fun `preserves tracking ID across overlapping frames and assigns new ID to distant object`() {
        val tracker = MultiObjectTracker()
        val f1 = tracker.update(
            listOf(RawObjectDetection("Vehicle", 0.88f, 0.20f, 0.30f, 0.40f, 0.60f)),
            timestampMillis = 1_000L,
        )
        assertEquals(1, f1.size)
        val id1 = f1.first().trackingId

        val f2 = tracker.update(
            listOf(
                RawObjectDetection("Vehicle", 0.90f, 0.22f, 0.31f, 0.42f, 0.61f),
                RawObjectDetection("Pedestrian", 0.85f, 0.70f, 0.40f, 0.78f, 0.75f),
            ),
            timestampMillis = 1_100L,
        )
        assertEquals(2, f2.size)
        assertEquals(id1, f2[0].trackingId)
        assertNotEquals(id1, f2[1].trackingId)
    }
}
