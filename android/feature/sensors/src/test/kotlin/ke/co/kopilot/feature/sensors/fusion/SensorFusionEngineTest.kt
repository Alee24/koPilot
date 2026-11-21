package ke.co.kopilot.feature.sensors.fusion

import ke.co.kopilot.feature.sensors.MountCalibrationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorFusionEngineTest {

    @Test
    fun `flat road with high engine load is not misclassified as uphill`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var snap = SensorFusionSnapshot()
        for (i in 0 until 12) {
            val t = i * 1000L
            lat += 0.00018 // ~20m per second (~72 km/h)
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = 1660.0,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = 6f,
                    gpsSpeedKmh = 72f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = 835.0f,
                    baroTimestampMillis = 1_700_000_000_000L + t,
                    calibratedPitchDeg = 0.1f,
                    mountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
                    accelAvailable = true,
                    gyroAvailable = true,
                    obdConnected = true,
                    obdRpm = 3200,
                    obdLoadPct = 82f,
                    obdThrottlePct = 65f,
                    obdSpeedKmh = 72f,
                    obdTimestampMillis = 1_700_000_000_000L + t,
                ),
            )
        }
        assertEquals(TerrainCategory.FLAT, snap.terrainCategory)
        assertTrue(snap.recentFindings.any { it.eventCategory == "FLAT_HIGH_LOAD" && !it.suppressed })
        assertFalse(snap.recentFindings.any { it.eventCategory == "TERRAIN_STEEP_UPHILL" })
    }

    @Test
    fun `gentle uphill with low engine load classifies uphill without claiming engine struggle`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var alt = 1660.0
        var baro = 835.0f
        var snap = SensorFusionSnapshot()
        for (i in 0 until 12) {
            val t = i * 1000L
            lat += 0.00015 // ~16.7m/s (~60 km/h)
            alt += 0.5 // ~3.0% grade
            baro -= 0.059f
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = alt,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = 6f,
                    gpsSpeedKmh = 60f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = baro,
                    baroTimestampMillis = 1_700_000_000_000L + t,
                    calibratedPitchDeg = 1.8f, // ~3.1%
                    mountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
                    obdConnected = true,
                    obdRpm = 1900,
                    obdLoadPct = 32f,
                    obdSpeedKmh = 60f,
                    obdTimestampMillis = 1_700_000_000_000L + t,
                ),
            )
        }
        assertEquals(TerrainCategory.MILD_UPHILL, snap.terrainCategory)
        assertTrue(snap.supportingSources.size >= 2)
        assertFalse(snap.recentFindings.any { it.explanation.contains("elevated", ignoreCase = true) })
    }

    @Test
    fun `steep uphill with elevated load and cvt rpm confirms steep uphill without transmission fault`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var alt = 1660.0
        var baro = 835.0f
        var snap = SensorFusionSnapshot()
        for (i in 0 until 12) {
            val t = i * 1000L
            lat += 0.00015 // ~16.7m/s
            alt += 1.2 // ~7.2% steep grade
            baro -= 0.142f
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = alt,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = 6f,
                    gpsSpeedKmh = 55f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = baro,
                    baroTimestampMillis = 1_700_000_000_000L + t,
                    calibratedPitchDeg = 4.1f,
                    mountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
                    obdConnected = true,
                    obdRpm = 3800,
                    obdLoadPct = 78f,
                    obdThrottlePct = 58f,
                    obdSpeedKmh = 55f,
                    obdTimestampMillis = 1_700_000_000_000L + t,
                    isCvtVehicle = true,
                ),
            )
        }
        assertEquals(TerrainCategory.STEEP_UPHILL, snap.terrainCategory)
        val uphillFinding = snap.recentFindings.firstOrNull { it.eventCategory == "TERRAIN_STEEP_UPHILL" }
        assertNotNull(uphillFinding)
        assertFalse(uphillFinding!!.suppressed)
        assertTrue(uphillFinding.explanation.contains("CVT", ignoreCase = true))
        assertFalse(uphillFinding.explanation.contains("transmission problem", ignoreCase = true))
    }

    @Test
    fun `steep downhill with low throttle confirms downhill advisory`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var alt = 1750.0
        var baro = 825.0f
        var snap = SensorFusionSnapshot()
        for (i in 0 until 12) {
            val t = i * 1000L
            lat += 0.00015
            alt -= 1.2 // -7.2%
            baro += 0.142f
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = alt,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = 6f,
                    gpsSpeedKmh = 60f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = baro,
                    baroTimestampMillis = 1_700_000_000_000L + t,
                    calibratedPitchDeg = -4.0f,
                    mountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
                    obdConnected = true,
                    obdRpm = 1400,
                    obdLoadPct = 12f,
                    obdThrottlePct = 4f,
                    obdSpeedKmh = 60f,
                    obdTimestampMillis = 1_700_000_000_000L + t,
                ),
            )
        }
        assertEquals(TerrainCategory.STEEP_DOWNHILL, snap.terrainCategory)
        assertTrue(snap.recentFindings.any { it.eventCategory == "TERRAIN_STEEP_DOWNHILL" && !it.suppressed })
    }

    @Test
    fun `speed bumps potholes and rough vibration do not trigger false terrain transitions`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var snap = SensorFusionSnapshot()
        for (i in 0 until 12) {
            val t = i * 1000L
            lat += 0.00015
            val bumpPitch = if (i == 6 || i == 7) 6.5f else 0.0f
            val vibRms = if (i in 5..9) 3.4f else 0.4f
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = 1660.0,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = 6f,
                    gpsSpeedKmh = 45f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = 835.0f,
                    baroTimestampMillis = 1_700_000_000_000L + t,
                    calibratedPitchDeg = bumpPitch,
                    mountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
                    verticalVibrationRmsMps2 = vibRms,
                ),
            )
        }
        assertEquals(TerrainCategory.FLAT, snap.terrainCategory)
        assertEquals(0, snap.confirmedAdvisoriesCount)
    }

    @Test
    fun `phone movement in mount invalidates pitch until recalibrated`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var snap = SensorFusionSnapshot()
        for (i in 0 until 10) {
            val t = i * 1000L
            lat += 0.00015
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = 1660.0,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = 6f,
                    gpsSpeedKmh = 55f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = null, // barometer unavailable
                    calibratedPitchDeg = 8.0f, // bogus pitch after mount shift
                    mountCalibrationStatus = MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED,
                    phoneMovedInMount = true,
                ),
            )
        }
        assertEquals(TerrainCategory.UNCERTAIN, snap.terrainCategory)
        assertFalse(EvidenceSource.VEHICLE_PITCH in snap.supportingSources)
    }

    @Test
    fun `gps altitude jumps and poor vertical accuracy are rejected`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var snap = SensorFusionSnapshot()
        for (i in 0 until 10) {
            val t = i * 1000L
            lat += 0.00015
            val alt = if (i == 5) 1740.0 else 1660.0 // 80m spike!
            val vAcc = if (i >= 7) 42f else 6f // poor vertical accuracy
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = alt,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = vAcc,
                    gpsSpeedKmh = 55f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = 835.0f,
                    baroTimestampMillis = 1_700_000_000_000L + t,
                    calibratedPitchDeg = 0.0f,
                    mountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
                ),
            )
        }
        assertEquals(TerrainCategory.FLAT, snap.terrainCategory)
        assertEquals(0, snap.confirmedAdvisoriesCount)
    }

    @Test
    fun `barometer unavailable and obd disconnected still classifies terrain from gps plus pitch without inventing values`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var alt = 1660.0
        var snap = SensorFusionSnapshot()
        for (i in 0 until 12) {
            val t = i * 1000L
            lat += 0.00015
            alt += 1.0 // ~6%
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = alt,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = 6f,
                    gpsSpeedKmh = 55f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = null,
                    baroTimestampMillis = null,
                    calibratedPitchDeg = 3.5f,
                    mountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
                    obdConnected = false,
                ),
            )
        }
        assertEquals(TerrainCategory.STEEP_UPHILL, snap.terrainCategory)
        assertNull(snap.baroPressureHpa)
        assertNull(snap.obdRpm)
        assertNull(snap.obdLoadPct)
        assertTrue(snap.supportingSources.containsAll(listOf(EvidenceSource.GPS_ELEVATION, EvidenceSource.VEHICLE_PITCH)))
    }

    @Test
    fun `conflicting terrain sensors mark uncertain and suppress unsupported advisory`() {
        val engine = SensorFusionEngine()
        var lat = -1.2921
        var alt = 1660.0
        var baro = 835.0f
        var snap = SensorFusionSnapshot()
        for (i in 0 until 10) {
            val t = i * 1000L
            lat += 0.00015
            alt += 1.2 // GPS says +7% uphill
            baro += 0.15f // Baro says -7% downhill
            snap = engine.process(
                SensorFusionInput(
                    timestampMillis = 1_700_000_000_000L + t,
                    monotonicMillis = t,
                    isTripActive = true,
                    gpsLatitude = lat,
                    gpsLongitude = 36.8219,
                    gpsAltitudeMeters = alt,
                    gpsHorizontalAccuracyMeters = 5f,
                    gpsVerticalAccuracyMeters = 6f,
                    gpsSpeedKmh = 55f,
                    gpsTimestampMillis = 1_700_000_000_000L + t,
                    baroPressureHpa = baro,
                    baroTimestampMillis = 1_700_000_000_000L + t,
                    calibratedPitchDeg = 0.0f, // Pitch says flat
                    mountCalibrationStatus = MountCalibrationStatus.CALIBRATED,
                ),
            )
        }
        assertEquals(TerrainCategory.UNCERTAIN, snap.terrainCategory)
        assertTrue(snap.suppressedAlertsCount >= 1)
    }

    @Test
    fun `stationary and low speed climbing remain uncertain while urgent coolant fault is never suppressed`() {
        val engine = SensorFusionEngine()
        val snap = engine.process(
            SensorFusionInput(
                timestampMillis = 1_700_000_000_000L,
                monotonicMillis = 1_000L,
                isTripActive = true,
                gpsLatitude = -1.2921,
                gpsLongitude = 36.8219,
                gpsAltitudeMeters = 1660.0,
                gpsSpeedKmh = 2f, // stationary / very low speed
                gpsTimestampMillis = 1_700_000_000_000L,
                baroPressureHpa = null,
                mountCalibrationStatus = MountCalibrationStatus.CALIBRATION_REQUIRED,
                obdConnected = true,
                obdCoolantTempC = 105, // urgent directly measured fault
                obdSpeedKmh = 2f,
                obdTimestampMillis = 1_700_000_000_000L,
            ),
        )
        assertEquals(TerrainCategory.UNCERTAIN, snap.terrainCategory)
        assertNull(snap.gradientPct)
        val urgent = snap.recentFindings.firstOrNull { it.eventCategory == "ENGINE_TEMPERATURE_HIGH" }
        assertNotNull(urgent)
        assertEquals(FusionAlertSeverity.URGENT, urgent!!.severity)
        assertFalse(urgent.suppressed)
    }
}
