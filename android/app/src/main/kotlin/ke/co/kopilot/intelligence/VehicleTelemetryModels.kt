package ke.co.kopilot.intelligence

enum class MeasurementValidity(val label: String) {
    AVAILABLE("Available"),
    UNSUPPORTED("Unsupported"),
    TEMPORARILY_UNAVAILABLE("Temporarily Unavailable"),
    STALE("Stale"),
    INVALID("Invalid"),
    NOT_YET_CHECKED("Not Yet Checked"),
}

data class MeasurementReading<T>(
    val pidOrSensorId: String,
    val name: String,
    val value: T?,
    val unit: String,
    val timestampMillis: Long?,
    val source: String,
    val validity: MeasurementValidity,
    val confidence: Float = 1.0f,
) {
    fun formatValue(): String = when {
        validity != MeasurementValidity.AVAILABLE || value == null -> validity.label
        value is Float -> "${"%.1f".format(value)} $unit".trim()
        else -> "$value $unit".trim()
    }
}

enum class DtcScanStatus(val label: String) {
    NOT_SCANNED("Not Scanned"),
    SCANNED_CLEAN("Scanned — 0 Codes Found"),
    SCANNED_FAULTS_FOUND("Scanned — Fault Codes Present"),
    UNAVAILABLE("Scan Unavailable (OBD Disconnected)"),
}

data class DtcInterpretation(
    val code: String,
    val severityLabel: String,
    val description: String,
    val possibleCauses: String,
    val suggestedChecks: String,
    val isPending: Boolean = false,
    val isPermanent: Boolean = false,
)

data class VehicleDiagnosticsSnapshot(
    val storedCodes: List<String>,
    val pendingCodes: List<String>,
    val permanentCodes: List<String>,
    val scanStatus: DtcScanStatus,
    val lastScanMillis: Long?,
)

data class PhoneSensorSnapshot(
    val gpsAvailable: Boolean,
    val gpsSpeedKmh: Float?,
    val gpsAccuracyMeters: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val roadSmoothnessScore: Int,
    val roadVibrationRmsMps2: Float,
    val drivingSafetyScore: Int,
    val fuelEfficiencyScore: Int,
    val drivingStateLabel: String,
    val lastSignDetected: String?,
    val lastSignConfidence: Float?,
)

data class VehicleSnapshot(
    val timestampIso: String,
    val timestampMillis: Long,
    val vehicleIdentity: String,
    val obdConnected: Boolean,
    val obdProtocol: String?,
    val adapterInfo: String?,
    val supportedPidHexes: Set<String>,
    val engineReadings: Map<String, MeasurementReading<Float>>,
    val fuelSystemStatus: MeasurementReading<String>,
    val phoneSensors: PhoneSensorSnapshot,
    val diagnostics: VehicleDiagnosticsSnapshot,
) {
    fun reading(pidHex: String): MeasurementReading<Float>? = engineReadings[pidHex]

    fun availableCount(): Int = engineReadings.values.count { it.validity == MeasurementValidity.AVAILABLE }
    fun unsupportedCount(): Int = engineReadings.values.count { it.validity == MeasurementValidity.UNSUPPORTED }
}

data class FullVehicleHealthReport(
    val generatedAtIso: String,
    val vehicleIdentity: String,
    val monitoringStatus: String,
    val engineObservations: List<String>,
    val electricalObservations: List<String>,
    val fuelObservations: List<String>,
    val diagnosticFindings: List<String>,
    val recentTrends: List<String>,
    val potentialConcerns: List<String>,
    val recommendedChecks: List<String>,
    val missingOrUnsupported: List<String>,
    val spokenSummary: String,
    val detailedText: String,
)
