package ke.co.kopilot.feature.dashboard.pretrip

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.database.preferences.PreferencesRepository
import ke.co.kopilot.core.network.connectivity.ConnectivityObserver
import ke.co.kopilot.feature.camera.CameraPerformanceController
import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.diagnostics.ObdConnectionState
import ke.co.kopilot.feature.diagnostics.ObdDiscoveredDevice
import ke.co.kopilot.feature.diagnostics.ObdFailureCategory
import ke.co.kopilot.feature.diagnostics.ObdLiveTelemetry
import ke.co.kopilot.feature.location.LocationTracker
import ke.co.kopilot.feature.sensors.MountCalibrationStatus
import ke.co.kopilot.feature.sensors.SensorManagerRepository
import ke.co.kopilot.feature.sensors.SensorSamplingProfile
import ke.co.kopilot.feature.sensors.wearable.DiscoveredBleWatch
import ke.co.kopilot.feature.sensors.wearable.HeartRateRepository
import ke.co.kopilot.feature.sensors.wearable.HeartRateTelemetryState
import ke.co.kopilot.feature.sensors.wearable.SleepAnalyticsCalculator
import ke.co.kopilot.feature.sensors.wearable.SleepAnalyticsRepository
import ke.co.kopilot.feature.sensors.wearable.SleepAnalyticsUiState
import ke.co.kopilot.feature.sensors.wearable.SmartwatchConnectionManager
import ke.co.kopilot.feature.sensors.wearable.SmartwatchConnectionState
import ke.co.kopilot.feature.sensors.wearable.SmartwatchDiagnosticsState
import ke.co.kopilot.feature.trips.TripActivityLogger
import ke.co.kopilot.feature.vision.RoadVisionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class PreTripWizardUiState(
    val currentStage: PreTripWizardStage = PreTripWizardStage.WELCOME_INIT,
    val initProgressFraction: Float = 0f,
    val currentActivityText: String = "Checking device permissions and hardware...",
    val cameraPermissionGranted: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val bluetoothPermissionGranted: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val rearCameraHardwareAvailable: Boolean = false,
    val visionModelLoaded: Boolean = false,
    val isNetworkOnline: Boolean = false,
    val sensorChecks: List<SensorCheckItem> = emptyList(),
    // OBD-II state
    val bluetoothEnabled: Boolean = false,
    val obdConnectionState: ObdConnectionState = ObdConnectionState.DISCONNECTED,
    val obdStatusMessage: String = "OBD-II not connected",
    val obdFailureCategory: ObdFailureCategory = ObdFailureCategory.NONE,
    val obdConnected: Boolean = false,
    val obdAdapterName: String? = null,
    val obdLiveTelemetry: ObdLiveTelemetry = ObdLiveTelemetry(),
    val obdDtcCodes: List<String> = emptyList(),
    val obdDiscoveredDevices: List<ObdDiscoveredDevice> = emptyList(),
    val obdConnectionLog: List<String> = emptyList(),
    val vehicleHealthParams: List<VehicleHealthParameter> = emptyList(),
    // Smartwatch & Sleep state
    val watchDiagnostics: SmartwatchDiagnosticsState = SmartwatchDiagnosticsState(),
    val discoveredWatches: List<DiscoveredBleWatch> = emptyList(),
    val heartRateState: HeartRateTelemetryState = HeartRateTelemetryState(),
    val sleepState: SleepAnalyticsUiState = SleepAnalyticsUiState(),
    // Calibration state
    val mountCalibrationStatus: MountCalibrationStatus = MountCalibrationStatus.CALIBRATION_REQUIRED,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val compassDegrees: Int = 0,
    val compassCardinal: String = "N",
    val compassReliable: Boolean = false,
    val gpsAccuracyMeters: Float? = null,
    val gpsSatellitesUsed: Int = 0,
    val gpsSpeedKmh: Float? = null,
    val gpsAltitudeMeters: Double? = null,
    val gpsHeadingReliable: Boolean = false,
    // Wellness & Final Readiness
    val driverWellness: DriverWellnessAssessment = PreTripReadinessEvaluator.evaluateDriverWellness(
        null, null, null, null, null, null, false, null, false,
    ),
    val readinessSummary: PreTripReadinessSummary = PreTripReadinessSummary(
        overallLevel = OverallReadinessLevel.READY_WITH_LIMITATIONS,
        headline = "Initializing pre-trip checks...",
        reasons = emptyList(),
        readySystemsCount = 0,
        totalSystemsCount = 5,
    ),
)

