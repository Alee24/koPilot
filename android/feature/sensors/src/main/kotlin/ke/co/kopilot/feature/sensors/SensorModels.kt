package ke.co.kopilot.feature.sensors

enum class SensorCategory {
    MOTION,
    ENVIRONMENTAL,
    POSITION,
    OTHER,
}

enum class SensorSamplingProfile(val label: String, val motionDelayUs: Int, val envDelayUs: Int) {
    IDLE("Idle (Power Saver)", 200_000, 1_000_000),
    DRIVING("Driving (Active)", 20_000, 500_000),
    HIGH_PRECISION("High Precision", 10_000, 200_000),
}

enum class SensorConnectionStatus {
    ACTIVE,
    IDLE,
    TRIGGER_ARMED,
    UNAVAILABLE,
}

data class PhysicalSensorReading(
    val sensorType: Int,
    val typeName: String,
    val category: SensorCategory,
    val name: String,
    val vendor: String,
    val isAvailable: Boolean,
    val status: SensorConnectionStatus,
    val values: List<Float> = emptyList(),
    val formattedReading: String = "Unavailable",
    val unit: String = "",
    val timestampMillis: Long = 0L,
    val accuracyLabel: String = "Unknown",
    val samplingHz: Float = 0f,
    val powerMa: Float = 0f,
    val calibrationNote: String = "N/A",
)

data class CameraCapabilitySummary(
    val cameraId: String,
    val lensFacing: String,
    val focalLengthsMm: List<Float>,
    val autofocusSupported: Boolean,
    val opticalStabilizationSupported: Boolean,
    val videoStabilizationSupported: Boolean,
)

data class AudioCapabilitySummary(
    val microphoneFeaturePresent: Boolean,
    val inputDevices: List<String>,
    val outputDevices: List<String>,
    val bluetoothAudioConnected: Boolean,
)

data class ConnectivityCapabilities(
    val bluetoothClassicSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val bleSupported: Boolean,
    val wifiSupported: Boolean,
    val wifiConnected: Boolean,
    val cellularAvailable: Boolean,
    val usbHostSupported: Boolean,
    val usbAccessorySupported: Boolean,
    val attachedUsbDeviceCount: Int,
    val nfcSupported: Boolean,
    val nfcEnabled: Boolean,
)

enum class LongitudinalMotionState {
    STEADY,
    ACCELERATING,
    HARD_ACCELERATION,
    BRAKING,
    HARSH_BRAKING,
}

enum class LateralMotionState {
    STRAIGHT,
    LANE_SHIFT_LEFT,
    LANE_SHIFT_RIGHT,
    LEFT_TURN,
    RIGHT_TURN,
    SHARP_CORNERING,
}

enum class RoadVibrationLevel {
    UNKNOWN,
    SMOOTH,
    MODERATE_VIBRATION,
    ROUGH_ROAD_EXPERIMENTAL,
    VERY_ROUGH,
}

data class RoadVibrationSample(
    val timestampMillis: Long,
    val rmsMps2: Float,
    val smoothnessScore: Int,
    val level: RoadVibrationLevel,
    val isBumpSpike: Boolean = false,
)

data class DrivingBehaviourStats(
    val roadSmoothnessScore: Int = 100,
    val averageRoadScore: Int = 100,
    val smoothPct: Int = 100,
    val moderatePct: Int = 0,
    val roughPct: Int = 0,
    val veryRoughPct: Int = 0,
    val potholeBumpCount: Int = 0,
    val brakingCount: Int = 0,
    val harshBrakingCount: Int = 0,
    val accelerationCount: Int = 0,
    val hardAccelerationCount: Int = 0,
    val sharpCornerCount: Int = 0,
    val driverBehaviourScore: Int = 100,
    val recentSamples: List<RoadVibrationSample> = emptyList(),
)

data class DrivingMotionAnalysis(
    val longitudinalState: LongitudinalMotionState = LongitudinalMotionState.STEADY,
    val lateralState: LateralMotionState = LateralMotionState.STRAIGHT,
    val longitudinalAccelMps2: Float = 0f,
    val lateralAccelMps2: Float = 0f,
    val verticalVibrationRmsMps2: Float = 0f,
    val yawRateRadPerSec: Float = 0f,
    val roadVibrationLevel: RoadVibrationLevel = RoadVibrationLevel.UNKNOWN,
    val suddenMovementDetected: Boolean = false,
    val phoneMovementDetected: Boolean = false,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val azimuthDegrees: Float = 0f,
    val behaviourStats: DrivingBehaviourStats = DrivingBehaviourStats(),
    val updatedAtMillis: Long = 0L,
)
