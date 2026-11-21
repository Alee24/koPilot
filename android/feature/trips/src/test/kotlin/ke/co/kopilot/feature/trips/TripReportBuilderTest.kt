package ke.co.kopilot.feature.trips

import ke.co.kopilot.core.database.entity.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TripReportBuilderTest {

    @Test
    fun `complete trip with GPS, OBD, HR, Weather and dismissed bump builds full honest report`() {
        val started = 1_700_000_000_000L
        val trip = TripEntity(
            id = "t1",
            originLabel = "Nairobi",
            destinationLabel = "Westlands",
            startedAtMillis = started,
            endedAtMillis = started + 900_000L,
            distanceKm = 12.4,
            durationSeconds = 900L,
            maxSpeedKmh = 72f,
            avgSpeedKmh = 49f,
            signsDetectedCount = 4,
            alertsTriggeredCount = 2,
            avgHeartRateBpm = 78,
            minHeartRateBpm = 68,
            maxHeartRateBpm = 94,
            heartRateSamplesCount = 12,
        )
        val temp = File.createTempFile("trip_report_complete", ".txt")
        try {
            temp.writeText(
                """
                === KoPilot Trip Activity Log ===
                [10:00:00] [SENSORS_ACTIVE] Camera=true, GPS=true, Motion=true, OBD=true
                [10:00:05] [GPS_TRACK] -1.292100,36.821900 spd=42.0 acc=5.0
                [10:00:10] [OBD_SAMPLE] RPM=1850 SPD=43 CLT=86 MAF=4.20 VOLT=13.9
                [10:00:15] [HR_SAMPLE] BPM=78
                [10:00:20] [WEATHER] Cond=Partly cloudy | Temp=23°C | Source=Open-Meteo
                [10:02:00] [ROAD_DISMISSED] Ordinary road bump | Vehicle continued moving normally
                [10:15:00] [ROAD_QUALITY] Avg Score=88/100
                [10:15:00] [DRIVING_BEHAVIOUR] Driver Score=92/100
                [10:15:00] [TRIP_SUMMARY] Fuel=1.10 L (8.8 L/100km) | Battery=13.9V (13.6-14.1V) | Summary=Safety 92/100
                """.trimIndent(),
            )
            val report = TripReportBuilder.build(trip, temp)
            assertEquals("Completed", report.statusLabel)
            assertEquals("No suspected accidents recorded during monitored periods", report.accidentBannerTitle)
            assertTrue(report.routePoints.isNotEmpty())
            assertTrue(report.samples.size >= 3)
            assertTrue(report.scoreBreakdown.overallScore != null)
            assertEquals(MeasurementProvenance.DIRECTLY_MEASURED, report.obdMetrics.first().provenance)
            assertEquals(MeasurementProvenance.DIRECTLY_MEASURED, report.wellnessMetrics.first().provenance)
            assertEquals(MeasurementProvenance.PROVIDER_REPORTED, report.weatherMetrics.first().provenance)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `GPS-only trip without OBD, Watch, or Weather marks missing metrics as Unavailable`() {
        val started = 1_700_000_000_000L
        val trip = TripEntity(
            id = "t2",
            originLabel = null,
            destinationLabel = null,
            startedAtMillis = started,
            endedAtMillis = started + 300_000L,
            distanceKm = 3.2,
            durationSeconds = 300L,
            maxSpeedKmh = 50f,
            avgSpeedKmh = 38f,
            signsDetectedCount = 0,
            alertsTriggeredCount = 0,
        )
        val report = TripReportBuilder.build(trip, null)
        assertEquals("Current Location", report.originLabel)
        assertEquals("Local Drive", report.destinationLabel)
        assertNull(report.elevationGainMeters)
        assertNull(report.scoreBreakdown.overallScore)
        assertEquals(MeasurementProvenance.UNAVAILABLE, report.obdMetrics.first().provenance)
        assertEquals("Unavailable", report.obdMetrics.first().formattedValue)
        assertEquals(MeasurementProvenance.UNAVAILABLE, report.wellnessMetrics.first().provenance)
        assertEquals(MeasurementProvenance.UNAVAILABLE, report.weatherMetrics.first().provenance)
    }
}
