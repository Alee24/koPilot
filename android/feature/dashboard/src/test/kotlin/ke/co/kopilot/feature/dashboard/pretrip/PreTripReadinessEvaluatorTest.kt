package ke.co.kopilot.feature.dashboard.pretrip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreTripReadinessEvaluatorTest {

    @Test
    fun `returns READY when all core systems, OBD, watch, and mount calibration are verified`() {
        val summary = PreTripReadinessEvaluator.evaluateOverallReadiness(
            cameraStatus = PreTripCheckStatus.READY,
            gpsStatus = PreTripCheckStatus.READY,
            motionStatus = PreTripCheckStatus.READY,
            obdConnected = true,
            obdAbnormalCount = 0,
            watchConnected = true,
            mountCalibrated = true,
            hasDriverWellnessCaution = false,
        )
        assertEquals(OverallReadinessLevel.READY, summary.overallLevel)
        assertEquals(5, summary.readySystemsCount)
        assertTrue(summary.reasons.isEmpty())
    }

    @Test
    fun `returns READY_WITH_LIMITATIONS when optional OBD or smartwatch are disconnected`() {
        val summary = PreTripReadinessEvaluator.evaluateOverallReadiness(
            cameraStatus = PreTripCheckStatus.READY,
            gpsStatus = PreTripCheckStatus.READY,
            motionStatus = PreTripCheckStatus.READY,
            obdConnected = false,
            obdAbnormalCount = 0,
            watchConnected = false,
            mountCalibrated = true,
            hasDriverWellnessCaution = false,
        )
        assertEquals(OverallReadinessLevel.READY_WITH_LIMITATIONS, summary.overallLevel)
        assertEquals(3, summary.readySystemsCount)
        assertTrue(summary.reasons.any { it.contains("OBD-II") })
        assertTrue(summary.reasons.any { it.contains("Smartwatch") })
    }

    @Test
    fun `returns ATTENTION_REQUIRED when OBD reports abnormal coolant or DTCs`() {
        val summary = PreTripReadinessEvaluator.evaluateOverallReadiness(
            cameraStatus = PreTripCheckStatus.READY,
            gpsStatus = PreTripCheckStatus.READY,
            motionStatus = PreTripCheckStatus.READY,
            obdConnected = true,
            obdAbnormalCount = 2,
            watchConnected = true,
            mountCalibrated = true,
            hasDriverWellnessCaution = false,
        )
        assertEquals(OverallReadinessLevel.ATTENTION_REQUIRED, summary.overallLevel)
        assertTrue(summary.reasons.any { it.contains("2 abnormal") })
    }

    @Test
    fun `driver wellness reports cannot be determined when no sleep or fresh HR exists`() {
        val wellness = PreTripReadinessEvaluator.evaluateDriverWellness(
            latestSleepDurationMinutes = null,
            sleepWindowText = null,
            sleepStagesSummary = null,
            sleepConsistencyText = null,
            liveHeartRateBpm = null,
            avgHeartRateBpm = null,
            sensorContactSupported = false,
            sensorContactDetected = null,
            isHeartRateFresh = false,
        )
        assertFalse(wellness.dataSufficient)
        assertEquals("Readiness cannot be determined from available data.", wellness.summaryStatement)
    }

    @Test
    fun `driver wellness adds cautious non-diagnostic recommendation for short sleep`() {
        val wellness = PreTripReadinessEvaluator.evaluateDriverWellness(
            latestSleepDurationMinutes = 300, // 5h 0m
            sleepWindowText = "01:00 - 06:00",
            sleepStagesSummary = "Unavailable",
            sleepConsistencyText = "Below average",
            liveHeartRateBpm = 78,
            avgHeartRateBpm = 76,
            sensorContactSupported = true,
            sensorContactDetected = true,
            isHeartRateFresh = true,
        )
        assertTrue(wellness.dataSufficient)
        assertEquals(1, wellness.cautionMessages.size)
        assertTrue(wellness.cautionMessages.first().contains("under 6 hours"))
    }

    @Test
    fun `vehicle health evaluator flags high coolant, low voltage, and MIL without fabricating gearbox data`() {
        val params = PreTripReadinessEvaluator.evaluateVehicleHealthParameters(
            obdConnected = true,
            rpm = 850,
            coolantTempC = 108,
            speedKmh = 0,
            engineLoadPct = 22f,
            throttlePct = 14f,
            voltageV = 11.4f,
            fuelLevelPct = null,
            dtcCodes = listOf("P0300"),
            milOn = true,
        )
        val coolant = params.first { it.name.contains("Coolant") }
        val volt = params.first { it.name.contains("Voltage") }
        val gearbox = params.first { it.name.contains("Transmission") }
        assertTrue(coolant.isAbnormal)
        assertTrue(volt.isAbnormal)
        assertFalse(gearbox.isAvailable)
        assertTrue(gearbox.valueText.contains("Unavailable"))
    }
}
