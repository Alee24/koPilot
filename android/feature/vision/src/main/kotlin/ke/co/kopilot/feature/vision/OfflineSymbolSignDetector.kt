package ke.co.kopilot.feature.vision

import android.graphics.Bitmap
import android.graphics.Color
import ke.co.kopilot.core.common.model.TrafficSignCategory
import kotlin.math.abs

/**
 * Option 3 (Zero-Model Pure On-Device CV):
 * Lightweight shape + color + interior symbol geometry analyzer that identifies
 * symbol-only road signs (Give Way, No Entry, Speed Bump, Dangerous Bend, Roundabout)
 * in < 3 ms completely offline without requiring any external model download.
 */
object OfflineSymbolSignDetector {

    private const val GRID = 24

    fun classifyCrop(roi: SignRoiCrop): RawSignDetection? {
        val bmp = roi.bitmap
        if (bmp.width < 24 || bmp.height < 24) return null
        val aspect = bmp.width.toFloat() / bmp.height.toFloat()
        if (aspect < 0.55f || aspect > 1.75f) return null

        val colorMask = Array(GRID) { arrayOfNulls<SignRoiColor>(GRID) }
        val darkMask = Array(GRID) { BooleanArray(GRID) }
        val whiteMask = Array(GRID) { BooleanArray(GRID) }

        for (r in 0 until GRID) {
            val y = ((r + 0.5f) * bmp.height / GRID).toInt().coerceIn(0, bmp.height - 1)
            for (c in 0 until GRID) {
                val x = ((c + 0.5f) * bmp.width / GRID).toInt().coerceIn(0, bmp.width - 1)
                val px = bmp.getPixel(x, y)
                val signColor = SignRoiExtractor.classifySignPixel(px)
                colorMask[r][c] = signColor

                val red = Color.red(px)
                val green = Color.green(px)
                val blue = Color.blue(px)
                val lum = 0.299f * red + 0.587f * green + 0.114f * blue
                val spread = maxOf(red, green, blue) - minOf(red, green, blue)

                if (signColor == null && lum < 92f) {
                    darkMask[r][c] = true
                }
                if (signColor == null && lum > 155f && spread < 48) {
                    whiteMask[r][c] = true
                }
            }
        }

        val centerX = (roi.normLeft + roi.normRight) / 2f
        return classifyGrid(
            colorMask = colorMask,
            darkMask = darkMask,
            whiteMask = whiteMask,
            dominantColor = roi.dominantColor,
            normalizedCenterX = centerX,
        )
    }