@HiltViewModel
class PreTripStartupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsRepo: PreferencesRepository,
    private val cameraController: CameraPerformanceController,
    private val visionPipeline: RoadVisionPipeline,
    private val locationTracker: LocationTracker,
    private val sensorManagerRepo: SensorManagerRepository,
    val obdManager: ObdBluetoothManager,
    val smartwatchConnectionManager: SmartwatchConnectionManager,
    val heartRateRepository: HeartRateRepository,
    val sleepAnalyticsRepository: SleepAnalyticsRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val tripActivityLogger: TripActivityLogger,
) : ViewModel() {

    private val currentStageFlow = MutableStateFlow(PreTripWizardStage.WELCOME_INIT)
    private val activityTextFlow = MutableStateFlow("Initializing KoPilot services...")
    private val permFlow = MutableStateFlow(PermissionFlags())
    private val rearCameraAvailable: Boolean = detectRearCameraHardware()
    private var initializationStarted = false

    private val coreSensorFlows = combine(
        currentStageFlow,
        activityTextFlow,
        permFlow,
        sensorManagerRepo.state,
        locationTracker.state,
    ) { stage, activity, perms, sensorHub, loc ->
        CoreSensorTuple(stage, activity, perms, sensorHub, loc)
    }

    private val obdFlows = combine(
        obdManager.connectionState,
        obdManager.state,
        obdManager.liveTelemetry,
        obdManager.discoveredDevices,
        obdManager.failureCategory,
    ) { connState, diagState, liveTel, devices, failCat ->
        ObdTuple(connState, diagState, liveTel, devices, failCat)
    }

    private val wearableFlows = combine(
        smartwatchConnectionManager.diagnostics,
        smartwatchConnectionManager.discoveredWatches,
        heartRateRepository.state,
        sleepAnalyticsRepository.state,
    ) { diag, watches, hr, sleep ->
        WearableTuple(diag, watches, hr, sleep)
    }

    val uiState: StateFlow<PreTripWizardUiState> = combine(
        coreSensorFlows,
        obdFlows,
        wearableFlows,
        cameraController.status,
        combine(visionPipeline.state, connectivityObserver.isOnline) { v, net -> v to net },
    ) { core, obd, wearable, camStatus, visionAndNet ->
        val (visionState, isOnline) = visionAndNet
        val perms = core.perms
        val hub = core.sensorHub
        val loc = core.loc
        val fix = loc.currentFix
        val motion = hub.motionAnalysis
        val compass = hub.compassReading

        // Build real sensor checks verifying actual readings (not just hardware presence)
        val sensorChecks = buildSensorChecks(
            perms = perms,
            rearCameraAvailable = rearCameraAvailable,
            visionModelLoaded = visionState.modelLoaded,
            hub = hub,
            loc = loc,
            camStatus = camStatus,
            isOnline = isOnline,
        )

        val completedInitChecks = listOf(
            visionState.modelLoaded,
            hub.physicalSensors.any { it.isAvailable && it.timestampMillis > 0L },
            perms.cameraGranted || perms.locationGranted,
            obd.connState != ObdConnectionState.DISCONNECTED || obd.devices.isNotEmpty(),
            wearable.diag.connectionState != SmartwatchConnectionState.DISCONNECTED || wearable.sleep.lastSyncTimestampMillis != null,
        ).count { it }
        val initProgress = ((completedInitChecks.toFloat() / 5f).coerceIn(0.15f, 1f))

        val effectiveVolt = obd.liveTel.controlModuleVoltageV ?: obd.liveTel.adapterVoltageV
        val vehicleHealthParams = PreTripReadinessEvaluator.evaluateVehicleHealthParameters(
            obdConnected = obd.diagState.obdConnected,
            rpm = obd.liveTel.engineRpm,
            coolantTempC = obd.liveTel.coolantTempC,
            speedKmh = obd.liveTel.vehicleSpeedKmh,
            engineLoadPct = obd.liveTel.engineLoadPct,
            throttlePct = obd.liveTel.throttlePositionPct,
            voltageV = effectiveVolt,
            fuelLevelPct = obd.liveTel.fuelLevelPct,
            dtcCodes = obd.diagState.dtcCodes,
            milOn = obd.liveTel.milOn,
        )

        val dateTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val latestSleep = wearable.sleep.latestSession
        val sleepWindow = latestSleep?.let {
            "${dateTimeFmt.format(Date(it.startTimeMillis))} - ${dateTimeFmt.format(Date(it.endTimeMillis))}"
        }
        val sleepStages = latestSleep?.let { s ->
            if (s.stagesAvailable) {
                "Deep ${SleepAnalyticsCalculator.formatDurationHoursMinutes(s.deepSleepMinutes ?: 0)} | " +
                    "Light ${SleepAnalyticsCalculator.formatDurationHoursMinutes(s.lightSleepMinutes ?: 0)} | " +
                    "REM ${SleepAnalyticsCalculator.formatDurationHoursMinutes(s.remSleepMinutes ?: 0)}"
            } else {
                "Stage breakdown not supplied by ${s.sourceAppName}"
            }
        }
        val driverWellness = PreTripReadinessEvaluator.evaluateDriverWellness(
            latestSleepDurationMinutes = latestSleep?.totalSleepDurationMinutes,
            sleepWindowText = sleepWindow,
            sleepStagesSummary = sleepStages,
            sleepConsistencyText = wearable.sleep.trendAnalysis.trendSummary,
            liveHeartRateBpm = wearable.hr.currentBpm,
            avgHeartRateBpm = wearable.hr.avgBpm,
            sensorContactSupported = wearable.hr.sensorContactSupported,
            sensorContactDetected = wearable.hr.sensorContactDetected,
            isHeartRateFresh = wearable.hr.isLiveReading && !wearable.hr.isStale,
        )

        val camCheck = sensorChecks.firstOrNull { it.id == "camera" }?.status ?: PreTripCheckStatus.CHECKING
        val gpsCheck = sensorChecks.firstOrNull { it.id == "gps" }?.status ?: PreTripCheckStatus.CHECKING
        val accelCheck = sensorChecks.firstOrNull { it.id == "accelerometer" }?.status ?: PreTripCheckStatus.CHECKING
        val abnormalObdCount = vehicleHealthParams.count { it.isAbnormal }
        val watchLive = wearable.diag.connectionState == SmartwatchConnectionState.SUBSCRIBED_LIVE

        val readiness = PreTripReadinessEvaluator.evaluateOverallReadiness(
            cameraStatus = camCheck,
            gpsStatus = gpsCheck,
            motionStatus = accelCheck,
            obdConnected = obd.diagState.obdConnected,
            obdAbnormalCount = abnormalObdCount,
            watchConnected = watchLive,
            mountCalibrated = hub.mountCalibrationStatus == MountCalibrationStatus.CALIBRATED,
            hasDriverWellnessCaution = driverWellness.cautionMessages.isNotEmpty(),
        )

        val gpsHeadingReliable = (fix?.speedKmh ?: 0f) >= 8f && (fix?.bearingDegrees != null)

        PreTripWizardUiState(
            currentStage = core.stage,
            initProgressFraction = initProgress,
            currentActivityText = core.activity,
            cameraPermissionGranted = perms.cameraGranted,
            locationPermissionGranted = perms.locationGranted,
            bluetoothPermissionGranted = perms.bluetoothGranted,
            micPermissionGranted = perms.micGranted,
            rearCameraHardwareAvailable = rearCameraAvailable,
            visionModelLoaded = visionState.modelLoaded,
            isNetworkOnline = isOnline,
            sensorChecks = sensorChecks,
            bluetoothEnabled = obdManager.isBluetoothEnabled(),
            obdConnectionState = obd.connState,
            obdStatusMessage = obdManager.connectionManager.statusMessage.value,
            obdFailureCategory = obd.failCat,
            obdConnected = obd.diagState.obdConnected,
            obdAdapterName = obd.diagState.adapterName ?: obd.liveTel.adapterInfo,
            obdLiveTelemetry = obd.liveTel,
            obdDtcCodes = obd.diagState.dtcCodes,
            obdDiscoveredDevices = obd.devices,
            obdConnectionLog = obdManager.connectionLog.value,
            vehicleHealthParams = vehicleHealthParams,
            watchDiagnostics = wearable.diag,
            discoveredWatches = wearable.watches,
            heartRateState = wearable.hr,
            sleepState = wearable.sleep,
            mountCalibrationStatus = hub.mountCalibrationStatus,
            pitchDegrees = motion.pitchDegrees,
            rollDegrees = motion.rollDegrees,
            compassDegrees = compass.degrees,
            compassCardinal = compass.cardinal,
            compassReliable = compass.source != "Unavailable",
            gpsAccuracyMeters = fix?.horizontalAccuracyMeters,
            gpsSatellitesUsed = loc.satellitesUsedInFix,
            gpsSpeedKmh = fix?.speedKmh,
            gpsAltitudeMeters = null,
            gpsHeadingReliable = gpsHeadingReliable,
            driverWellness = driverWellness,
            readinessSummary = readiness,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PreTripWizardUiState(),
    )

    fun startRealInitialization(
        cameraGranted: Boolean,
        locationGranted: Boolean,
        bluetoothGranted: Boolean,
        micGranted: Boolean,
    ) {
        permFlow.value = PermissionFlags(cameraGranted, locationGranted, bluetoothGranted, micGranted)
        if (initializationStarted) {
            // Avoid duplicate Bluetooth connections on recomposition/resume
            if (bluetoothGranted) {
                obdManager.listPairedDevices(true)
            }
            return
        }
        initializationStarted = true

        viewModelScope.launch(Dispatchers.Default) {
            activityTextFlow.value = "Loading offline road-sign neural model (LiteRT YOLO11n)..."
            visionPipeline.initializeIfNeeded()

            activityTextFlow.value = "Activating phone motion, compass, and barometer sensors..."
            sensorManagerRepo.startMonitoring(SensorSamplingProfile.DRIVING)

            if (locationGranted) {
                activityTextFlow.value = "Acquiring GPS satellite lock..."
                locationTracker.setPermissionGranted(true)
                locationTracker.startTracking(highAccuracy = true)
            }

            activityTextFlow.value = "Checking Health Connect sleep records..."
            sleepAnalyticsRepository.refreshAvailabilityAndCompanionApps()

            if (bluetoothGranted && obdManager.isBluetoothEnabled()) {
                activityTextFlow.value = "Checking paired OBD-II adapters & BLE smartwatches..."
                obdManager.listPairedDevices(true)
                val s = prefsRepo.settingsFlow.firstOrNull()
                if (s != null && s.lastObdDeviceAddress.isNotBlank() && !obdManager.state.value.obdConnected) {
                    obdManager.connectAndMonitor(s.lastObdDeviceAddress, true, forceRestart = false)
                }
                smartwatchConnectionManager.tryRestoreSavedWatch(true)
            }

            activityTextFlow.value = "Initial system checks ready."
            if (currentStageFlow.value == PreTripWizardStage.WELCOME_INIT) {
                currentStageFlow.value = PreTripWizardStage.CAMERA_AND_SENSORS
            }
        }
    }

    fun updatePermissions(
        cameraGranted: Boolean,
        locationGranted: Boolean,
        bluetoothGranted: Boolean,
        micGranted: Boolean,
    ) {
        permFlow.value = PermissionFlags(cameraGranted, locationGranted, bluetoothGranted, micGranted)
        if (locationGranted) {
            locationTracker.setPermissionGranted(true)
            locationTracker.startTracking(highAccuracy = true)
        }
        if (bluetoothGranted && obdManager.isBluetoothEnabled()) {
            obdManager.listPairedDevices(true)
        }
    }

    fun goToStage(stage: PreTripWizardStage) {
        currentStageFlow.value = stage
    }

    fun nextStage() {
        val entries = PreTripWizardStage.entries
        val nextIdx = (currentStageFlow.value.ordinal + 1).coerceAtMost(entries.lastIndex)
        currentStageFlow.value = entries[nextIdx]
    }

    fun previousStage() {
        val entries = PreTripWizardStage.entries
        val prevIdx = (currentStageFlow.value.ordinal - 1).coerceAtLeast(0)
        currentStageFlow.value = entries[prevIdx]
    }

    fun scanObdAdapters() {
        obdManager.startScan(permFlow.value.bluetoothGranted)
    }

    fun connectObdAdapter(address: String, name: String) {
        viewModelScope.launch {
            prefsRepo.setLastObdDevice(address, name)
        }
        obdManager.connectAndMonitor(address, permFlow.value.bluetoothGranted, forceRestart = true)
    }

    fun retryObdConnection() {
        viewModelScope.launch {
            val savedAddr = obdManager.lastAttemptedAddress.value
                ?: prefsRepo.settingsFlow.firstOrNull()?.lastObdDeviceAddress.orEmpty()
            if (savedAddr.isNotBlank()) {
                obdManager.connectAndMonitor(savedAddr, permFlow.value.bluetoothGranted, forceRestart = true)
            } else {
                obdManager.startScan(permFlow.value.bluetoothGranted)
            }
        }
    }

    fun scanSmartwatches() {
        smartwatchConnectionManager.startBleScan(permFlow.value.bluetoothGranted, durationMs = 12_000L)
    }

    fun connectSmartwatch(address: String, name: String) {
        smartwatchConnectionManager.connectToWatch(address, name, permFlow.value.bluetoothGranted)
    }

    fun syncSleepNow() {
        viewModelScope.launch(Dispatchers.IO) {
            sleepAnalyticsRepository.checkPermissionsAndSync(autoTriggered = false)
        }
    }

    fun calibratePhoneMount() {
        sensorManagerRepo.recalibrateMount()
    }

    fun retryFailedChecks() {
        val perms = permFlow.value
        visionPipeline.initializeIfNeeded()
        sensorManagerRepo.startMonitoring(SensorSamplingProfile.DRIVING)
        if (perms.locationGranted) {
            locationTracker.startTracking(highAccuracy = true)
        }
        if (perms.bluetoothGranted && obdManager.isBluetoothEnabled()) {
            if (!obdManager.state.value.obdConnected) {
                retryObdConnection()
            }
            if (smartwatchConnectionManager.diagnostics.value.connectionState != SmartwatchConnectionState.SUBSCRIBED_LIVE) {
                smartwatchConnectionManager.tryRestoreSavedWatch(true)
            }
        }
        syncSleepNow()
    }

    fun logPreTripReportToActiveTrip() {
        val st = uiState.value
        val summary = buildString {
            append("Overall=${st.readinessSummary.overallLevel.label} | ")
            append("SystemsReady=${st.readinessSummary.readySystemsCount}/${st.readinessSummary.totalSystemsCount} | ")
            append("OBD=${if (st.obdConnected) "${st.obdAdapterName ?: "Connected"} (${st.obdLiveTelemetry.protocolName ?: "Auto"})" else "Disconnected"} | ")
            append("Watch=${if (st.heartRateState.isLiveReading && !st.heartRateState.isStale) "${st.heartRateState.currentBpm} BPM" else st.watchDiagnostics.connectionState.displayName} | ")
            append("Sleep=${st.driverWellness.sleepDurationText} | ")
            append("MountCal=${st.mountCalibrationStatus.name}")
        }
        tripActivityLogger.logActivity("PRE_TRIP_REPORT", summary)
    }

    private fun detectRearCameraHardware(): Boolean {
        return runCatching {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
            mgr.cameraIdList.any { id ->
                val chars = mgr.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrDefault(false)
    }

    private fun buildSensorChecks(
        perms: PermissionFlags,
        rearCameraAvailable: Boolean,
        visionModelLoaded: Boolean,
        hub: ke.co.kopilot.feature.sensors.SensorHubState,
        loc: ke.co.kopilot.feature.location.LocationTrackingState,
        camStatus: ke.co.kopilot.feature.camera.CameraStatusState,
        isOnline: Boolean,
    ): List<SensorCheckItem> {
        val accel = hub.physicalSensors.firstOrNull { it.typeName.contains("Accelerometer", ignoreCase = true) }
        val gyro = hub.physicalSensors.firstOrNull { it.typeName.contains("Gyroscope", ignoreCase = true) }
        val mag = hub.physicalSensors.firstOrNull { it.typeName.contains("Magnetic", ignoreCase = true) }
        val baro = hub.physicalSensors.firstOrNull { it.typeName.contains("Pressure", ignoreCase = true) || it.typeName.contains("Barometer", ignoreCase = true) }
        val fix = loc.currentFix

        return listOf(
            SensorCheckItem(
                id = "camera",
                name = "Rear Camera & Offline AI Vision",
                status = when {
                    !perms.cameraGranted -> PreTripCheckStatus.NEEDS_PERMISSION
                    !rearCameraAvailable -> PreTripCheckStatus.UNAVAILABLE
                    visionModelLoaded -> PreTripCheckStatus.READY
                    else -> PreTripCheckStatus.CHECKING
                },
                readingText = if (visionModelLoaded) "Rear camera ready (LiteRT YOLO11n loaded)" else "Initializing offline vision",
                detailText = "Camera hardware=${if (rearCameraAvailable) "Present" else "Not found"} | Permission=${perms.cameraGranted}",
                isCoreDrivingRequirement = true,
            ),
            SensorCheckItem(
                id = "gps",
                name = "GPS / GNSS Location",
                status = when {
                    !perms.locationGranted -> PreTripCheckStatus.NEEDS_PERMISSION
                    fix != null -> PreTripCheckStatus.READY
                    else -> PreTripCheckStatus.CHECKING
                },
                readingText = if (fix != null) {
                    "Lock active (${loc.satellitesUsedInFix} sat, ±${fix.horizontalAccuracyMeters?.toInt() ?: "--"}m)"
                } else {
                    "Waiting for satellite fix"
                },
                detailText = fix?.let {
                    "Lat ${"%.4f".format(it.latitude)}, Lng ${"%.4f".format(it.longitude)} | Accuracy ±${it.horizontalAccuracyMeters?.toInt() ?: "--"}m"
                } ?: "Move outdoors or near windshield for GNSS lock.",
                isCoreDrivingRequirement = true,
            ),
            SensorCheckItem(
                id = "accelerometer",
                name = "Accelerometer & Mount Orientation",
                status = when {
                    accel == null || !accel.isAvailable -> PreTripCheckStatus.UNAVAILABLE
                    accel.timestampMillis > 0L && hub.mountCalibrationStatus == MountCalibrationStatus.CALIBRATED -> PreTripCheckStatus.READY
                    accel.timestampMillis > 0L -> PreTripCheckStatus.CALIBRATION_REQUIRED
                    else -> PreTripCheckStatus.CHECKING
                },
                readingText = accel?.formattedReading ?: "Not available",
                detailText = "Pitch ${"%.1f".format(hub.motionAnalysis.pitchDegrees)} deg | Roll ${"%.1f".format(hub.motionAnalysis.rollDegrees)} deg | Rate: ${accel?.samplingHz?.toInt() ?: 0} Hz",
            ),
            SensorCheckItem(
                id = "gyroscope",
                name = "Gyroscope",
                status = when {
                    gyro == null || !gyro.isAvailable -> PreTripCheckStatus.UNAVAILABLE
                    gyro.timestampMillis > 0L -> PreTripCheckStatus.READY
                    else -> PreTripCheckStatus.CHECKING
                },
                readingText = gyro?.formattedReading ?: "Hardware not present",
                detailText = if (gyro != null && gyro.isAvailable) "Accuracy: ${gyro.accuracyLabel} (${gyro.samplingHz.toInt()} Hz)" else "Device has no hardware gyroscope; accelerometer + compass used.",
            ),
            SensorCheckItem(
                id = "compass",
                name = "Magnetometer & Compass",
                status = when {
                    mag == null || !mag.isAvailable -> PreTripCheckStatus.UNAVAILABLE
                    mag.timestampMillis > 0L -> PreTripCheckStatus.READY
                    else -> PreTripCheckStatus.CHECKING
                },
                readingText = "${hub.compassReading.cardinal} (${hub.compassReading.degrees} deg)",
                detailText = "Source: ${hub.compassReading.source} | Accuracy: ${mag?.accuracyLabel ?: "Unavailable"}",
            ),
            SensorCheckItem(
                id = "barometer",
                name = "Barometer (Pressure / Elevation)",
                status = when {
                    baro == null || !baro.isAvailable -> PreTripCheckStatus.UNAVAILABLE
                    baro.timestampMillis > 0L -> PreTripCheckStatus.READY
                    else -> PreTripCheckStatus.CHECKING
                },
                readingText = baro?.formattedReading ?: "Not present on this phone",
                detailText = if (baro != null && baro.isAvailable) "Accuracy: ${baro.accuracyLabel}" else "Optional sensor; GPS altitude used instead.",
            ),
            SensorCheckItem(
                id = "thermal",
                name = "Battery & Thermal Monitor",
                status = PreTripCheckStatus.READY,
                readingText = buildString {
                    append("Thermal: ${camStatus.thermalState.label}")
                    camStatus.batteryTemperatureC?.let { append(" (${"%.1f".format(it)} C)") }
                    if (camStatus.batteryPercent >= 0) append(" | Batt ${camStatus.batteryPercent}%")
                },
                detailText = "OS Thermal: ${camStatus.osThermalStatusLabel}",
            ),
            SensorCheckItem(
                id = "network",
                name = "Network Connectivity (Informational Only)",
                status = PreTripCheckStatus.READY,
                readingText = if (isOnline) "Online" else "Offline (All core driving systems operational)",
                detailText = "Road signs, OBD-II, BLE smartwatch, GPS, and Voice AI work 100% offline.",
            ),
        )
    }

    private data class PermissionFlags(
        val cameraGranted: Boolean = false,
        val locationGranted: Boolean = false,
        val bluetoothGranted: Boolean = false,
        val micGranted: Boolean = false,
    )

    private data class CoreSensorTuple(
        val stage: PreTripWizardStage,
        val activity: String,
        val perms: PermissionFlags,
        val sensorHub: ke.co.kopilot.feature.sensors.SensorHubState,
        val loc: ke.co.kopilot.feature.location.LocationTrackingState,
    )

    private data class ObdTuple(
        val connState: ObdConnectionState,
        val diagState: ke.co.kopilot.core.common.model.VehicleDiagnosticsState,
        val liveTel: ObdLiveTelemetry,
        val devices: List<ObdDiscoveredDevice>,
        val failCat: ObdFailureCategory,
    )

    private data class WearableTuple(
        val diag: SmartwatchDiagnosticsState,
        val watches: List<DiscoveredBleWatch>,
        val hr: HeartRateTelemetryState,
        val sleep: SleepAnalyticsUiState,
    )
}
