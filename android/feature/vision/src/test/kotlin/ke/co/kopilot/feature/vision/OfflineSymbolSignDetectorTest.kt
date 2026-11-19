package ke.co.kopilot.feature.vision

import ke.co.kopilot.core.common.model.TrafficSignCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OfflineSymbolSignDetectorTest {

    @Test
    fun `detects NO_ENTRY red disc with horizontal white bar`() {
        val colorMask = Array(24) { arrayOfNulls<SignRoiColor>(24) }
        val darkMask = Array(24) { BooleanArray(24) }
        val whiteMask = Array(24) { BooleanArray(24) }

        for (r in 2..21) {
            for (c in 3..20) {
                if (r in 10..13 && c in 4..19) {
                    whiteMask[r][c] = true
                } else {
                    colorMask[r][c] = SignRoiColor.RED
                }
            }
        }

        val result = OfflineSymbolSignDetector.classifyGrid(
            colorMask = colorMask,
            darkMask = darkMask,
            whiteMask = whiteMask,
            dominantColor = SignRoiColor.RED,
            normalizedCenterX = 0.5f,
        )
        assertNotNull(result)
        assertEquals(TrafficSignCategory.NO_ENTRY, result?.category)
    }

    @Test
    fun `detects GIVE_WAY inverted red triangle`() {
        val colorMask = Array(24) { arrayOfNulls<SignRoiColor>(24) }
        val darkMask = Array(24) { BooleanArray(24) }
        val whiteMask = Array(24) { BooleanArray(24) }

        // Inverted triangle: wide at top (row 2), narrow at bottom (row 20)
        for (r in 2..20) {
            val halfWidth = ((20 - r) * 9 / 18).coerceAtLeast(1)
            val left = 12 - halfWidth
            val right = 12 + halfWidth
            colorMask[r][left] = SignRoiColor.RED
            colorMask[r][right] = SignRoiColor.RED
            if (r <= 4) {
                for (c in left..right) colorMask[r][c] = SignRoiColor.RED
            } else {
                for (c in (left + 1) until right) whiteMask[r][c] = true
            }
        }

        val result = OfflineSymbolSignDetector.classifyGrid(
            colorMask = colorMask,
            darkMask = darkMask,
            whiteMask = whiteMask,
            dominantColor = SignRoiColor.RED,
            normalizedCenterX = 0.5f,
        )
        assertNotNull(result)
        assertEquals(TrafficSignCategory.GIVE_WAY, result?.category)
    }

    @Test
    fun `detects SPEED_BUMP upright warning triangle with lower dark hump`() {
        val colorMask = Array(24) { arrayOfNulls<SignRoiColor>(24) }
        val darkMask = Array(24) { BooleanArray(24) }
        val whiteMask = Array(24) { BooleanArray(24) }

        // Upright triangle: narrow at top (row 2), wide at bottom (row 20)
        for (r in 2..20) {
            val halfWidth = ((r - 2) * 9 / 18).coerceAtLeast(1)
            val left = 12 - halfWidth
            val right = 12 + halfWidth
            colorMask[r][left] = SignRoiColor.RED
            colorMask[r][right] = SignRoiColor.RED
            if (r >= 19) {
                for (c in left..right) colorMask[r][c] = SignRoiColor.RED
            } else {
                for (c in (left + 1) until right) whiteMask[r][c] = true
            }
        }
        // Dark speed bump in lower interior (rows 15..17, cols 8..16)
        for (r in 15..17) {
            for (c in 8..16) {
                whiteMask[r][c] = false
                darkMask[r][c] = true
            }
        }

        val result = OfflineSymbolSignDetector.classifyGrid(
            colorMask = colorMask,
            darkMask = darkMask,
            whiteMask = whiteMask,
            dominantColor = SignRoiColor.RED,
            normalizedCenterX = 0.5f,
        )
        assertNotNull(result)
        assertEquals(TrafficSignCategory.SPEED_BUMP, result?.category)
    }

    @Test
    fun `detects ROUNDABOUT blue circle with white ring`() {
        val colorMask = Array(24) { arrayOfNulls<SignRoiColor>(24) }
        val darkMask = Array(24) { BooleanArray(24) }
        val whiteMask = Array(24) { BooleanArray(24) }

        for (r in 2..21) {
            for (c in 2..21) {
                colorMask[r][c] = SignRoiColor.BLUE
            }
        }
        // White ring around center (rows 6..17, cols 6..17, hollow inside 10..13)
        for (r in 6..17) {
            for (c in 6..17) {
                val inOuter = r in 6..8 || r in 15..17 || c in 6..8 || c in 15..17
                if (inOuter) {
                    colorMask[r][c] = null
                    whiteMask[r][c] = true
                }
            }
        }

        val result = OfflineSymbolSignDetector.classifyGrid(
            colorMask = colorMask,
            darkMask = darkMask,
            whiteMask = whiteMask,
            dominantColor = SignRoiColor.BLUE,
            normalizedCenterX = 0.5f,
        )
        assertNotNull(result)
        assertEquals(TrafficSignCategory.ROUNDABOUT, result?.category)
    }

    @Test
    fun `detects KEEP_LEFT blue circle with diagonal white arrow and rejects empty indoor clutter`() {
        val colorMask = Array(24) { arrayOfNulls<SignRoiColor>(24) }
        val darkMask = Array(24) { BooleanArray(24) }
        val whiteMask = Array(24) { BooleanArray(24) }

        for (r in 2..21) {
            for (c in 2..21) {
                colorMask[r][c] = SignRoiColor.BLUE
            }
        }
        // Diagonal white arrow from upper-right (rows 6..11, cols 12..17) to lower-left (rows 12..17, cols 6..11)
        for (r in 6..11) {
            for (c in 12..17) {
                colorMask[r][c] = null
                whiteMask[r][c] = true
            }
        }
        for (r in 12..17) {
            for (c in 6..11) {
                colorMask[r][c] = null
                whiteMask[r][c] = true
            }
        }

        val keepLeft = OfflineSymbolSignDetector.classifyGrid(
            colorMask = colorMask,
            darkMask = darkMask,
            whiteMask = whiteMask,
            dominantColor = SignRoiColor.BLUE,
            normalizedCenterX = 0.3f,
        )
        assertNotNull(keepLeft)
        assertEquals(TrafficSignCategory.KEEP_LEFT, keepLeft?.category)

        // Indoor scene with no sign colors -> must return null (zero false positives)
        val emptyIndoorColor = Array(24) { arrayOfNulls<SignRoiColor>(24) }
        val indoorNull = OfflineSymbolSignDetector.classifyGrid(
            colorMask = emptyIndoorColor,
            darkMask = darkMask,
            whiteMask = whiteMask,
            dominantColor = SignRoiColor.YELLOW,
            normalizedCenterX = 0.5f,
        )
        org.junit.Assert.assertNull(indoorNull)
    }
}
