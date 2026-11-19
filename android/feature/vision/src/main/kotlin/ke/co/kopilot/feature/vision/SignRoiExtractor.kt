package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import ke.co.kopilot.core.common.model.TrafficSignCategory
import kotlin.math.abs

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
    val signScore: Float = 0.5f,
)

/**
 * Full-view, high-density color-salient road sign ROI extractor and shape/color verifier.
 * Scans the full camera view using a 48x36 multi-sample grid so small and distant road signs
 * (down to 20x20 px) are localized reliably, while rejecting large non-sign vehicles, walls,
 * and indoor clutter by scoring aspect-ratio compactness and perimeter-to-interior contrast.
 */
object SignRoiExtractor {

    private const val GRID_COLS = 48
    private const val GRID_ROWS = 36

    fun extractSignRois(bitmap: Bitmap, maxRois: Int = 4): List<SignRoiCrop> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 64 || h < 64) return emptyList()

        val cols = GRID_COLS
        val rows = GRID_ROWS
        val grid = Array(rows) { arrayOfNulls<SignRoiColor>(cols) }

        val cellW = (w.toFloat() / cols).coerceAtLeast(1f)
        val cellH = (h.toFloat() / rows).coerceAtLeast(1f)

        // Sample 5 points per cell (center + 4 inner quadrants) so thin hollow red rings are never skipped
        for (r in 0 until rows) {
            val cy = ((r + 0.5f) * cellH).toInt().coerceIn(0, h - 1)
            val yTop = ((r + 0.22f) * cellH).toInt().coerceIn(0, h - 1)
            val yBot = ((r + 0.78f) * cellH).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val cx = ((c + 0.5f) * cellW).toInt().coerceIn(0, w - 1)
                val xLeft = ((c + 0.22f) * cellW).toInt().coerceIn(0, w - 1)
                val xRight = ((c + 0.78f) * cellW).toInt().coerceIn(0, w - 1)

                val hit = classifySignPixel(bitmap.getPixel(cx, cy))
                    ?: classifySignPixel(bitmap.getPixel(xLeft, yTop))
                    ?: classifySignPixel(bitmap.getPixel(xRight, yTop))
                    ?: classifySignPixel(bitmap.getPixel(xLeft, yBot))
                    ?: classifySignPixel(bitmap.getPixel(xRight, yBot))
                grid[r][c] = hit
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        data class CandidateBlob(
            val rect: Rect,
            val dominantColor: SignRoiColor,
            val score: Float,
        )
        val candidates = mutableListOf<CandidateBlob>()

        for (r in 0 until rows) {
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
                            if (nr in 0 until rows && nc in 0 until cols && grid[nr][nc] != null && !visited[nr][nc]) {
                                visited[nr][nc] = true
                                queue.addLast(nr to nc)
                            }
                        }
                    }
                }

                val rawLeft = (minC * cellW).toInt()
                val rawTop = (minR * cellH).toInt()
                val rawRight = ((maxC + 1) * cellW).toInt()
                val rawBottom = ((maxR + 1) * cellH).toInt()
                val rawW = rawRight - rawLeft
                val rawH = rawBottom - rawTop

                val minPx = 20
                if (rawW < minPx || rawH < minPx) continue

                val aspect = rawW.toFloat() / rawH.toFloat().coerceAtLeast(1f)
                val areaRatio = (rawW.toFloat() * rawH.toFloat()) / (w.toFloat() * h.toFloat())

                // Road signs are compact (circular, triangular, octagonal, or square/rectangular plates)
                // and occupy between 0.04% and 26% of the frame.
                if (aspect !in 0.50f..1.85f || areaRatio !in 0.0004f..0.26f) continue

                val padX = (rawW * 0.18f).toInt().coerceAtLeast(6)
                val padY = (rawH * 0.18f).toInt().coerceAtLeast(6)
                val pxLeft = (rawLeft - padX).coerceIn(0, w - 1)
                val pxTop = (rawTop - padY).coerceIn(0, h - 1)
                val pxRight = (rawRight + padX).coerceIn(pxLeft + 1, w)
                val pxBottom = (rawBottom + padY).coerceIn(pxTop + 1, h)
                val rect = Rect(pxLeft, pxTop, pxRight, pxBottom)

                val dominant = when {
                    redCount >= yellowCount && redCount >= blueCount -> SignRoiColor.RED
                    yellowCount >= blueCount -> SignRoiColor.YELLOW
                    else -> SignRoiColor.BLUE
                }

                val geometryScore = evaluateSignCompactnessAndContrast(bitmap, rect, dominant)
                if (geometryScore >= 0.28f) {
                    candidates.add(CandidateBlob(rect, dominant, geometryScore))
                }
            }
        }

        return candidates
            .sortedByDescending { it.score }
            .take(maxRois)
            .map { blob ->
                val rect = blob.rect
                val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
                SignRoiCrop(
                    bitmap = crop,
                    normLeft = rect.left.toFloat() / w,
                    normTop = rect.top.toFloat() / h,
                    normRight = rect.right.toFloat() / w,
                    normBottom = rect.bottom.toFloat() / h,
                    dominantColor = blob.dominantColor,
                    signScore = blob.score,
                )
            }
    }

    /**
     * Evaluates whether a region exhibits genuine road-sign characteristics:
     * 1. Aspect ratio near 1:1 (circle, triangle, octagon, square).
     * 2. Presence of saturated sign border/face color (red, yellow, or blue).
     * 3. High interior-to-border contrast (white/bright or dark symbol interior, not a uniform wood/skin/wall tone).
     */
    fun evaluateSignCompactnessAndContrast(
        bitmap: Bitmap,
        rect: Rect,
        expectedColor: SignRoiColor? = null,
    ): Float {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val rw = right - left
        val rh = bottom - top
        if (rw < 14 || rh < 14) return 0f

        val aspect = rw.toFloat() / rh.toFloat()
        if (aspect !in 0.48f..1.90f) return 0f
        val aspectScore = (1f - abs(1f - aspect).coerceAtMost(0.75f))

        val steps = 10
        var signColorHits = 0
        var whiteHits = 0
        var darkSymbolHits = 0
        var skinOrWoodHits = 0
        var total = 0

        for (iy in 0 until steps) {
            val y = (top + (iy + 0.5f) * rh / steps).toInt().coerceIn(0, bitmap.height - 1)
            for (ix in 0 until steps) {
                val x = (left + (ix + 0.5f) * rw / steps).toInt().coerceIn(0, bitmap.width - 1)
                val px = bitmap.getPixel(x, y)
                total++
                val c = classifySignPixel(px)
                if (c != null && (expectedColor == null || c == expectedColor)) {
                    signColorHits++
                }
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val spread = maxOf(r, g, b) - minOf(r, g, b)
                if (lum >= 135f && spread <= 55) whiteHits++
                if (lum <= 85f && spread <= 50) darkSymbolHits++
                // Reject warm indoor wood/skin/beige tones (R > G > B with moderate saturation)
                if (c == null && r in 110..225 && g in 75..180 && b in 45..140 && (r - g) in 12..48 && (g - b) in 12..55) {
                    skinOrWoodHits++
                }
            }
        }
        if (total == 0) return 0f
        val colorRatio = signColorHits.toFloat() / total
        val contrastRatio = (whiteHits + darkSymbolHits).toFloat() / total
        val woodPenalty = if (skinOrWoodHits.toFloat() / total > 0.42f) 0.35f else 0f

        if (colorRatio < 0.06f) return 0f
        return (aspectScore * 0.40f + (colorRatio * 1.8f).coerceAtMost(0.35f) + (contrastRatio * 1.2f).coerceAtMost(0.35f) - woodPenalty)
            .coerceIn(0f, 1f)
    }

    /**
     * Verifies a neural-network (YOLO11n) bounding box against actual pixel color & contrast
     * to reject indoor false positives (furniture, walls, monitors) and roadworks false positives.
     */
    fun verifyYoloDetectionBox(
        bitmap: Bitmap,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        category: TrafficSignCategory,
    ): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val rect = Rect(
            (normLeft * w).toInt().coerceIn(0, w - 1),
            (normTop * h).toInt().coerceIn(0, h - 1),
            (normRight * w).toInt().coerceIn(1, w),
            (normBottom * h).toInt().coerceIn(1, h),
        )
        val score = evaluateSignCompactnessAndContrast(bitmap, rect, expectedColor = null)
        if (score < 0.24f) return false

        // Speed limit signs and prohibitory signs MUST contain either red border pixels or high-contrast white/dark interior
        if (category.speedLimitKmh != null) {
            val hasWhite = SignCandidateParser.hasWhiteInterior(bitmap, rect)
            val hasBorder = SignCandidateParser.hasSignBorderColor(bitmap, rect)
            return hasWhite || hasBorder
        }
        // Roadworks signs must have genuine yellow/amber/red warning colors and dark interior symbol ink
        if (category == TrafficSignCategory.ROADWORKS) {
            return score >= 0.36f && hasDarkInteriorSymbol(bitmap, rect)
        }
        return true
    }

    private fun hasDarkInteriorSymbol(bitmap: Bitmap, rect: Rect): Boolean {
        val inLeft = (rect.left + rect.width() * 0.22f).toInt().coerceIn(0, bitmap.width - 1)
        val inRight = (rect.right - rect.width() * 0.22f).toInt().coerceIn(inLeft + 1, bitmap.width)
        val inTop = (rect.top + rect.height() * 0.22f).toInt().coerceIn(0, bitmap.height - 1)
        val inBot = (rect.bottom - rect.height() * 0.22f).toInt().coerceIn(inTop + 1, bitmap.height)
        var darkHits = 0
        var total = 0
        val steps = 6
        for (iy in 0 until steps) {
            val y = (inTop + (iy + 0.5f) * (inBot - inTop) / steps).toInt().coerceIn(0, bitmap.height - 1)
            for (ix in 0 until steps) {
                val x = (inLeft + (ix + 0.5f) * (inRight - inLeft) / steps).toInt().coerceIn(0, bitmap.width - 1)
                val px = bitmap.getPixel(x, y)
                val lum = 0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)
                if (lum < 95f) darkHits++
                total++
            }
        }
        return total > 0 && (darkHits.toFloat() / total) in 0.08f..0.75f
    }

    internal fun classifySignPixel(px: Int): SignRoiColor? {
        val r = Color.red(px)
        val g = Color.green(px)
        val b = Color.blue(px)
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        if (maxC < 70 || (maxC - minC) < 38) return null

        return when {
            r == maxC && (r - g) > 38 && (r - b) > 38 -> SignRoiColor.RED
            r > 125 && g > 100 && b < 110 && (r - b) > 50 && (g - b) > 35 -> SignRoiColor.YELLOW
            b == maxC && (b - r) > 40 && (b - g) > 18 -> SignRoiColor.BLUE
            else -> null
        }
    }
}
