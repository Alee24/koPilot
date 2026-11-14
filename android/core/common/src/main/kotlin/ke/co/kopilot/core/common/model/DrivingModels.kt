package ke.co.kopilot.core.common.model

enum class GpsSignalState {
    UNAVAILABLE,
    UNCERTAIN,
    GOOD,
}

data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val horizontalAccuracyMeters: Float?,
    val bearingDegrees: Float?,
    val timestampMillis: Long,
    val isStale: Boolean = false,
) {
    val speedKmh: Float?
        get() = speedMps?.times(3.6f)

    val speedAccuracyKmh: Float?
        get() = speedAccuracyMps?.times(3.6f)
}

enum class TrafficSignCategory(val displayName: String, val speedLimitKmh: Int? = null) {
    SPEED_LIMIT_30("30 km/h Speed Limit", 30),
    SPEED_LIMIT_50("50 km/h Speed Limit", 50),
    SPEED_LIMIT_60("60 km/h Speed Limit", 60),
    SPEED_LIMIT_80("80 km/h Speed Limit", 80),
    SPEED_LIMIT_100("100 km/h Speed Limit", 100),
    SPEED_LIMIT_110("110 km/h Speed Limit", 110),
    STOP("STOP Sign"),
    GIVE_WAY("Give Way"),
    NO_ENTRY("No Entry"),
    PEDESTRIAN_CROSSING("Pedestrian Crossing"),
    SCHOOL_ZONE("School Zone"),
    ROADWORKS("Roadworks Ahead"),
    DANGEROUS_BEND("Dangerous Bend"),
    SPEED_BUMP("Speed Bump Ahead"),
    NO_OVERTAKING("No Overtaking"),
}

data class TrafficSignObservation(
    val id: String,
    val category: TrafficSignCategory,
    val confidence: Float,
    val consecutiveFrames: Int,
    val applicableToDriverLane: Boolean?,
    val timestampMillis: Long,
    val modelVersion: String,
)

enum class HazardCategory(val displayName: String) {
    VEHICLE("Vehicle"),
    MOTORCYCLE("Motorcycle"),
    BICYCLE("Bicycle"),
    PEDESTRIAN("Pedestrian"),
    ANIMAL("Animal"),
    STATIONARY_OBSTACLE("Stationary Obstacle"),
    ROAD_HAZARD("Road Hazard"),
}

data class HazardObservation(
    val trackingId: Int,
    val category: HazardCategory,
    val confidence: Float,
    val timestampMillis: Long,
    val advisoryOnly: Boolean = true,
)

enum class AlertPriority(val rank: Int) {
    ROAD_OBSERVATION(1),
    NAVIGATION_INSTRUCTION(2),
    TRAFFIC_UPDATE(3),
    WEATHER_NOTIFICATION(4),
    VEHICLE_DIAGNOSTIC(5),
    CONVERSATIONAL_RESPONSE(6),
}

data class AlertMessage(
    val id: String,
    val priority: AlertPriority,
    val spokenText: String,
    val displayText: String,
    val dedupKey: String,
    val timestampMillis: Long,
)

enum class SpeedAlertDecision {
    NO_ALERT,
    SPEED_LIMIT_OBSERVATION,
    POSSIBLE_OVERSPEED_WARNING,
    SPEED_DATA_UNAVAILABLE,
}

data class VehicleProfile(
    val id: String = "generic",
    val manufacturer: String = "Generic",
    val model: String = "Vehicle",
    val modelYear: Int? = null,
    val engineType: String = "Internal Combustion",
    val transmission: String = "Automatic",
    val fuelType: String = "Petrol",
    val obdAdapterAddress: String? = null,
    val coolantWarningThresholdC: Int = 105,
)

data class VehicleDiagnosticsState(
    val obdConnected: Boolean = false,
    val adapterName: String? = null,
    val protocolName: String? = null,
    val engineRpm: Int? = null,
    val coolantTempC: Int? = null,
    val coolantTimestampMillis: Long? = null,
    val obdSpeedKmh: Int? = null,
    val dtcCodes: List<String> = emptyList(),
    val tpmsStatusMessage: String = "Tyre pressure monitoring is not available for this vehicle.",
    val statusMessage: String = "OBD-II adapter not connected",
)