    internal fun classifyGrid(
        colorMask: Array<Array<SignRoiColor?>>,
        darkMask: Array<BooleanArray>,
        whiteMask: Array<BooleanArray>,
        dominantColor: SignRoiColor,
        normalizedCenterX: Float,
    ): RawSignDetection? {
        val topSpan = averageColorSpan(colorMask, 2..7, dominantColor)
        val midSpan = averageColorSpan(colorMask, 9..14, dominantColor)
        val botSpan = averageColorSpan(colorMask, 16..21, dominantColor)

        val totalCells = GRID * GRID
        var dominantCount = 0
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                if (colorMask[r][c] == dominantColor) dominantCount++
            }
        }
        val dominantFill = dominantCount.toFloat() / totalCells
        if (dominantFill < 0.10f) return null

        val centerWhiteRatio = regionRatio(whiteMask, 7..16, 7..16)

        // 1. Red-dominant signs: No Entry, Give Way (inverted triangle), Upright Warning Triangle
        if (dominantColor == SignRoiColor.RED) {
            val topRedRatio = colorRegionRatio(colorMask, 3..7, 6..17, SignRoiColor.RED)
            val midWhiteRatio = regionRatio(whiteMask, 10..13, 5..18)
            val botRedRatio = colorRegionRatio(colorMask, 16..20, 6..17, SignRoiColor.RED)

            // No Entry: red disc with bold horizontal white bar across the center
            if (dominantFill >= 0.30f && topRedRatio >= 0.42f && botRedRatio >= 0.42f && midWhiteRatio >= 0.42f) {
                return RawSignDetection(
                    category = TrafficSignCategory.NO_ENTRY,
                    confidence = 0.80f,
                    normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
                )
            }

            // Give Way: inverted triangle (wide top, narrow bottom, white interior)
            if (topSpan >= 10f && botSpan in 1f..10f && topSpan >= botSpan * 1.55f && centerWhiteRatio >= 0.25f) {
                return RawSignDetection(
                    category = TrafficSignCategory.GIVE_WAY,
                    confidence = 0.78f,
                    normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
                )
            }

            // Upright Warning Triangle: narrow top, wide bottom
            if (botSpan >= 10f && topSpan in 1f..10f && botSpan >= topSpan * 1.45f && centerWhiteRatio >= 0.20f) {
                return classifyWarningTriangleInterior(darkMask, normalizedCenterX)
            }
        }

        // 2. Yellow-dominant warning signs (diamond or upright triangle)
        if (dominantColor == SignRoiColor.YELLOW && dominantFill >= 0.22f) {
            return classifyWarningTriangleInterior(darkMask, normalizedCenterX)
        }

        // 3. Blue-dominant circular mandatory signs (Roundabout & Keep Left)
        if (dominantColor == SignRoiColor.BLUE && dominantFill >= 0.30f && midSpan >= 10f) {
            val ringWhite = (
                regionRatio(whiteMask, 6..8, 7..16) +
                    regionRatio(whiteMask, 15..17, 7..16) +
                    regionRatio(whiteMask, 8..15, 6..8) +
                    regionRatio(whiteMask, 8..15, 15..17)
                ) / 4f
            val coreWhite = regionRatio(whiteMask, 10..13, 10..13)
            if (ringWhite >= 0.22f && ringWhite > coreWhite * 1.45f) {
                return RawSignDetection(
                    category = TrafficSignCategory.ROUNDABOUT,
                    confidence = 0.73f,
                    normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
                )
            }
            // Keep Left: diagonal white arrow from upper-right quadrant toward lower-left quadrant
            val upperRightWhite = regionRatio(whiteMask, 6..11, 12..17)
            val lowerLeftWhite = regionRatio(whiteMask, 12..17, 6..11)
            val upperLeftWhite = regionRatio(whiteMask, 6..11, 6..11)
            val lowerRightWhite = regionRatio(whiteMask, 12..17, 12..17)
            val diagMain = (upperRightWhite + lowerLeftWhite) * 0.5f
            val diagOff = (upperLeftWhite + lowerRightWhite) * 0.5f
            if (diagMain >= 0.20f && diagMain > diagOff * 1.55f) {
                return RawSignDetection(
                    category = TrafficSignCategory.KEEP_LEFT,
                    confidence = 0.72f,
                    normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
                )
            }
        }

        return null
    }

    private fun classifyWarningTriangleInterior(
        darkMask: Array<BooleanArray>,
        normalizedCenterX: Float,
    ): RawSignDetection? {
        val upperDark = regionRatio(darkMask, 7..11, 7..16)
        val lowerDark = regionRatio(darkMask, 13..18, 6..17)
        val totalInteriorDark = (upperDark + lowerDark) / 2f
        if (totalInteriorDark < 0.06f) return null

        // Speed bump: dark symbol sits heavily in the lower half with minimal upper ink
        if (lowerDark >= 0.14f && upperDark <= 0.05f && lowerDark > upperDark * 2.2f) {
            return RawSignDetection(
                category = TrafficSignCategory.SPEED_BUMP,
                confidence = 0.74f,
                normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
            )
        }

        // Red-triangle Roundabout (W6): dark circular ring around center (rows 9..16, cols 8..15) with lighter hollow center (11..14, 10..13)
        val ringDark = (
            regionRatio(darkMask, 9..10, 9..14) +
                regionRatio(darkMask, 15..16, 9..14) +
                regionRatio(darkMask, 11..14, 8..9) +
                regionRatio(darkMask, 11..14, 14..15)
            ) / 4f
        val coreDark = regionRatio(darkMask, 11..14, 10..13)
        if (ringDark >= 0.18f && ringDark > coreDark * 1.6f) {
            return RawSignDetection(
                category = TrafficSignCategory.ROUNDABOUT,
                confidence = 0.72f,
                normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
            )
        }

        // Dangerous bend: S-curve / angled stroke with horizontal center-of-mass shift between upper and lower halves
        val upperComX = centerOfMassX(darkMask, 7..11, 6..17)
        val lowerComX = centerOfMassX(darkMask, 13..18, 6..17)
        if (upperComX != null && lowerComX != null && abs(upperComX - lowerComX) >= 2.2f) {
            return RawSignDetection(
                category = TrafficSignCategory.DANGEROUS_BEND,
                confidence = 0.71f,
                normalizedCenterX = normalizedCenterX.coerceIn(0f, 1f),
            )
        }

        return null
    }

    private fun averageColorSpan(
        colorMask: Array<Array<SignRoiColor?>>,
        rows: IntRange,
        target: SignRoiColor,
    ): Float {
        var sum = 0
        var count = 0
        for (r in rows) {
            var first = -1
            var last = -1
            for (c in 0 until GRID) {
                if (colorMask[r][c] == target) {
                    if (first == -1) first = c
                    last = c
                }
            }
            if (first != -1) {
                sum += (last - first + 1)
                count++
            }
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    private fun regionRatio(mask: Array<BooleanArray>, rows: IntRange, cols: IntRange): Float {
        var hits = 0
        var total = 0
        for (r in rows) {
            for (c in cols) {
                total++
                if (mask[r][c]) hits++
            }
        }
        return if (total == 0) 0f else hits.toFloat() / total
    }

    private fun colorRegionRatio(
        mask: Array<Array<SignRoiColor?>>,
        rows: IntRange,
        cols: IntRange,
        target: SignRoiColor,
    ): Float {
        var hits = 0
        var total = 0
        for (r in rows) {
            for (c in cols) {
                total++
                if (mask[r][c] == target) hits++
            }
        }
        return if (total == 0) 0f else hits.toFloat() / total
    }

    private fun centerOfMassX(mask: Array<BooleanArray>, rows: IntRange, cols: IntRange): Float? {
        var sumX = 0
        var count = 0
        for (r in rows) {
            for (c in cols) {
                if (mask[r][c]) {
                    sumX += c
                    count++
                }
            }
        }
        return if (count < 3) null else sumX.toFloat() / count
    }
}
