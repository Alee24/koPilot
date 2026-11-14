package ke.co.kopilot.feature.traffic

import ke.co.kopilot.core.network.dto.RouteOptionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficAdvisorTest {
    private val advisor = TrafficAdvisor()

    @Test
    fun `only recommends alternative when provider returns meaningful time saving`() {
        val active = RouteOptionDto("r0", "Mombasa Rd", 12000, 1800, 1100, 700, "")
        val minorAlt = RouteOptionDto("r1", "Southern Bypass", 14000, 1700, 1200, 500, "")
        val majorAlt = RouteOptionDto("r2", "Expressway", 12500, 1320, 1000, 320, "")

        val noRec = advisor.evaluate(active, listOf(minorAlt))
        assertNull(noRec.recommendedAlternative)

        val rec = advisor.evaluate(active, listOf(minorAlt, majorAlt))
        assertNotNull(rec.recommendedAlternative)
        assertEquals(8, rec.estimatedMinutesSaved)
    }
}
