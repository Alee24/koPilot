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
        assertEquals("Car", FrontRoadAnalyzer.classifyObjectByGeometry(0.3f, 0.4f, 0.6f, 0.65f, "Object"))
        assertEquals("Lorry / Bus", FrontRoadAnalyzer.classifyObjectByGeometry(0.2f, 0.25f, 0.65f, 0.75f, "Object"))
        assertEquals("Motorbike", FrontRoadAnalyzer.classifyObjectByGeometry(0.45f, 0.45f, 0.56f, 0.62f, "Object"))
        assertEquals("Bicycle", FrontRoadAnalyzer.classifyObjectByGeometry(0.45f, 0.45f, 0.52f, 0.55f, "Object"))
    }
}
