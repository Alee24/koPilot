package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.VisibleForTesting
import kotlin.math.abs

enum class LaneShiftEvent {
    NONE,
    SHIFTING_LEFT,
    SHIFTING_RIGHT,
    LANE_DEPARTURE_LEFT,
    LANE_DEPARTURE_RIGHT,
}

data class LaneOverlayState(
    val lanesDetected: Boolean = false,
    val roadSurfaceDetected: Boolean = false,
    val leftTopX: Float = 0.36f,
    val leftTopY: Float = 0.56f,
    val leftBottomX: Float = 0.14f,
    val leftBottomY: Float = 0.94f,
    val rightTopX: Float = 0.64f,
    val rightTopY: Float = 0.56f,
    val rightBottomX: Float = 0.86f,
    val rightBottomY: Float = 0.94f,
    val roadLeftPoints: List<Pair<Float, Float>> = emptyList(),
    val roadRightPoints: List<Pair<Float, Float>> = emptyList(),
    val centerOffset: Float = 0f,
    val shiftEvent: LaneShiftEvent = LaneShiftEvent.NONE,
)

/**
 * Fast on-device drivable road segmentation & lane tracker (< 2 ms per frame).
 * Learns the bottom-center asphalt signature, traces left/right drivable road boundaries
 * across 6 scan rows so the UI can color the active road surface and detect veering off-road.
 */
object LaneDetector {

    private val SCAN_Y_NORMS = floatArrayOf(0.56f, 0.63f, 0.70f, 0.77f, 0.84f, 0.91f)

    fun detectLanes(bitmap: Bitmap): LaneOverlayState {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 64 || h < 64) return LaneOverlayState()

        val (roadR, roadG, roadB, roadLum) = estimateBottomRoadColor(bitmap, w, h)

        val roadLeftPts = mutableListOf<Pair<Float, Float>>()
        val roadRightPts = mutableListOf<Pair<Float, Float>>()
        val laneLeftHits = mutableListOf<Pair<Float, Float>>()
        val laneRightHits = mutableListOf<Pair<Float, Float>>()

        val step = (w / 120).coerceAtLeast(1)

        for (yNorm in SCAN_Y_NORMS) {
            val y = (yNorm * h).toInt().coerceIn(0, h - 1)
            val rowLum = estimateRoadLuminance(bitmap, w, y)

            // Scan left from center (0.48 -> 0.04)
            var leftEdgeX = 0.04f
            var foundLaneLeft: Float? = null
            var x = (w * 0.48f).toInt()
            val minLeft = (w * 0.04f).toInt()
            while (x >= minLeft) {
                val px = bitmap.getPixel(x, y)
                if (isLaneMarkingPixel(px, rowLum)) {
                    foundLaneLeft = x.toFloat() / w
                    leftEdgeX = foundLaneLeft
                    break
                }
                if (!matchesRoadAsphalt(px, roadR, roadG, roadB, roadLum)) {
                    leftEdgeX = (x.toFloat() / w).coerceIn(0.04f, 0.46f)
                    break
                }
                x -= step
            }
            roadLeftPts.add(leftEdgeX to yNorm)
            if (foundLaneLeft != null) laneLeftHits.add(foundLaneLeft to yNorm)

            // Scan right from center (0.52 -> 0.96)
            var rightEdgeX = 0.96f
            var foundLaneRight: Float? = null
            x = (w * 0.52f).toInt()
            val maxRight = (w * 0.96f).toInt()
            while (x <= maxRight) {
                val px = bitmap.getPixel(x, y)
                if (isLaneMarkingPixel(px, rowLum)) {
                    foundLaneRight = x.toFloat() / w
                    rightEdgeX = foundLaneRight
                    break
                }
                if (!matchesRoadAsphalt(px, roadR, roadG, roadB, roadLum)) {
                    rightEdgeX = (x.toFloat() / w).coerceIn(0.54f, 0.96f)
                    break
                }
                x += step
            }
            roadRightPts.add(rightEdgeX to yNorm)
            if (foundLaneRight != null) laneRightHits.add(foundLaneRight to yNorm)
        }

        val hasLaneMarks = laneLeftHits.size >= 2 || laneRightHits.size >= 2
        val lTop = if (laneLeftHits.size >= 2) laneLeftHits.first() else roadLeftPts.first()
        val lBot = if (laneLeftHits.size >= 2) laneLeftHits.last() else roadLeftPts.last()
        val rTop = if (laneRightHits.size >= 2) laneRightHits.first() else roadRightPts.first()
        val rBot = if (laneRightHits.size >= 2) laneRightHits.last() else roadRightPts.last()

        val (offset, shiftEvent) = computeLaneShift(lBot.first, rBot.first)

        return LaneOverlayState(
            lanesDetected = hasLaneMarks,
            roadSurfaceDetected = roadLeftPts.isNotEmpty() && roadRightPts.isNotEmpty(),
            leftTopX = lTop.first,
            leftTopY = lTop.second,
            leftBottomX = lBot.first,
            leftBottomY = lBot.second,
            rightTopX = rTop.first,
            rightTopY = rTop.second,
            rightBottomX = rBot.first,
            rightBottomY = rBot.second,
            roadLeftPoints = roadLeftPts,
            roadRightPoints = roadRightPts,
            centerOffset = offset,
            shiftEvent = shiftEvent,
        )
    }

    @VisibleForTesting
    internal fun computeLaneShift(leftBottomX: Float, rightBottomX: Float): Pair<Float, LaneShiftEvent> {
        val laneWidth = (rightBottomX - leftBottomX).coerceAtLeast(0.18f)
        val laneMid = (leftBottomX + rightBottomX) / 2f
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

    private data class RoadColorSample(val r: Float, val g: Float, val b: Float, val lum: Float)

    private fun estimateBottomRoadColor(bitmap: Bitmap, w: Int, h: Int): RoadColorSample {
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var count = 0
        val yStart = (h * 0.86f).toInt()
        val yEnd = (h * 0.94f).toInt()
        val xStart = (w * 0.35f).toInt()
        val xEnd = (w * 0.65f).toInt()
        val stepX = ((xEnd - xStart) / 8).coerceAtLeast(1)
        val stepY = ((yEnd - yStart) / 3).coerceAtLeast(1)
        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val px = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
                sr += Color.red(px)
                sg += Color.green(px)
                sb += Color.blue(px)
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return RoadColorSample(80f, 80f, 80f, 80f)
        val r = sr / count
        val g = sg / count
        val b = sb / count
        return RoadColorSample(r, g, b, 0.299f * r + 0.587f * g + 0.114f * b)
    }

    private fun matchesRoadAsphalt(px: Int, roadR: Float, roadG: Float, roadB: Float, roadLum: Float): Boolean {
        val r = Color.red(px)
        val g = Color.green(px)
        val b = Color.blue(px)
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        val colorDist = (abs(r - roadR) + abs(g - roadG) + abs(b - roadB)) / 3f
        return abs(lum - roadLum) <= 44f && colorDist <= 38f
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
