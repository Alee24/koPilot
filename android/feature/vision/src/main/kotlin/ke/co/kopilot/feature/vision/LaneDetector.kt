package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.VisibleForTesting

enum class LaneShiftEvent {
    NONE,
    SHIFTING_LEFT,
    SHIFTING_RIGHT,
    LANE_DEPARTURE_LEFT,
    LANE_DEPARTURE_RIGHT,
}

data class LaneOverlayState(
    val lanesDetected: Boolean = false,
    val leftTopX: Float = 0.36f,
    val leftTopY: Float = 0.56f,
    val leftBottomX: Float = 0.14f,
    val leftBottomY: Float = 0.94f,
    val rightTopX: Float = 0.64f,
    val rightTopY: Float = 0.56f,
    val rightBottomX: Float = 0.86f,
    val rightBottomY: Float = 0.94f,
    val centerOffset: Float = 0f,
    val shiftEvent: LaneShiftEvent = LaneShiftEvent.NONE,
)

/**
 * Fast on-device road lane detector (< 2 ms per frame).
 * Scans the lower road region for white or yellow lane markings, fits left/right lane lines,
 * and computes lane-centering offset and lane shift/departure events.
 */
object LaneDetector {

    private val SCAN_Y_NORMS = floatArrayOf(0.56f, 0.63f, 0.70f, 0.77f, 0.84f, 0.91f)

    fun detectLanes(bitmap: Bitmap): LaneOverlayState {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 64 || h < 64) return LaneOverlayState()

        val leftHits = mutableListOf<Pair<Float, Float>>()
        val rightHits = mutableListOf<Pair<Float, Float>>()

        for (yNorm in SCAN_Y_NORMS) {
            val y = (yNorm * h).toInt().coerceIn(0, h - 1)
            val roadBaselineLum = estimateRoadLuminance(bitmap, w, y)

            // Search outward from lane center (0.48 -> 0.05) for left marking
            var foundLeftX: Float? = null
            var x = (w * 0.48f).toInt()
            val minLeft = (w * 0.05f).toInt()
            val step = (w / 140).coerceAtLeast(1)
            while (x >= minLeft) {
                if (isLaneMarkingPixel(bitmap.getPixel(x, y), roadBaselineLum)) {
                    foundLeftX = x.toFloat() / w
                    break
                }
                x -= step
            }
            if (foundLeftX != null) leftHits.add(foundLeftX to yNorm)

            // Search outward from lane center (0.52 -> 0.95) for right marking
            var foundRightX: Float? = null
            x = (w * 0.52f).toInt()
            val maxRight = (w * 0.95f).toInt()
            while (x <= maxRight) {
                if (isLaneMarkingPixel(bitmap.getPixel(x, y), roadBaselineLum)) {
                    foundRightX = x.toFloat() / w
                    break
                }
                x += step
            }
            if (foundRightX != null) rightHits.add(foundRightX to yNorm)
        }

        val hasLeft = leftHits.size >= 2
        val hasRight = rightHits.size >= 2
        if (!hasLeft && !hasRight) {
            return LaneOverlayState(lanesDetected = false)
        }

        val leftTop = if (hasLeft) leftHits.first() else (1f - rightHits.first().first).coerceIn(0.15f, 0.48f) to rightHits.first().second
        val leftBot = if (hasLeft) leftHits.last() else (1f - rightHits.last().first).coerceIn(0.05f, 0.46f) to rightHits.last().second
        val rightTop = if (hasRight) rightHits.first() else (1f - leftTop.first).coerceIn(0.52f, 0.85f) to leftTop.second
        val rightBot = if (hasRight) rightHits.last() else (1f - leftBot.first).coerceIn(0.54f, 0.95f) to leftBot.second

        val (offset, shiftEvent) = computeLaneShift(leftBot.first, rightBot.first)

        return LaneOverlayState(
            lanesDetected = true,
            leftTopX = leftTop.first,
            leftTopY = leftTop.second,
            leftBottomX = leftBot.first,
            leftBottomY = leftBot.second,
            rightTopX = rightTop.first,
            rightTopY = rightTop.second,
            rightBottomX = rightBot.first,
            rightBottomY = rightBot.second,
            centerOffset = offset,
            shiftEvent = shiftEvent,
        )
    }

    @VisibleForTesting
    internal fun computeLaneShift(leftBottomX: Float, rightBottomX: Float): Pair<Float, LaneShiftEvent> {
        val laneWidth = (rightBottomX - leftBottomX).coerceAtLeast(0.18f)
        val laneMid = (leftBottomX + rightBottomX) / 2f
        // Positive offset => car center (0.5) is to the right of laneMid (shifting right)
        val offset = ((0.5f - laneMid) / (laneWidth / 2f)).coerceIn(-1f, 1f)
        val event = when {
            offset <= -0.45f -> LaneShiftEvent.LANE_DEPARTURE_LEFT
            offset <= -0.25f -> LaneShiftEvent.SHIFTING_LEFT
            offset >= 0.45f -> LaneShiftEvent.LANE_DEPARTURE_RIGHT
            offset >= 0.25f -> LaneShiftEvent.SHIFTING_RIGHT
            else -> LaneShiftEvent.NONE
        }
        return offset to event
    }

    private fun estimateRoadLuminance(bitmap: Bitmap, w: Int, y: Int): Float {
        var sum = 0f
        var count = 0
        val startX = (w * 0.28f).toInt()
        val endX = (w * 0.72f).toInt()
        val step = ((endX - startX) / 10).coerceAtLeast(1)
        var x = startX
        while (x <= endX) {
            val px = bitmap.getPixel(x, y)
            sum += 0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)
            count++
            x += step
        }
        return if (count == 0) 80f else sum / count
    }

    private fun isLaneMarkingPixel(px: Int, roadBaselineLum: Float): Boolean {
        val r = Color.red(px)
        val g = Color.green(px)
        val b = Color.blue(px)
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        val isWhiteLine = lum > (roadBaselineLum + 42f).coerceAtLeast(135f) && spread < 52
        val isYellowLine = r > 140 && g > 110 && b < 110 && (r - b) > 45 && lum > roadBaselineLum + 20f
        return isWhiteLine || isYellowLine
    }
}
