package ke.co.kopilot.feature.navigation

import com.google.android.gms.maps.model.LatLng
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

    @Test
    fun `encodePolyline and decodePolyline round-trip coordinates`() {
        val original = listOf(
            LatLng(-1.29210, 36.82190),
            LatLng(-1.28333, 36.81667),
            LatLng(-0.28333, 36.06667),
        )
        val encoded = NavigationRepository.encodePolyline(original)
        val decoded = NavigationRepository.decodePolyline(encoded)
        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (exp, act) ->
            assertEquals(exp.latitude, act.latitude, 1e-4)
            assertEquals(exp.longitude, act.longitude, 1e-4)
        }
    }

    @Test
    fun `parseNominatimPlaces parses OpenStreetMap search JSON`() {
        val raw = """
            [
              {
                "place_id": 123456,
                "lat": "-1.286389",
                "lon": "36.817223",
                "name": "Nairobi CBD",
                "display_name": "Nairobi CBD, Nairobi County, Kenya"
              }
            ]
        """.trimIndent()
        val places = NavigationRepository.parseNominatimPlaces(raw)
        assertEquals(1, places.size)
        assertEquals("osm_123456", places[0].placeId)
        assertEquals("Nairobi CBD", places[0].name)
        assertEquals(-1.286389, places[0].latitude, 1e-6)
        assertEquals(36.817223, places[0].longitude, 1e-6)
    }

    @Test
    fun `parseOsrmRoutes parses OSRM GeoJSON route and steps`() {
        val raw = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 14250.5,
                  "duration": 980.2,
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [36.8219, -1.2921],
                      [36.8167, -1.2833],
                      [36.8050, -1.2650]
                    ]
                  },
                  "legs": [
                    {
                      "summary": "Uhuru Highway",
                      "steps": [
                        {
                          "name": "Kenyatta Avenue",
                          "distance": 450.0,
                          "duration": 45.0,
                          "maneuver": { "type": "depart", "modifier": "straight" }
                        },
                        {
                          "name": "Uhuru Highway",
                          "distance": 13800.5,
                          "duration": 935.2,
                          "maneuver": { "type": "turn", "modifier": "left" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val routes = NavigationRepository.parseOsrmRoutes(raw)
        assertEquals(1, routes.size)
        val route = routes[0]
        assertEquals("Via Uhuru Highway (OSM)", route.summary)
        assertEquals(14250, route.distanceMeters)
        assertEquals(980, route.durationSeconds)
        assertEquals(2, route.steps.size)
        assertEquals("Start on Kenyatta Avenue", route.steps[0].instruction)
        assertEquals("Turn left onto Uhuru Highway", route.steps[1].instruction)
        val decoded = NavigationRepository.decodePolyline(route.encodedPolyline)
        assertEquals(3, decoded.size)
        assertEquals(-1.2921, decoded[0].latitude, 1e-4)
        assertEquals(36.8219, decoded[0].longitude, 1e-4)
    }
}
