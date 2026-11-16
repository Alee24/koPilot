package ke.co.kopilot.feature.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrontRoadAnalyzerTest {
    @Test
    fun `extracts Kenyan license plates and classifies road object geometry`() {
        assertEquals("KDA 123A", FrontRoadAnalyzer.extractKenyanPlate("Vehicle KDA 123A ahead"))
        assertEquals("KCA456B", FrontRoadAnalyzer.extractKenyanPlate("KCA456B"))
        assertNull(FrontRoadAnalyzer.extractKenyanPlate("SPEED LIMIT 50"))

        assertEquals("Pedestrian", FrontRoadAnalyzer.classifyObjectByGeometry(0.4f, 0.35f, 0.5f, 0.75f, "Object"))
        assertEquals("Vehicle", FrontRoadAnalyzer.classifyObjectByGeometry(0.3f, 0.4f, 0.6f, 0.65f, "Object"))
    }
}
