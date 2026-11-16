package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

enum class SignRoiColor {
    RED,
    YELLOW,
    BLUE,
}

data class SignRoiCrop(
    val bitmap: Bitmap,
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
    val dominantColor: SignRoiColor,
)

/**
 * Finds color-salient road sign regions (red, yellow, or blue) in the upper & roadside
 * corridors so OCR, shape analysis, and TFLite classification focus on sign candidates
 * with clean padding around the sign border.
 */
object SignRoiExtractor {

    fun extractSignRois(bitmap: Bitmap, maxRois: Int = 3): List<SignRoiCrop> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 64 || h < 64) return emptyList()

        val cols = 28
        val rows = 20
        val maxRow = (rows * 0.85f).toInt().coerceIn(1, rows)
        val grid = Array(maxRow) { arrayOfNulls<SignRoiColor>(cols) }

        val cellW = (w / cols).coerceAtLeast(1)
        val cellH = (h / rows).coerceAtLeast(1)

        for (r in 0 until maxRow) {
            val y0 = (r * cellH).coerceIn(0, h - 1)
            val y1 = ((r * cellH) + cellH / 2).coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val x0 = (c * cellW + cellW / 3).coerceIn(0, w - 1)
                val x1 = (c * cellW + (2 * cellW) / 3).coerceIn(0, w - 1)
                val c1 = classifySignPixel(bitmap.getPixel(x0, y1))
                val c2 = if (c1 == null) classifySignPixel(bitmap.getPixel(x1, y0)) else c1
                grid[r][c] = c2
            }
        }

        val visited = Array(maxRow) { BooleanArray(cols) }
        val candidates = mutableListOf<Pair<Rect, SignRoiColor>>()

        for (r in 0 until maxRow) {
            for (c in 0 until cols) {
                val startColor = grid[r][c] ?: continue
                if (visited[r][c]) continue
                var minR = r
                var maxR = r
                var minC = c
                var maxC = c
                var redCount = 0
                var yellowCount = 0
                var blueCount = 0
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.addLast(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    when (grid[cr][cc]) {
                        SignRoiColor.RED -> redCount++
                        SignRoiColor.YELLOW -> yellowCount++
                        SignRoiColor.BLUE -> blueCount++
                        null -> {}
                    }
                    minR = minOf(minR, cr)
                    maxR = maxOf(maxR, cr)
                    minC = minOf(minC, cc)
                    maxC = maxOf(maxC, cc)

                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until maxRow && nc in 0 until cols && grid[nr][nc] != null && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                queue.addLast(nr to nc)
                            }
                        }
                    }
                }

                val rawLeft = minC * cellW
                val rawTop = minR * cellH
                val rawRight = (maxC + 1) * cellW
                val rawBottom = (maxR + 1) * cellH
                val rawW = rawRight - rawLeft
                val rawH = rawBottom - rawTop

                if (rawW >= cellW * 2 && rawH >= cellH * 2) {
                    // Add generous 18% padding so ML Kit OCR & shape analyzer see the full sign border
                    val padX = (rawW * 0.18f).toInt().coerceAtLeast(cellW)
                    val padY = (rawH * 0.18f).toInt().coerceAtLeast(cellH)
                    val pxLeft = (rawLeft - padX).coerceIn(0, w - 1)
                    val pxTop = (rawTop - padY).coerceIn(0, h - 1)
                    val pxRight = (rawRight + padX).coerceIn(pxLeft + 1, w)
                    val pxBottom = (rawBottom + padY).coerceIn(pxTop + 1, h)

                    val dominant = when {
                        redCount >= yellowCount && redCount >= blueCount -> SignRoiColor.RED
                        yellowCount >= blueCount -> SignRoiColor.YELLOW
                        else -> SignRoiColor.BLUE
                    }
                    candidates.add(Rect(pxLeft, pxTop, pxRight, pxBottom) to dominant)
                }
            }
        }

        return candidates
            .sortedByDescending { (rect, _) -> rect.width() * rect.height() }
            .take(maxRois)
            .map { (rect, color) ->
                val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
                SignRoiCrop(
                    bitmap = crop,
                    normLeft = rect.left.toFloat() / w,
                    normTop = rect.top.toFloat() / h,
                    normRight = rect.right.toFloat() / w,
                    normBottom = rect.bottom.toFloat() / h,
                    dominantColor = color,
                )
            }
    }

    internal fun classifySignPixel(px: Int): SignRoiColor? {
        val r = Color.red(px)
        val g = Color.green(px)
        val b = Color.blue(px)
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        if (maxC < 75 || (maxC - minC) < 42) return null

        return when {
            r == maxC && (r - g) > 42 && (r - b) > 42 -> SignRoiColor.RED
            r > 130 && g > 105 && b < 110 && (r - b) > 55 && (g - b) > 40 -> SignRoiColor.YELLOW
            b == maxC && (b - r) > 45 && (b - g) > 20 -> SignRoiColor.BLUE
            else -> null
        }
    }
}
