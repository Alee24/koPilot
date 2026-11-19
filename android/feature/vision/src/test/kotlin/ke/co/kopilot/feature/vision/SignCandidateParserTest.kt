package ke.co.kopilot.feature.vision

import ke.co.kopilot.core.common.model.TrafficSignCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignCandidateParserTest {

    @Test
    fun `parses international and Kenyan speed limits accurately and marks unbordered bare numbers as uncertain`() {
        // Red ring + white interior + "30" -> high confidence SPEED_LIMIT_30
        val speed30 = SignCandidateParser.parseSignFromText(
            rawText = "30",
            hasSignBorderColor = true,
            normalizedCenterX = 0.4f,
            hasWhiteInterior = true,
        )
        assertNotNull(speed30)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_30, speed30?.category)
        assertTrue((speed30?.confidence ?: 0f) >= 0.85f)

        // 90 and 120 km/h international speed limits
        val speed90 = SignCandidateParser.parseSignFromText("90", hasSignBorderColor = true, normalizedCenterX = 0.2f, hasWhiteInterior = true)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_90, speed90?.category)
        val speed120 = SignCandidateParser.parseSignFromText("120", hasSignBorderColor = true, normalizedCenterX = 0.8f, hasWhiteInterior = true)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_120, speed120?.category)

        // OCR confusion "3O" with red ring -> still SPEED_LIMIT_30
        val speed3O = SignCandidateParser.parseSignFromText("3O", hasSignBorderColor = true, normalizedCenterX = 0.4f, hasWhiteInterior = true)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_30, speed3O?.category)

        // Bare "50" on a white wall WITHOUT a red sign border -> low confidence (< 0.60f, uncertain) to prevent false positives
        val bareWhiteWall50 = SignCandidateParser.parseSignFromText(
            rawText = "50",
            hasSignBorderColor = false,
            normalizedCenterX = 0.5f,
            hasWhiteInterior = true,
        )
        assertNotNull(bareWhiteWall50)
        assertTrue("Bare number without sign border must be uncertain (<0.60)", (bareWhiteWall50?.confidence ?: 1f) < 0.60f)

        // Explicit "50KM/H" on white sign face -> confirmed SPEED_LIMIT_50
        val speed50Km = SignCandidateParser.parseSignFromText("50KM/H", hasSignBorderColor = false, normalizedCenterX = 0.5f, hasWhiteInterior = true)
        assertEquals(TrafficSignCategory.SPEED_LIMIT_50, speed50Km?.category)
        assertTrue((speed50Km?.confidence ?: 0f) >= 0.80f)

        // Solid red STOP sign -> STOP
        val stop = SignCandidateParser.parseSignFromText("STOP", hasSignBorderColor = true, normalizedCenterX = 0.3f, hasWhiteInterior = false)
        assertEquals(TrafficSignCategory.STOP, stop?.category)

        // SCHOOL ZONE
        val school = SignCandidateParser.parseSignFromText("SCHOOL ZONE", hasSignBorderColor = false, normalizedCenterX = 0.6f)
        assertEquals(TrafficSignCategory.SCHOOL_ZONE, school?.category)
    }

    @Test
    fun `rejects speed limit stickers on buses, distance markers, route numbers, billboards, and registration plates`() {
        // Speed limit sticker painted on the rear of a bus/truck (isOnVehicleBody = true) -> rejected
        val busSticker80 = SignCandidateParser.parseSignFromText(
            rawText = "80 KPH",
            hasSignBorderColor = true,
            normalizedCenterX = 0.5f,
            hasWhiteInterior = true,
            isOnVehicleBody = true,
        )
        assertNull(busSticker80)

        val vehicles = listOf(
            TrackedRoadObject(
                trackingId = 1,
                label = "Lorry / Bus",
                confidence = 0.9f,
                normalizedLeft = 0.30f,
                normalizedTop = 0.25f,
                normalizedRight = 0.70f,
                normalizedBottom = 0.80f,
            ),
        )
        assertTrue(SignCandidateParser.isBoxInsideDetectedVehicle(0.45f, 0.50f, 0.55f, 0.60f, vehicles))
        assertFalse(SignCandidateParser.isBoxInsideDetectedVehicle(0.05f, 0.20f, 0.15f, 0.30f, vehicles))

        // Distance markers, highway codes, and percentages must never trigger speed limit signs
        assertNull(SignCandidateParser.parseSignFromText("50M", hasSignBorderColor = true, normalizedCenterX = 0.4f))
        assertNull(SignCandidateParser.parseSignFromText("100 METRES", hasSignBorderColor = true, normalizedCenterX = 0.4f))
        assertNull(SignCandidateParser.parseSignFromText("A104", hasSignBorderColor = true, normalizedCenterX = 0.4f))
        assertNull(SignCandidateParser.parseSignFromText("10%", hasSignBorderColor = true, normalizedCenterX = 0.4f))

        // Kenyan & international license plates must NEVER be misclassified as road signs
        assertNull(SignCandidateParser.parseSignFromText("KDM", hasSignBorderColor = true, normalizedCenterX = 0.5f, hasWhiteInterior = false))
        assertNull(SignCandidateParser.parseSignFromText("KDM 632A", hasSignBorderColor = true, normalizedCenterX = 0.5f, hasWhiteInterior = false))
        assertNull(SignCandidateParser.parseSignFromText("UAB 450C", hasSignBorderColor = true, normalizedCenterX = 0.5f, hasWhiteInterior = false))
        assertNull(SignCandidateParser.parseSignFromText("AB12 CDE", hasSignBorderColor = true, normalizedCenterX = 0.5f, hasWhiteInterior = false))
    }
}
