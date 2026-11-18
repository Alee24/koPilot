package ke.co.kopilot.feature.diagnostics

enum class ObdConnectionState {
    DISCONNECTED,
    SCANNING,
    DEVICE_FOUND,
    PAIRING,
    CONNECTING,
    ADAPTER_INITIALIZING,
    ECU_CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
    UNSUPPORTED,
}

enum class ObdTransportType {
    UNKNOWN,
    BLUETOOTH_CLASSIC_SPP,
    BLUETOOTH_LOW_ENERGY_GATT,
}

enum class PidSupportStatus {
    SUPPORTED,
    UNSUPPORTED,
    TEMPORARILY_UNAVAILABLE,
    REQUIRES_MANUFACTURER_INTEGRATION,
}

data class ObdDiscoveredDevice(
    val name: String,
    val address: String,
    val rssiDbm: Int?,
    val isPaired: Boolean,
    val transportHint: ObdTransportType,
    val isHhobdHint: Boolean,
)

data class ObdPidCapability(
    val pidHex: String,
    val name: String,
    val unit: String,
    val status: PidSupportStatus,
    val lastValueDisplay: String? = null,
    val lastUpdatedMillis: Long? = null,
)

data class ObdLiveTelemetry(
    val adapterInfo: String? = null,
    val protocolName: String? = null,
    val transportType: ObdTransportType = ObdTransportType.UNKNOWN,
    val adapterVoltageV: Float? = null,
    val engineRpm: Int? = null,
    val vehicleSpeedKmh: Int? = null,
    val coolantTempC: Int? = null,
    val coolantHistoryC: List<Int> = emptyList(),
    val engineLoadPct: Float? = null,
    val throttlePositionPct: Float? = null,
    val intakeAirTempC: Int? = null,
    val fuelSystemStatus: String? = null,
    val shortTermFuelTrimPct: Float? = null,
    val longTermFuelTrimPct: Float? = null,
    val shortTermFuelTrimB2Pct: Float? = null,
    val longTermFuelTrimB2Pct: Float? = null,
    val fuelPressureGaugeKpa: Int? = null,
    val o2SensorVoltageV: Float? = null,
    val mafGramsPerSec: Float? = null,
    val mapKpa: Int? = null,
    val fuelLevelPct: Float? = null,
    val fuelPressureKpa: Int? = null,
    val barometricPressureKpa: Int? = null,
    val controlModuleVoltageV: Float? = null,
    val absoluteLoadPct: Float? = null,
    val relativeThrottlePct: Float? = null,
    val timingAdvanceDeg: Float? = null,
    val ambientAirTempC: Int? = null,
    val engineOilTempC: Int? = null,
    val engineFuelRateLph: Float? = null,
    val runtimeSinceStartSec: Int? = null,
    val pendingDtcCodes: List<String> = emptyList(),
    val permanentDtcCodes: List<String> = emptyList(),
    val dtcScanCompleted: Boolean = false,
    val lastDtcScanMillis: Long? = null,
    val supportedPidHexes: Set<String> = emptySet(),
    val transmissionDtcCodes: List<String> = emptyList(),
    val observedRpmSpeedRatio: Float? = null,
    val transmissionStatusNote: String = "Standard OBD-II monitors P07xx–P09xx transmission DTCs; CVT fluid temp & pressure require manufacturer-specific PIDs.",
    val lastUpdatedMillis: Long? = null,
)
