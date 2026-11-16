package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

data class SignRoiCrop(
    val bitmap: Bitmap,
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
)

/**
 * Finds color-salient road sign regions (red, yellow, or blue) in the upper & roadside
 * corridors so OCR focuses on sign candidates rather than reading the entire road surface.
 */
object SignRoiExtractor {

    fun extractSignRois(bitmap: Bitmap, maxRois: Int = 3): List<SignRoiCrop> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 64 || h < 64) return emptyList()

        val cols = 20
        val rows = 14
        val maxRow = (rows * 0.72f).toInt() // Focus on upper 72% (roadside signs & gantries, skip hood/asphalt)
        val grid = Array(maxRow) { BooleanArray(cols) }

        val cellW = (w / cols).coerceAtLeast(1)
        val cellH = (h / rows).coerceAtLeast(1)

        for (r in 0 until maxRow) {
            val cy = (r * cellH + cellH / 2).coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val cx = (c * cellW + cellW / 2).coerceIn(0, w - 1)
                if (isSignColorPixel(bitmap.getPixel(cx, cy))) {
                    grid[r][c] = true
                }
            }
        }

        val visited = Array(maxRow) { BooleanArray(cols) }
        val boxes = mutableListOf<Rect>()

        for (r in 0 until maxRow) {
            for (c in 0 until cols) {
                if (!grid[r][c] || visited[r][c]) continue
                var minR = r
                var maxR = r
                var minC = c
                var maxC = c
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.addLast(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    minR = minOf(minR, cr)
                    maxR = maxOf(maxR, cr)
                    minC = minOf(minC, cc)
                    maxC = maxOf(maxC, cc)

                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until maxRow && nc in 0 until cols && grid[nr][nc] && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                queue.addLast(nr to nc)
                            }
                        }
                    }
                }

                val pxLeft = ((minC - 1).coerceAtLeast(0) * cellW).coerceIn(0, w - 1)
                val pxTop = ((minR - 1).coerceAtLeast(0) * cellH).coerceIn(0, h - 1)
                val pxRight = (((maxC + 2) * cellW)).coerceIn(pxLeft + 1, w)
                val pxBottom = (((maxR + 2) * cellH)).coerceIn(pxTop + 1, h)
                val boxW = pxRight - pxLeft
                val boxH = pxBottom - pxTop
                if (boxW >= cellW * 2 && boxH >= cellH * 2) {
                    boxes.add(Rect(pxLeft, pxTop, pxRight, pxBottom))
                }
            }
        }

        return boxes
            .sortedByDescending { it.width() * it.height() }
            .take(maxRois)
            .map { rect ->
                val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
                SignRoiCrop(
                    bitmap = crop,
                    normLeft = rect.left.toFloat() / w,
                    normTop = rect.top.toFloat() / h,
                    normRight = rect.right.toFloat() / w,
                    normBottom = rect.bottom.toFloat() / h,
                )
            }
    }

    private fun isSignColorPixel(px: Int): Boolean {
        val r = Color.red(px)
        val g = Color.green(px)
        val b = Color.blue(px)
        val isRed = r > 135 && g < 95 && b < 95 && (r - g) > 50
        val isYellow = r > 155 && g > 125 && b < 90
        val isBlue = b > 135 && r < 95 && (b - r) > 50
        return isRed || isYellow || isBlue
    }
}
