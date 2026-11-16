package ke.co.kopilot.feature.vision

import ke.co.kopilot.core.common.model.TrafficSignCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignCandidateParserTest {
    @Test
    fun `parses speed limit only with sign border color and parses regulatory text signs`() {
        // Without red/yellow border, bare number "50" should be rejected
        assertNull(SignCandidateParser.parseSignFromText("50", hasSignBorderColor = false, normalizedCenterX = 0.5f))

        // With sign border color, "50" -> SPEED_LIMIT_50
        val speed50 = SignCandidateParser.parseSignFromText("50", hasSignBorderColor = true, normalizedCenterX = 0.4f)
        assertNotNull(speed50)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_50, speed50?.category)

        // STOP sign
        val stop = SignCandidateParser.parseSignFromText("STOP", hasSignBorderColor = true, normalizedCenterX = 0.3f)
        assertEquals(TrafficSignCategory.STOP, stop?.category)

        // SCHOOL ZONE
        val school = SignCandidateParser.parseSignFromText("SCHOOL ZONE", hasSignBorderColor = false, normalizedCenterX = 0.6f)
        assertEquals(TrafficSignCategory.SCHOOL_ZONE, school?.category)
    }
}
