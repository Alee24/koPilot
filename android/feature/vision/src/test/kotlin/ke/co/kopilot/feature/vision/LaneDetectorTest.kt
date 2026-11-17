package ke.co.kopilot.feature.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class LaneDetectorTest {

    @Test
    fun `centered vehicle reports NONE shift`() {
        val (offset, event) = LaneDetector.computeLaneShift(leftBottomX = 0.15f, rightBottomX = 0.85f)
        assertEquals(0f, offset, 0.01f)
        assertEquals(LaneShiftEvent.NONE, event)
    }

    @Test
    fun `vehicle drifting left reports LANE_DEPARTURE_LEFT`() {
        // Lane shifted to the right in camera frame -> car moved left
        val (_, event) = LaneDetector.computeLaneShift(leftBottomX = 0.42f, rightBottomX = 0.94f)
        assertEquals(LaneShiftEvent.LANE_DEPARTURE_LEFT, event)
    }

    @Test
    fun `vehicle drifting right reports LANE_DEPARTURE_RIGHT`() {
        // Lane shifted to the left in camera frame -> car moved right
        val (_, event) = LaneDetector.computeLaneShift(leftBottomX = 0.06f, rightBottomX = 0.58f)
        assertEquals(LaneShiftEvent.LANE_DEPARTURE_RIGHT, event)
    }
}
