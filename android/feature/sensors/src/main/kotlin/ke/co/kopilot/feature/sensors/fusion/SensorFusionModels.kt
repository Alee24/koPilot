package ke.co.kopilot.feature.sensors.fusion

import ke.co.kopilot.feature.sensors.MountCalibrationStatus

enum class TerrainCategory(val label: String) {
    FLAT("Flat"),
    MILD_UPHILL("Mild Uphill"),
    STEEP_UPHILL("Steep Uphill"),
    MILD_DOWNHILL("Mild Downhill"),
    STEEP_DOWNHILL("Steep Downhill"),
    UNCERTAIN("Terrain Uncertain"),
}

enum class EvidenceSource(val label: String) {
    GPS_ELEVATION("GPS Elevation"),
    BAROMETER_TREND("Barometer"),
    VEHICLE_PITCH("Calibrated Pitch"),
    OBD_ENGINE_RESPONSE("OBD-II Engine"),
    ACCEL_IMU("Accelerometer"),
    GYRO_IMU("Gyroscope"),
    VEHICLE_SPEED("Vehicle Speed"),
}

enum class FusionAlertSeverity {
    INFO,
    ADVISORY,
    WARNING,
    URGENT,
}

data class FusionFinding(
    val eventCategory: String,
    val timestampMillis: Long,
    val monotonicMillis: Long,
    val measuredValues: Map<String, String>,
    val supportingSources: List<EvidenceSource>,
    val conflictingSources: List<EvidenceSource>,
    val sensorFreshnessSummary: String,
    val confidence: String,
    val explanation: String,
    val severity: FusionAlertSeverity,
    val recommendedAction: String,
    val suppressed: Boolean = false,
    val suppressionReason: String? = null,
)

data class FusionTimelineSample(
    val timestampMillis: Long,
    val gradientPct: Float?,
    val speedKmh: Float?,
    val rpm: Int?,
    val loadPct: Float?,
    val terrainCategory: TerrainCategory,
)

data class SensorFusionInput(
    val timestampMillis: Long,
    val monotonicMillis: Long,
    val isTripActive: Boolean,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsAltitudeMeters: Double? = null,
    val gpsHorizontalAccuracyMeters: Float? = null,
    val gpsVerticalAccuracyMeters: Float? = null,
    val gpsSpeedKmh: Float? = null,
    val gpsTimestampMillis: Long? = null,
    val baroPressureHpa: Float? = null,
    val baroTimestampMillis: Long? = null,
    val calibratedPitchDeg: Float? = null,
    val mountCalibrationStatus: MountCalibrationStatus = MountCalibrationStatus.CALIBRATION_REQUIRED,
    val phoneMovedInMount: Boolean = false,
    val verticalVibrationRmsMps2: Float = 0f,
    val accelAvailable: Boolean = false,
    val gyroAvailable: Boolean = false,
    val obdConnected: Boolean = false,
    val obdRpm: Int? = null,
    val obdLoadPct: Float? = null,
    val obdThrottlePct: Float? = null,
    val obdSpeedKmh: Float? = null,
    val obdCoolantTempC: Int? = null,
    val obdMafGps: Float? = null,
    val obdMapKpa: Int? = null,
    val obdTimestampMillis: Long? = null,
    val isCvtVehicle: Boolean = true,
)

data class TerrainEvidenceResult(
    val category: TerrainCategory,
    val gradientPct: Float?,
    val gpsGradientPct: Float?,
    val baroGradientPct: Float?,
    val pitchGradientPct: Float?,
    val baroElevationDeltaMeters: Float?,
    val supportingSources: List<EvidenceSource>,
    val conflictingSources: List<EvidenceSource>,
    val confidence: String,
    val explanation: String,
)

data class SensorFusionSnapshot(
    val terrainCategory: TerrainCategory = TerrainCategory.UNCERTAIN,
    val gradientPct: Float? = null,
    val confidence: String = "Uncertain",
    val supportingSources: List<EvidenceSource> = emptyList(),
    val conflictingSources: List<EvidenceSource> = emptyList(),
    val gpsAltitudeMeters: Double? = null,
    val gpsVerticalAccuracyMeters: Float? = null,
    val baroPressureHpa: Float? = null,
    val baroElevationTrendMeters: Float? = null,
    val calibratedPitchDeg: Float? = null,
    val mountCalibrationStatus: MountCalibrationStatus = MountCalibrationStatus.CALIBRATION_REQUIRED,
    val accelAvailable: Boolean = false,
    val gyroAvailable: Boolean = false,
    val obdConnected: Boolean = false,
    val obdRpm: Int? = null,
    val obdLoadPct: Float? = null,
    val obdThrottlePct: Float? = null,
    val obdCoolantC: Int? = null,
    val vehicleSpeedKmh: Float? = null,
    val elevationGainMeters: Double = 0.0,
    val elevationLossMeters: Double = 0.0,
    val uphillDistanceKm: Double = 0.0,
    val downhillDistanceKm: Double = 0.0,
    val uphillDurationSec: Long = 0L,
    val downhillDurationSec: Long = 0L,
    val recentFindings: List<FusionFinding> = emptyList(),
    val suppressedAlertsCount: Int = 0,
    val confirmedAdvisoriesCount: Int = 0,
    val timelineSamples: List<FusionTimelineSample> = emptyList(),
    val lastUpdatedMillis: Long = 0L,
)
