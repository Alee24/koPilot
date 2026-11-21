package ke.co.kopilot.feature.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.HandlerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import ke.co.kopilot.feature.location.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class SensorHubState(
    val isMonitoring: Boolean = false,
    val samplingProfile: SensorSamplingProfile = SensorSamplingProfile.IDLE,
    val physicalSensors: List<PhysicalSensorReading> = emptyList(),
    val cameras: List<CameraCapabilitySummary> = emptyList(),
    val audio: AudioCapabilitySummary? = null,
    val connectivity: ConnectivityCapabilities? = null,
    val motionAnalysis: DrivingMotionAnalysis = DrivingMotionAnalysis(),
    val lastSignificantMotionMillis: Long? = null,
    val mountCalibrationStatus: MountCalibrationStatus = MountCalibrationStatus.CALIBRATION_REQUIRED,
    val mountCalibratedAtMillis: Long = 0L,
    val vehicleAxesSample: VehicleAxesSample = VehicleAxesSample(0f, 0f, 0f, 0f, 0f, 0f, 1f, MountCalibrationStatus.CALIBRATION_REQUIRED),
    val compassReading: CompassReading = CompassReading(),
    val barometerPressureHpa: Float? = null,
    val barometerTimestampMillis: Long? = null,
)

@Singleton
class SensorManagerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityDiscovery: HardwareCapabilityDiscovery,
    private val motionAnalyzer: DrivingMotionAnalyzer,
    private val locationTracker: LocationTracker,
    private val dispatchers: DispatcherProvider,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val _state = MutableStateFlow(SensorHubState())
    val state: StateFlow<SensorHubState> = _state.asStateFlow()

    private val readingsByType = LinkedHashMap<Int, PhysicalSensorReading>()
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private var latestRawAccel = floatArrayOf(0f, 9.81f, 0f)
    private var latestLinearAccel = floatArrayOf(0f, 0f, 0f)
    private var latestGyro = floatArrayOf(0f, 0f, 0f)
    private var latestOrientationDeg = floatArrayOf(0f, 0f, 0f)
    private var latestMagneticUt: Float? = null
    private var latestPressureHpa: Float? = null
    private var latestPressureTsMs: Long? = null
    private val mountCalibrator = PhoneMountCalibrator()
    private val compassEstimator = CompassHeadingEstimator()
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastUiPublishMs = 0L

    fun recalibrateMount() {
        mountCalibrator.requestRecalibration()
        _state.value = _state.value.copy(
            mountCalibrationStatus = MountCalibrationStatus.CALIBRATION_REQUIRED,
            mountCalibratedAtMillis = 0L,
        )
    }

    private val expectedCoreSensorTypes = listOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        Sensor.TYPE_SIGNIFICANT_MOTION,
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR,
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_RELATIVE_HUMIDITY,
        Sensor.TYPE_PROXIMITY,
        Sensor.TYPE_MAGNETIC_FIELD,
    )

    init {
        discoverAllCapabilities()
    }

    fun discoverAllCapabilities() {
        val discovered = sensorManager.getSensorList(Sensor.TYPE_ALL)
        readingsByType.clear()

        for (sensor in discovered) {
            if (!readingsByType.containsKey(sensor.type)) {
                readingsByType[sensor.type] = PhysicalSensorReading(
                    sensorType = sensor.type,
                    typeName = sensorTypeName(sensor.type),
                    category = categoryFor(sensor.type),
                    name = sensor.name,
                    vendor = sensor.vendor,
                    isAvailable = true,
                    status = SensorConnectionStatus.IDLE,
                    unit = unitFor(sensor.type),
                    powerMa = sensor.power,
                    calibrationNote = calibrationNoteFor(sensor.type),
                )
            }
        }

        for (expectedType in expectedCoreSensorTypes) {
            if (!readingsByType.containsKey(expectedType)) {
                readingsByType[expectedType] = PhysicalSensorReading(
                    sensorType = expectedType,
                    typeName = sensorTypeName(expectedType),
                    category = categoryFor(expectedType),
                    name = "Not present on hardware",
                    vendor = "N/A",
                    isAvailable = false,
                    status = SensorConnectionStatus.UNAVAILABLE,
                    formattedReading = "Not supported on this device",
                    unit = unitFor(expectedType),
                )
            }
        }

        _state.value = _state.value.copy(
            physicalSensors = readingsByType.values.toList(),
            cameras = capabilityDiscovery.discoverCameras(),
            audio = capabilityDiscovery.discoverAudio(),
            connectivity = capabilityDiscovery.discoverConnectivity(),
        )
    }

    fun startMonitoring(profile: SensorSamplingProfile) {
        stopMonitoring()
        discoverAllCapabilities()

        val thread = HandlerThread("KoPilotSensorThread").apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler

        val discovered = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val registeredTypes = mutableSetOf<Int>()

        for (sensor in discovered) {
            if (!registeredTypes.add(sensor.type)) continue
            if (sensor.type == Sensor.TYPE_SIGNIFICANT_MOTION) {
                armSignificantMotion(sensor)
                continue
            }
            val delayUs = if (categoryFor(sensor.type) == SensorCategory.MOTION) {
                profile.motionDelayUs
            } else {
                profile.envDelayUs
            }
            val ok = sensorManager.registerListener(this, sensor, delayUs, handler)
            if (ok) {
                readingsByType[sensor.type]?.let { existing ->
                    readingsByType[sensor.type] = existing.copy(
                        status = SensorConnectionStatus.ACTIVE,
                        samplingHz = 1_000_000f / delayUs.toFloat(),
                    )
                }
            }
        }

        _state.value = _state.value.copy(
            isMonitoring = true,
            samplingProfile = profile,
            physicalSensors = readingsByType.values.toList(),
        )
    }

    fun setSamplingProfile(profile: SensorSamplingProfile) {
        if (_state.value.isMonitoring) {
            startMonitoring(profile)
        } else {
            _state.value = _state.value.copy(samplingProfile = profile)
        }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        motionAnalyzer.reset()

        for ((type, reading) in readingsByType) {
            if (reading.isAvailable) {
                readingsByType[type] = reading.copy(status = SensorConnectionStatus.IDLE)
            }
        }
        _state.value = _state.value.copy(
            isMonitoring = false,
            physicalSensors = readingsByType.values.toList(),
        )
    }

    private fun armSignificantMotion(sensor: Sensor) {
        readingsByType[sensor.type]?.let {
            readingsByType[sensor.type] = it.copy(status = SensorConnectionStatus.TRIGGER_ARMED)
        }
        sensorManager.requestTriggerSensor(
            object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent?) {
                    val now = System.currentTimeMillis()
                    readingsByType[Sensor.TYPE_SIGNIFICANT_MOTION]?.let { existing ->
                        readingsByType[Sensor.TYPE_SIGNIFICANT_MOTION] = existing.copy(
                            formattedReading = "Triggered",
                            timestampMillis = now,
                        )
                    }
                    _state.value = _state.value.copy(
                        lastSignificantMotionMillis = now,
                        physicalSensors = readingsByType.values.toList(),
                    )
                    armSignificantMotion(sensor)
                }
            },
            sensor,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowMs = System.currentTimeMillis()
        val type = event.sensor.type
        val values = event.values.toList()
        val unit = unitFor(type)

        when (type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size >= 3) {
                    latestRawAccel = floatArrayOf(event.values[0], event.values[1], event.values[2])
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (event.values.size >= 3) {
                    latestLinearAccel = floatArrayOf(event.values[0], event.values[1], event.values[2])
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (event.values.size >= 3) {
                    latestGyro = floatArrayOf(event.values[0], event.values[1], event.values[2])
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (event.values.size >= 3) {
                    val mx = event.values[0]
                    val my = event.values[1]
                    val mz = event.values[2]
                    latestMagneticUt = kotlin.math.sqrt(mx * mx + my * my + mz * mz)
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                latestOrientationDeg = floatArrayOf(
                    Math.toDegrees(orientationAngles[0].toDouble()).toFloat(),
                    Math.toDegrees(orientationAngles[1].toDouble()).toFloat(),
                    Math.toDegrees(orientationAngles[2].toDouble()).toFloat(),
                )
            }
            Sensor.TYPE_PRESSURE -> {
                if (event.values.isNotEmpty() && event.values[0] in 300f..1100f) {
                    latestPressureHpa = event.values[0]
                    latestPressureTsMs = nowMs
                }
            }
        }

        readingsByType[type]?.let { existing ->
            readingsByType[type] = existing.copy(
                values = values,
                formattedReading = formatValues(values, unit),
                timestampMillis = nowMs,
                accuracyLabel = accuracyText(event.accuracy),
            )
        }

        if (nowMs - lastUiPublishMs >= UI_PUBLISH_INTERVAL_MS) {
            lastUiPublishMs = nowMs
            val fix = locationTracker.state.value.currentFix
            val gpsSpeedKmh = fix?.speedKmh
            val accelAvailable = readingsByType[Sensor.TYPE_ACCELEROMETER]?.isAvailable == true
            val gyroAvailable = readingsByType[Sensor.TYPE_GYROSCOPE]?.isAvailable == true
            val axesSample = mountCalibrator.update(
                nowMillis = nowMs,
                accelAvailable = accelAvailable,
                gyroAvailable = gyroAvailable,
                rawAx = latestRawAccel[0],
                rawAy = latestRawAccel[1],
                rawAz = latestRawAccel[2],
                linearAx = latestLinearAccel[0],
                linearAy = latestLinearAccel[1],
                linearAz = latestLinearAccel[2],
                pitchDeg = latestOrientationDeg[1],
                rollDeg = latestOrientationDeg[2],
                yawDeg = latestOrientationDeg[0],
                gpsSpeedKmh = gpsSpeedKmh,
            )
            val compass = compassEstimator.update(
                gpsSpeedKmh = gpsSpeedKmh,
                gpsBearingDeg = fix?.bearingDegrees,
                sensorAzimuthDeg = latestOrientationDeg[0],
                magneticFieldUt = latestMagneticUt,
            )
            val motion = motionAnalyzer.analyze(
                linearAccelX = latestLinearAccel[0],
                linearAccelY = latestLinearAccel[1],
                linearAccelZ = latestLinearAccel[2],
                gyroX = latestGyro[0],
                gyroY = latestGyro[1],
                gyroZ = latestGyro[2],
                azimuthDeg = compass.degrees.toFloat(),
                pitchDeg = latestOrientationDeg[1],
                rollDeg = latestOrientationDeg[2],
                gpsSpeedKmh = gpsSpeedKmh,
                nowMillis = nowMs,
            )
            scope.launch {
                _state.value = _state.value.copy(
                    physicalSensors = readingsByType.values.toList(),
                    motionAnalysis = motion,
                    mountCalibrationStatus = axesSample.calibrationStatus,
                    mountCalibratedAtMillis = mountCalibrator.getCalibratedAtMillis(),
                    vehicleAxesSample = axesSample,
                    compassReading = compass,
                    barometerPressureHpa = latestPressureHpa,
                    barometerTimestampMillis = latestPressureTsMs,
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        readingsByType[sensor.type]?.let { existing ->
            readingsByType[sensor.type] = existing.copy(accuracyLabel = accuracyText(accuracy))
        }
    }

    private fun formatValues(values: List<Float>, unit: String): String =
        values.take(3).joinToString(", ") { v -> "%.2f".format(v) } + if (unit.isNotBlank()) " $unit" else ""

    private fun accuracyText(acc: Int): String = when (acc) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
        else -> "Unreliable"
    }

    private fun categoryFor(type: Int): SensorCategory = when (type) {
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        Sensor.TYPE_SIGNIFICANT_MOTION,
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR,
        -> SensorCategory.MOTION

        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_RELATIVE_HUMIDITY,
        Sensor.TYPE_PROXIMITY,
        -> SensorCategory.ENVIRONMENTAL

        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        -> SensorCategory.POSITION

        else -> SensorCategory.OTHER
    }

    private fun sensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Acceleration"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game Rotation Vector"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic Rotation Vector"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant Motion"
        Sensor.TYPE_STEP_COUNTER -> "Step Counter"
        Sensor.TYPE_STEP_DETECTOR -> "Step Detector"
        Sensor.TYPE_LIGHT -> "Ambient Light"
        Sensor.TYPE_PRESSURE -> "Barometer"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient Temperature"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "Relative Humidity"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
        else -> "Sensor ($type)"
    }

    private fun unitFor(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GRAVITY,
        -> "m/s²"
        Sensor.TYPE_GYROSCOPE -> "rad/s"
        Sensor.TYPE_MAGNETIC_FIELD -> "µT"
        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> "steps"
        else -> ""
    }

    private fun calibrationNoteFor(type: Int): String = when (type) {
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        -> "Figure-8 motion improves compass calibration"
        Sensor.TYPE_GYROSCOPE -> "Zero-rate offset stabilizes when stationary"
        else -> "Factory calibrated"
    }

    companion object {
        const val UI_PUBLISH_INTERVAL_MS = 150L
    }
}
