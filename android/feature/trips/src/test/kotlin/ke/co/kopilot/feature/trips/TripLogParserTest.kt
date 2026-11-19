package ke.co.kopilot.feature.trips

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TripLogParserTest {
    @Test
    fun `parses GPS track points and compact trip summary`() {
        val temp = File.createTempFile("kopilot_trip_test", ".txt")
        try {
            temp.writeText(
                """
                === KoPilot Trip Activity Log ===
                [10:00:00] [TRIP_START] Trip started | Origin: Current Location | Destination: Free Drive | GPS=-1.29210,36.82190
                [10:00:10] [GPS_TRACK] -1.291000,36.822500
                [10:00:20] [GPS_TRACK] -1.289500,36.824000
                [10:05:00] [TRIP_SUMMARY] Fuel=0.45 L (7.2 L/100km) | Battery=13.9V (12.4-14.1V) | Summary=Safety 94/100 • Road Smooth (91/100)
                """.trimIndent(),
            )
            val parsed = TripLogParser.parse(temp)
            assertEquals(3, parsed.routePoints.size)
            assertEquals(-1.29210, parsed.routePoints[0].latitude, 1e-4)
            assertEquals(36.824000, parsed.routePoints[2].longitude, 1e-4)
            assertEquals("0.45 L (7.2 L/100km)", parsed.fuelConsumption)
            assertEquals("13.9V (12.4-14.1V)", parsed.batteryVoltage)
            assertEquals("Safety 94/100 • Road Smooth (91/100)", parsed.overallSummary)
        } finally {
            temp.delete()
        }
    }
}
