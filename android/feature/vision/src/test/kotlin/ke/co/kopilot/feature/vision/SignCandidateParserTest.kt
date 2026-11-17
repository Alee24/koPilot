package ke.co.kopilot.feature.vision

import ke.co.kopilot.core.common.model.TrafficSignCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignCandidateParserTest {
    @Test
    fun `parses 30 speed limit accurately without confusing with STOP and marks low-confidence as unsure`() {
        // Red ring + white interior + "30" -> high confidence SPEED_LIMIT_30
        val speed30 = SignCandidateParser.parseSignFromText("30", hasSignBorderColor = true, normalizedCenterX = 0.4f, hasWhiteInterior = true)
        assertNotNull(speed30)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_30, speed30?.category)
        assertEquals(true, (speed30?.confidence ?: 0f) >= 0.60f)

        // OCR confusion "3O" with red ring -> still SPEED_LIMIT_30
        val speed3O = SignCandidateParser.parseSignFromText("3O", hasSignBorderColor = true, normalizedCenterX = 0.4f, hasWhiteInterior = true)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_30, speed3O?.category)

        // Unclear bare "30" without border or white interior -> low confidence (< 60%) for auto-zoom & "unsure" feedback
        val unsure30 = SignCandidateParser.parseSignFromText("30", hasSignBorderColor = false, normalizedCenterX = 0.5f, hasWhiteInterior = false)
        assertNotNull(unsure30)
        assertEquals(true, (unsure30?.confidence ?: 1f) < 0.60f)

        // Compact "50KM/H" without space -> SPEED_LIMIT_50
        val speed50Km = SignCandidateParser.parseSignFromText("50KM/H", hasSignBorderColor = false, normalizedCenterX = 0.5f, hasWhiteInterior = true)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_50, speed50Km?.category)

        // Solid red STOP sign -> STOP
        val stop = SignCandidateParser.parseSignFromText("STOP", hasSignBorderColor = true, normalizedCenterX = 0.3f, hasWhiteInterior = false)
        assertEquals(TrafficSignCategory.STOP, stop?.category)

        // SCHOOL ZONE
        val school = SignCandidateParser.parseSignFromText("SCHOOL ZONE", hasSignBorderColor = false, normalizedCenterX = 0.6f)
        assertEquals(TrafficSignCategory.SCHOOL_ZONE, school?.category)
    }
}
