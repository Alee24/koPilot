package ke.co.kopilot.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRepositoryTest {
    @Test
    fun `polyline decoder decodes standard Google polyline`() {
        val points = NavigationRepository.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertTrue(points[0].latitude > 38.0)
    }
}
