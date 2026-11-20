package ke.co.kopilot.feature.dashboard.status

import ke.co.kopilot.feature.sensors.CompassHeadingEstimator
import ke.co.kopilot.feature.sensors.CompassReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStatusBriefingEngineTest {

    private fun allConnectedInput() = SystemStatusInput(
        nowMillis = 1_700_000_000_000L,
        isMoving = false,
        cameraAvailable = true,
        cameraPermissionGranted = true,
        accelerometerAvailable = true,
        gyroscopeAvailable = true,
        mountCalibrationLabel = "Calibrated",
        gpsConnected = true,
        gpsAccuracyMeters = 5.0f,
        compassReading = CompassReading(
            degrees = 135,
            cardinal = "SE",
            fullDirectionName = "Southeast",
            formatted = "Southeast · 135°",
            source = "GPS",
            northReference = "True North",
            isReliable = true,
            interferenceSuspected = false,
        ),
        obdConnected = true,
        obdAdapterName = "ELM327 v1.5",
        obdProtocolName = "ISO 15765-4 (CAN 11/500)",
        obdRpm = 850,
        obdSpeedKmh = 0,
        obdFuelLevelPct = 62f,
        obdMafGramsPerSec = 4.8f,
        obdCoolantTempC = 86,
        obdEngineLoadPct = 24f,
        obdThrottlePct = 14f,
        obdVoltageV = 13.8f,
        obdDtcCodes = emptyList(),
        obdMilOn = false,
        obdSupportedPids = setOf("010C", "010D", "0105", "0110", "012F"),
        watchConnected = true,
        watchName = "Utarudi GS99",
        watchBatteryPct = 78,
        watchHeartRateBpm = 78,
        watchHeartRateFresh = true,
        watchSensorContact = true,
        sleepDurationMinutes = 440,
        sleepHistoryAvailable = true,
        weatherAvailable = true,
        weatherCondition = "Cloudy",
        weatherTempC = 23.0,
        weatherRainProbPct = 15,
        weatherWindKmh = 12.0,
        weatherIsStaleCache = false,
        weatherObservationAgeMinutes = 2L,
        isOnline = true,
    )

    @Test
    fun `all eight compass directions format as full spoken words`() {
        val expected = listOf(
            0 to "North",
            45 to "Northeast",
            90 to "East",
            135 to "Southeast",
            180 to "South",
            225 to "Southwest",
            270 to "West",
            315 to "Northwest",
        )
        expected.forEach { (deg, full) ->
            assertEquals(full, CompassHeadingEstimator.toFullDirectionName(deg))
        }
        assertEquals("Southeast", CompassHeadingEstimator.toFullDirectionName("SE"))
        assertEquals("Northwest", CompassHeadingEstimator.toFullDirectionName("NW"))
    }

    @Test
    fun `all devices connected produces ALL_OPERATIONAL and expands all spoken units`() {
        val report = SystemStatusBriefingEngine.buildReport(allConnectedInput())
        assertEquals(SystemReadinessLevel.ALL_OPERATIONAL, report.readiness)
        assertEquals("Southeast", report.headingSpoken)
        val speech = report.spokenBriefingFull
        assertTrue(speech.contains("Southeast"))
        assertTrue(speech.contains("850 revolutions per minute"))
        assertTrue(speech.contains("62 percent"))
        assertTrue(speech.contains("Mass airflow is 4.8 grams per second"))
        assertTrue(speech.contains("86 degrees Celsius"))
        assertTrue(speech.contains("zero kilometers per hour"))
        assertTrue(speech.contains("78 beats per minute"))
        assertTrue(speech.contains("7 hours and 20 minutes"))
        assertTrue(speech.contains("23 degrees Celsius"))
        assertTrue(speech.contains("All required systems are operational"))
        assertFalse("Raw abbreviation SE must not appear in speech", Regex("\\bSE\\b").containsMatchIn(speech))
        assertFalse("Raw abbreviation RPM must not appear in speech", Regex("\\bRPM\\b").containsMatchIn(speech))
        assertFalse("Raw abbreviation MAF must not appear in speech", Regex("\\bMAF\\b").containsMatchIn(speech))
    }

    @Test
    fun `OBD disconnected and ignition off report limitations without claiming all operational`() {
        val disc = SystemStatusBriefingEngine.buildReport(allConnectedInput().copy(obdConnected = false))
        assertEquals(SystemReadinessLevel.READY_WITH_LIMITATIONS, disc.readiness)
        assertTrue(disc.spokenBriefingFull.contains("OBD two adapter is not connected"))
        assertFalse(disc.spokenBriefingFull.contains("All required systems are operational"))

        val ignOff = SystemStatusBriefingEngine.buildReport(
            allConnectedInput().copy(obdConnected = false, obdIgnitionOffSuspected = true),
        )
        assertTrue(ignOff.spokenBriefingFull.contains("ignition is on"))
    }

    @Test
    fun `unsupported MAF and fuel PIDs announce not available from this vehicle`() {
        val report = SystemStatusBriefingEngine.buildReport(
            allConnectedInput().copy(
                obdMafGramsPerSec = null,
                obdFuelLevelPct = null,
                obdSupportedPids = setOf("010C", "010D", "0105"),
            ),
        )
        assertTrue(report.spokenBriefingFull.contains("Fuel level is not available from this vehicle"))
        assertTrue(report.spokenBriefingFull.contains("Mass airflow is not available from this vehicle"))
    }

    @Test
    fun `stale heart rate, watch disconnected, and unavailable sleep are handled honestly`() {
        val staleHr = SystemStatusBriefingEngine.buildReport(
            allConnectedInput().copy(watchHeartRateFresh = false, sleepHistoryAvailable = false, sleepDurationMinutes = null),
        )
        assertEquals(SystemReadinessLevel.READY_WITH_LIMITATIONS, staleHr.readiness)
        assertTrue(staleHr.spokenBriefingFull.contains("No fresh heart rate reading"))
        assertTrue(staleHr.spokenBriefingFull.contains("Sleep history is not available"))

        val watchDisc = SystemStatusBriefingEngine.buildReport(allConnectedInput().copy(watchConnected = false))
        assertTrue(watchDisc.spokenBriefingFull.contains("Smartwatch is not connected"))
    }

    @Test
    fun `poor compass calibration announces heading unavailable`() {
        val report = SystemStatusBriefingEngine.buildReport(
            allConnectedInput().copy(compassCalibrationPoor = true),
        )
        assertEquals("Heading unavailable", report.headingSpoken)
        assertTrue(report.spokenBriefingFull.contains("Heading unavailable"))
    }

    @Test
    fun `GPS unavailable triggers ATTENTION_REQUIRED`() {
        val report = SystemStatusBriefingEngine.buildReport(
            allConnectedInput().copy(gpsConnected = false, gpsAccuracyMeters = null),
        )
        assertEquals(SystemReadinessLevel.ATTENTION_REQUIRED, report.readiness)
        assertTrue(report.spokenBriefingFull.contains("GPS is currently unavailable"))
    }

    @Test
    fun `offline and weather unavailable announce weather unavailable clearly`() {
        val report = SystemStatusBriefingEngine.buildReport(
            allConnectedInput().copy(weatherAvailable = false, weatherCondition = null, isOnline = false),
        )
        assertEquals(SystemReadinessLevel.READY_WITH_LIMITATIONS, report.readiness)
        assertTrue(report.spokenBriefingFull.contains("Current weather information is unavailable"))
    }

    @Test
    fun `moving vehicle uses concise driving summary`() {
        val report = SystemStatusBriefingEngine.buildReport(allConnectedInput().copy(isMoving = true))
        assertTrue(report.spokenBriefingDrivingConcise.length < report.spokenBriefingFull.length)
        assertTrue(report.spokenBriefingDrivingConcise.contains("Full report available when safely stopped"))
    }
}
