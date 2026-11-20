package ke.co.kopilot.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.co.kopilot.core.common.model.AlertMessage
import ke.co.kopilot.core.common.model.AlertPriority
import ke.co.kopilot.core.common.model.GpsSignalState
import ke.co.kopilot.core.common.model.SpeedAlertDecision
import ke.co.kopilot.core.common.model.TrafficSignObservation
import ke.co.kopilot.core.common.model.VehicleDiagnosticsState
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.core.database.preferences.DetectionQuality
import ke.co.kopilot.core.database.preferences.KoPilotSettings
import ke.co.kopilot.core.common.model.TrafficSignCategory
import ke.co.kopilot.core.database.preferences.PreferencesRepository
import ke.co.kopilot.core.database.preferences.VoiceMode
import ke.co.kopilot.core.network.connectivity.ConnectivityObserver
import ke.co.kopilot.feature.alerts.AlertCoordinatorState
import ke.co.kopilot.feature.alerts.AndroidTtsSpeechSink
import ke.co.kopilot.feature.alerts.LocalAlertCoordinator
import ke.co.kopilot.feature.alerts.SpeechSink
import ke.co.kopilot.feature.camera.CameraPerformanceController
import ke.co.kopilot.feature.camera.CameraStatusState
import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.location.LocationTracker
import ke.co.kopilot.feature.location.LocationTrackingState
import ke.co.kopilot.feature.location.SpeedAlertEngine
import ke.co.kopilot.feature.location.SpeedEvaluation
import ke.co.kopilot.feature.location.SpeedZone
import ke.co.kopilot.feature.location.SpeedZoneMonitor
import ke.co.kopilot.feature.sensors.AccidentAssessment
import ke.co.kopilot.feature.sensors.AccidentDetectionEngine
import ke.co.kopilot.feature.sensors.AccidentEventClass
import ke.co.kopilot.feature.sensors.AccidentSensitivityProfile
import ke.co.kopilot.feature.sensors.CompassReading
import ke.co.kopilot.feature.sensors.DrivingMotionAnalysis
import ke.co.kopilot.feature.sensors.EngineStopClassification
import ke.co.kopilot.feature.sensors.EngineStopEvaluation
import ke.co.kopilot.feature.sensors.EngineStopMonitor
import ke.co.kopilot.feature.sensors.LateralMotionState
import ke.co.kopilot.feature.sensors.MountCalibrationStatus
import ke.co.kopilot.feature.sensors.RealTimeDrivingIntelligence
import ke.co.kopilot.feature.sensors.RealTimeDrivingIntelligenceState
import ke.co.kopilot.feature.sensors.RealTimeIntelligenceInput
import ke.co.kopilot.feature.sensors.SensorFrame
import ke.co.kopilot.feature.sensors.SensorManagerRepository
import ke.co.kopilot.feature.sensors.SensorSamplingProfile
import ke.co.kopilot.feature.sensors.wearable.HeartRateRepository
import ke.co.kopilot.feature.sensors.wearable.HeartRateTelemetryState
import ke.co.kopilot.feature.sensors.wearable.SleepAnalyticsCalculator
import ke.co.kopilot.feature.sensors.wearable.SleepAnalyticsRepository
import ke.co.kopilot.feature.sensors.wearable.SleepAnalyticsUiState
import ke.co.kopilot.feature.sensors.wearable.SmartwatchConnectionManager
import ke.co.kopilot.feature.sensors.wearable.SmartwatchDiagnosticsState
import ke.co.kopilot.feature.navigation.NavigationRepository
import ke.co.kopilot.feature.navigation.NavigationUiState
import ke.co.kopilot.feature.traffic.TrafficAdvisor
import ke.co.kopilot.feature.traffic.TrafficEvaluation
import ke.co.kopilot.feature.trips.TripActivityLogger
import ke.co.kopilot.feature.trips.TripRepository
import ke.co.kopilot.feature.vision.LaneShiftEvent
import ke.co.kopilot.feature.vision.KenyanPlateValidator
import ke.co.kopilot.feature.vision.RoadVisionPipeline
import ke.co.kopilot.feature.vision.TrafficLightSignal
import ke.co.kopilot.feature.vision.VisionPipelineState
import ke.co.kopilot.feature.voice.OpenAiRealtimeVoiceClient
import ke.co.kopilot.feature.voice.VoiceAssistantState
import ke.co.kopilot.feature.weather.WeatherRepository
import ke.co.kopilot.feature.weather.WeatherState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class DashboardUiState(
    val isDrivingSessionActive: Boolean = false,
    val tripStartedAtMillis: Long? = null,
    val isOnline: Boolean = false,
    val settings: KoPilotSettings = KoPilotSettings(),
    val cameraStatus: CameraStatusState = CameraStatusState(),
    val visionState: VisionPipelineState = VisionPipelineState(),
    val locationState: LocationTrackingState = LocationTrackingState(),
    val speedEvaluation: SpeedEvaluation = SpeedEvaluation(
        SpeedAlertDecision.SPEED_DATA_UNAVAILABLE,
        GpsSignalState.UNAVAILABLE,
        null,
        null,
        "GPS idle",
    ),
    val navigationState: NavigationUiState = NavigationUiState(),
    val trafficEvaluation: TrafficEvaluation = TrafficAdvisor().evaluate(null, emptyList()),
    val weatherState: WeatherState = WeatherState(),
    val voiceState: VoiceAssistantState = VoiceAssistantState(),
    val alertState: AlertCoordinatorState = AlertCoordinatorState(),
    val diagnosticsState: VehicleDiagnosticsState = VehicleDiagnosticsState(),
    val savedTrips: List<TripEntity> = emptyList(),
    val activeTripLogPath: String? = null,
    val speedZone: SpeedZone = SpeedZone.NORMAL,
    val motionAnalysis: DrivingMotionAnalysis = DrivingMotionAnalysis(),
    val intelligenceState: RealTimeDrivingIntelligenceState = RealTimeDrivingIntelligenceState(),
    val compassReading: CompassReading = CompassReading(),
    val mountCalibrationStatus: MountCalibrationStatus = MountCalibrationStatus.CALIBRATION_REQUIRED,
    val engineStopEvaluation: EngineStopEvaluation = EngineStopEvaluation(
        EngineStopClassification.PARKED,
        "Standby",
        0L,
    ),
    val pendingAccident: AccidentAssessment? = null,
    val accidentCountdownRemainingSec: Int = 0,
    val postImpactMonitoringActive: Boolean = false,
    val emergencyDispatchTriggerId: String? = null,
    val smartwatchDiagnostics: SmartwatchDiagnosticsState = SmartwatchDiagnosticsState(),
    val heartRateState: HeartRateTelemetryState = HeartRateTelemetryState(),
    val sleepAnalyticsState: SleepAnalyticsUiState = SleepAnalyticsUiState(),
    val currentRoadName: String? = null,
    val tripBreadcrumbPoints: List<com.google.android.gms.maps.model.LatLng> = emptyList(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val prefsRepo: PreferencesRepository,
    val cameraController: CameraPerformanceController,
    val visionPipeline: RoadVisionPipeline,
    private val locationTracker: LocationTracker,
    private val speedAlertEngine: SpeedAlertEngine,
    private val speedZoneMonitor: SpeedZoneMonitor,
    private val navigationRepo: NavigationRepository,
    private val trafficAdvisor: TrafficAdvisor,
    private val weatherRepo: WeatherRepository,
    private val voiceClient: OpenAiRealtimeVoiceClient,
    private val alertCoordinator: LocalAlertCoordinator,
    private val speechSink: SpeechSink,
    val obdManager: ObdBluetoothManager,
    private val tripRepo: TripRepository,
    val tripActivityLogger: TripActivityLogger,
    private val sensorManagerRepo: SensorManagerRepository,
    private val realTimeIntelligence: RealTimeDrivingIntelligence,
    val smartwatchConnectionManager: SmartwatchConnectionManager,
    val heartRateRepository: HeartRateRepository,
    val sleepAnalyticsRepository: SleepAnalyticsRepository,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val isDriving = MutableStateFlow(false)
    private val tripStartedAt = MutableStateFlow<Long?>(null)
    private val currentSpeedZone = MutableStateFlow(SpeedZone.NORMAL)
    private var maxSpeedObservedKmh = 0f
    private var speedSampleSum = 0f
    private var speedSampleCount = 0
    private var signsDetectedInTrip = 0
    private var lastSpeedLogMs = 0L
    private var lastLoggedSpeedKmh = -1f
    private var lastLoggedPlate: String? = null
    private var lastLoggedTrafficSignal = TrafficLightSignal.NONE
    private var lastLoggedTrafficSignalMs = 0L
    private var lastLoggedBrakeLightMs = 0L
    private var lastLoggedDarkMs = 0L
    private var lastLoggedCameraMs = 0L
    private var lastLoggedOverspeedMs = 0L
    private var lastLoggedWeather: String? = null
    private var lastLaneAlertMs = 0L
    private var lastTurnAlertMs = 0L
    private var lastAnnouncedLaneDirection: String? = null
    private var lastAnnouncedTurnState: LateralMotionState = LateralMotionState.STRAIGHT
    private var lastTutorVehicleAheadMs = 0L
    private var lastTutorBusyRoadMs = 0L
    private var lastTutorClearRoadMs = 0L
    private var clearRoadSinceMs = 0L
    private var lastSensorSnapshotLoggedMs = 0L
    private var lastWatchedPlateAlertMs = 0L
    private var lastWatchedPlateAlerted: String? = null
    private var lastEcuAlertMs = 0L
    private var lastIntelVoiceAlertMs = 0L
    private var lastIntelVoiceType: String? = null
    private var motionDetectedSinceMs = 0L
    private var lastVehicleMovingMs = 0L
    private var autoModeArmed = false
    private var autoObdConnectAttempted = false
    private var lastTurnReferenceHeadingDeg: Float? = null
    private var lastTurnReferenceCardinal: String? = null
    private var preflightObdSpoken = false
    private var obdBeepPlayed = false
    private var gpsBeepPlayed = false
    private var bluetoothGranted = false
    private var lastTrackLat: Double? = null
    private var lastTrackLng: Double? = null
    private var lastTrackMs = 0L
    private var fuelRateSum = 0.0
    private var fuelRateCount = 0
    private var voltSum = 0.0
    private var voltCount = 0
    private var voltMin = Float.MAX_VALUE
    private var voltMax = 0f
    private val explainedSignsInTrip = mutableSetOf<TrafficSignCategory>()
    private val accidentEngine = AccidentDetectionEngine()
    private val engineStopMonitor = EngineStopMonitor()
    private val pendingAccidentState = MutableStateFlow<AccidentAssessment?>(null)
    private val countdownRemainingState = MutableStateFlow(0)
    private val postImpactState = MutableStateFlow(false)
    private val emergencyDispatchTrigger = MutableStateFlow<String?>(null)
    private val engineStopState = MutableStateFlow(
        EngineStopEvaluation(EngineStopClassification.PARKED, "Standby", 0L),
    )
    private var countdownJob: kotlinx.coroutines.Job? = null
    private var lastWeatherRefreshMs = 0L
    private var lastAnnouncedCardinal: String? = null
    private var pendingCardinal: String? = null
    private var pendingCardinalSinceMs = 0L
    private var lastHeadingAnnounceMs = 0L

    private val coreFlows = combine(
        isDriving,
        tripStartedAt,
        connectivityObserver.isOnline,
        prefsRepo.settingsFlow,
        cameraController.status,
    ) { driving, started, online, settings, cam ->
        CoreBundle(driving, started, online, settings, cam)
    }

    private val sensorFlows = combine(
        visionPipeline.state,
        locationTracker.state,
        navigationRepo.state,
        weatherRepo.state,
        voiceClient.state,
    ) { vision, loc, nav, weather, voice ->
        SensorBundle(vision, loc, nav, weather, voice)
    }

    private var currentActiveTripId: String? = null
    private var lastLoggedHeartRateMs: Long = 0L
    private val currentRoadNameFlow = MutableStateFlow<String?>(null)
    private val tripBreadcrumbFlow = MutableStateFlow<List<com.google.android.gms.maps.model.LatLng>>(emptyList())
    private var lastRoadLookupMs = 0L
    private var lastAnnouncedRoadName: String? = null
    private var twoMinuteRoadAnnounced = false

    private val wearableFlows = combine(
        smartwatchConnectionManager.diagnostics,
        heartRateRepository.state,
        sleepAnalyticsRepository.state,
    ) { diag, hr, sleep ->
        Triple(diag, hr, sleep)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        coreFlows,
        sensorFlows,
        combine(alertCoordinator.state, realTimeIntelligence.state, wearableFlows) { a, i, w -> Triple(a, i, w) },
        obdManager.state,
        tripRepo.trips,
    ) { core, sensor, alertsIntelWearable, obd, trips ->
        val (alerts, intel, wearable) = alertsIntelWearable
        val (watchDiag, hrState, sleepState) = wearable
        val speedEval = speedAlertEngine.evaluate(
            fix = sensor.location.currentFix,
            latestSign = sensor.vision.lastObservation,
            thresholdKmh = core.settings.speedAlertThresholdKmh,
        )
        val trafficEval = trafficAdvisor.evaluate(
            activeRoute = sensor.navigation.activeRoute,
            alternatives = sensor.navigation.alternativeRoutes,
        )
        DashboardUiState(
            isDrivingSessionActive = core.driving,
            tripStartedAtMillis = core.startedAt,
            isOnline = core.online,
            settings = core.settings,
            cameraStatus = core.camera,
            visionState = sensor.vision,
            locationState = sensor.location,
            speedEvaluation = speedEval,
            navigationState = sensor.navigation,
            trafficEvaluation = trafficEval,
            weatherState = sensor.weather,
            voiceState = sensor.voice,
            alertState = alerts,
            diagnosticsState = obd,
            savedTrips = trips,
            activeTripLogPath = tripActivityLogger.activeLogPath.value,
            speedZone = currentSpeedZone.value,
            motionAnalysis = sensorManagerRepo.state.value.motionAnalysis,
            intelligenceState = intel,
            compassReading = sensorManagerRepo.state.value.compassReading,
            mountCalibrationStatus = sensorManagerRepo.state.value.mountCalibrationStatus,
            engineStopEvaluation = engineStopState.value,
            pendingAccident = pendingAccidentState.value,
            accidentCountdownRemainingSec = countdownRemainingState.value,
            postImpactMonitoringActive = postImpactState.value,
            emergencyDispatchTriggerId = emergencyDispatchTrigger.value,
            smartwatchDiagnostics = watchDiag,
            heartRateState = hrState,
            sleepAnalyticsState = sleepState,
            currentRoadName = currentRoadNameFlow.value,
            tripBreadcrumbPoints = tripBreadcrumbFlow.value,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(),
    )

    init {
        visionPipeline.initializeIfNeeded()
        viewModelScope.launch {
            prefsRepo.settingsFlow.collect { s ->
                if (speechSink is AndroidTtsSpeechSink) {
                    speechSink.setVoiceProfile(s.aiVoiceName)
                }
                tryAutoConnectObd(s)
            }
        }
        viewModelScope.launch {
            obdManager.lastAttemptedAddress.collect { addr ->
                if (!addr.isNullOrBlank()) {
                    val name = obdManager.discoveredDevices.value
                        .firstOrNull { it.address == addr }?.name
                        ?: obdManager.listPairedDevices(bluetoothGranted)
                            .firstOrNull { it.address == addr }?.name
                        ?: uiState.value.settings.lastObdDeviceName.ifBlank { "OBD-II Adapter" }
                    prefsRepo.setLastObdDevice(addr, name)
                }
            }
        }
        // OBD Connection Beep, Persistence & Pre-Flight Voice Check
        viewModelScope.launch {
            obdManager.state.collect { obdState ->
                if (!obdState.obdConnected) {
                    obdBeepPlayed = false
                    preflightObdSpoken = false
                    return@collect
                }
                if (!obdBeepPlayed) {
                    obdBeepPlayed = true
                    (speechSink as? AndroidTtsSpeechSink)?.playConnectionBeep()
                    val addr = obdManager.lastAttemptedAddress.value
                    if (!addr.isNullOrBlank()) {
                        val name = obdManager.discoveredDevices.value
                            .firstOrNull { it.address == addr }?.name
                            ?: uiState.value.settings.lastObdDeviceName.ifBlank { "OBD-II Adapter" }
                        prefsRepo.setLastObdDevice(addr, name)
                    }
                }
                if (!preflightObdSpoken) {
                    preflightObdSpoken = true
                    // Wait briefly for initial ATRV, MAF, and Coolant PIDs to arrive
                    var waitedMs = 0L
                    while (waitedMs < 2_500L) {
                        val t = obdManager.liveTelemetry.value
                        if ((t.controlModuleVoltageV != null || t.adapterVoltageV != null) &&
                            (t.mafGramsPerSec != null || t.engineRpm != null)
                        ) {
                            break
                        }
                        kotlinx.coroutines.delay(250L)
                        waitedMs += 250L
                    }
                    val t = obdManager.liveTelemetry.value
                    val s = uiState.value.settings
                    val volt = t.controlModuleVoltageV ?: t.adapterVoltageV
                    val voltText = when {
                        volt == null -> "Battery voltage check unavailable."
                        volt < 11.8f -> "Battery voltage check: ${"%.1f".format(volt)} volts, low."
                        else -> "Battery voltage check: ${"%.1f".format(volt)} volts, normal."
                    }
                    val maf = t.mafGramsPerSec
                    val mafText = when {
                        maf != null -> "Mass airflow check: ${"%.1f".format(maf)} grams per second, normal."
                        (t.engineRpm ?: 0) > 0 -> "Mass airflow check: engine running, nominal."
                        else -> "Mass airflow check: standby."
                    }
                    val coolantText = t.coolantTempC?.let { "Coolant temperature $it degrees." } ?: ""
                    val dtcs = obdManager.state.value.dtcCodes
                    val readiness = if (dtcs.isEmpty()) {
                        "All systems check, ready to start."
                    } else {
                        "Diagnostic code ${dtcs.first()} found. Systems check complete, ready to start."
                    }
                    val fullMsg = listOf(
                        "OBD connected.",
                        voltText,
                        mafText,
                        coolantText,
                        readiness,
                    ).filter { it.isNotBlank() }.joinToString(" ")
                    if (!s.alertsMuted) {
                        alertCoordinator.submit(
                            alert = AlertMessage(
                                id = UUID.randomUUID().toString(),
                                priority = AlertPriority.VEHICLE_DIAGNOSTIC,
                                spokenText = fullMsg,
                                displayText = "Pre-Flight Check: ${volt?.let { "%.1fV".format(it) } ?: "--"} • MAF ${maf?.let { "%.1fg/s".format(it) } ?: "--"}",
                                dedupKey = "obd_preflight_${System.currentTimeMillis() / 10000L}",
                                timestampMillis = System.currentTimeMillis(),
                            ),
                            speechRate = s.voiceSpeed,
                            language = s.language,
                            globalMute = s.alertsMuted,
                        )
                    }
                }
            }
        }
        // Independent Real-Time Driving Intelligence loop (4 Hz, off main thread, non-blocking)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (true) {
                val now = System.currentTimeMillis()
                val hub = sensorManagerRepo.state.value
                val fix = locationTracker.state.value.currentFix
                val obdConnected = obdManager.state.value.obdConnected
                val tel = obdManager.liveTelemetry.value
                val signLimit = visionPipeline.state.value.lastObservation?.category?.speedLimitKmh
                val effectiveSpeedKmh = maxOf(fix?.speedKmh ?: 0f, tel.vehicleSpeedKmh?.toFloat() ?: 0f)
                val isSensorMotion = !hub.motionAnalysis.phoneMovementDetected &&
                    kotlin.math.abs(hub.motionAnalysis.longitudinalAccelMps2) >= 1.0f

                if (fix != null && !gpsBeepPlayed) {
                    gpsBeepPlayed = true
                    (speechSink as? AndroidTtsSpeechSink)?.playConnectionBeep()
                }
                if (fix != null && (now - lastWeatherRefreshMs) >= 600_000L) {
                    lastWeatherRefreshMs = now
                    viewModelScope.launch {
                        weatherRepo.refreshWeather(fix.latitude, fix.longitude)
                    }
                }

                // Announce heading change when driving and cardinal direction shifts cleanly
                if (isDriving.value && effectiveSpeedKmh >= 12.0f && uiState.value.settings.compassHudEnabled && !uiState.value.settings.alertsMuted) {
                    val c = hub.compassReading.cardinal
                    if (lastAnnouncedCardinal == null) {
                        lastAnnouncedCardinal = c
                    } else if (c != lastAnnouncedCardinal) {
                        if (pendingCardinal != c) {
                            pendingCardinal = c
                            pendingCardinalSinceMs = now
                        } else if ((now - pendingCardinalSinceMs) >= 2_000L && (now - lastHeadingAnnounceMs) >= 20_000L) {
                            lastAnnouncedCardinal = c
                            lastHeadingAnnounceMs = now
                            pendingCardinal = null
                            val fullName = when (c) {
                                "N" -> "North"
                                "NE" -> "North East"
                                "E" -> "East"
                                "SE" -> "South East"
                                "S" -> "South"
                                "SW" -> "South West"
                                "W" -> "West"
                                "NW" -> "North West"
                                else -> c
                            }
                            val s = uiState.value.settings
                            alertCoordinator.submit(
                                alert = AlertMessage(
                                    id = UUID.randomUUID().toString(),
                                    priority = AlertPriority.NAVIGATION_INSTRUCTION,
                                    spokenText = "Heading $fullName, ${hub.compassReading.degrees} degrees",
                                    displayText = "Heading: ${hub.compassReading.formatted}",
                                    dedupKey = "heading_$c",
                                    timestampMillis = now,
                                ),
                                speechRate = s.voiceSpeed,
                                language = s.language,
                                globalMute = s.alertsMuted,
                            )
                        }
                    } else {
                        pendingCardinal = null
                    }
                } else if (!isDriving.value) {
                    lastAnnouncedCardinal = null
                    pendingCardinal = null
                }

                // Multi-sensor accident & unexpected engine stop monitoring during active trip
                val sSafety = uiState.value.settings
                if (isDriving.value && sSafety.accidentMonitoringEnabled) {
                    val sensitivity = runCatching {
                        AccidentSensitivityProfile.valueOf(sSafety.accidentSensitivity)
                    }.getOrDefault(AccidentSensitivityProfile.STANDARD)
                    val axes = hub.vehicleAxesSample
                    val linearMag = kotlin.math.sqrt(
                        axes.forwardMps2 * axes.forwardMps2 +
                            axes.lateralMps2 * axes.lateralMps2 +
                            axes.verticalMps2 * axes.verticalMps2,
                    )
                    val accelAvail = hub.physicalSensors.any { it.typeName == "Accelerometer" && it.isAvailable }
                    val gyroAvail = hub.physicalSensors.any { it.typeName == "Gyroscope" && it.isAvailable }
                    val safetyOut = accidentEngine.processFrame(
                        frame = SensorFrame(
                            timestampMillis = now,
                            forwardAccelMps2 = axes.forwardMps2,
                            lateralAccelMps2 = axes.lateralMps2,
                            verticalAccelMps2 = axes.verticalMps2,
                            linearMagMps2 = linearMag,
                            angularVelDegPerSec = kotlin.math.abs(Math.toDegrees(hub.motionAnalysis.yawRateRadPerSec.toDouble()).toFloat()),
                            pitchDeltaDeg = axes.pitchDeltaDeg,
                            rollDeltaDeg = axes.rollDeltaDeg,
                            speedKmh = effectiveSpeedKmh,
                            gpsAccuracyMeters = fix?.horizontalAccuracyMeters,
                            obdRpm = tel.engineRpm,
                        ),
                        mountStatus = hub.mountCalibrationStatus,
                        accelAvailable = accelAvail,
                        gyroAvailable = gyroAvail,
                        obdConnected = obdConnected,
                        sensitivity = sensitivity,
                        impactEnabled = sSafety.impactDetectionEnabled,
                        rolloverEnabled = sSafety.rolloverDetectionEnabled,
                        swerveEnabled = sSafety.swerveDetectionEnabled,
                    )
                    postImpactState.value = safetyOut.postImpactMonitoringActive
                    if (sSafety.engineStopDetectionEnabled) {
                        engineStopState.value = engineStopMonitor.evaluate(
                            nowMillis = now,
                            obdConnected = obdConnected,
                            rpm = tel.engineRpm,
                            speedKmh = effectiveSpeedKmh,
                            recentPeakImpactMps2 = safetyOut.assessment.impactIntensityMps2,
                            impactThresholdMps2 = sensitivity.impactThresholdMps2,
                            mountStatus = hub.mountCalibrationStatus,
                        )
                    }
                    if (safetyOut.assessment.eventClass.triggersCountdown && pendingAccidentState.value == null) {
                        tripActivityLogger.logActivity(
                            type = "ACCIDENT_DETECTED",
                            details = "${safetyOut.assessment.eventClass.label} (${safetyOut.assessment.confidencePercent}%): ${safetyOut.assessment.triggeringMeasurements.joinToString("; ")}",
                            timestampMillis = now,
                        )
                        startEmergencyCountdown(safetyOut.assessment)
                    }
                }

                // Auto-start is disabled: driving only starts when the user explicitly taps Start Driving.
                motionDetectedSinceMs = 0L

                // Record GPS track & OBD fuel/voltage stats during active trip
                if (isDriving.value) {
                    if (fix != null) {
                        val movedEnough = lastTrackLat == null || lastTrackLng == null ||
                            ke.co.kopilot.feature.navigation.NavigationRepository.haversineMeters(
                                lastTrackLat!!,
                                lastTrackLng!!,
                                fix.latitude,
                                fix.longitude,
                            ) >= 20.0
                        if (movedEnough || (now - lastTrackMs) >= 5_000L) {
                            lastTrackLat = fix.latitude
                            lastTrackLng = fix.longitude
                            lastTrackMs = now
                            val pt = com.google.android.gms.maps.model.LatLng(fix.latitude, fix.longitude)
                            tripBreadcrumbFlow.value = (tripBreadcrumbFlow.value + pt).takeLast(500)
                            tripActivityLogger.logActivity(
                                type = "GPS_TRACK",
                                details = "${"%.6f".format(java.util.Locale.US, fix.latitude)},${"%.6f".format(java.util.Locale.US, fix.longitude)}",
                                timestampMillis = now,
                            )
                        }

                        // Resolve current road name every 15s during active drive;
                        // speak road name 2 minutes after drive starts and every time the road changes
                        if ((now - lastRoadLookupMs) >= 15_000L) {
                            lastRoadLookupMs = now
                            val startedMs = tripStartedAt.value ?: now
                            val elapsedDriveMs = (now - startedMs).coerceAtLeast(0L)
                            val road = resolveRoadNameForLocation(fix.latitude, fix.longitude)
                            if (!road.isNullOrBlank()) {
                                val prevRoad = currentRoadNameFlow.value
                                currentRoadNameFlow.value = road
                                val s = uiState.value.settings
                                val isTwoMinTrigger = !twoMinuteRoadAnnounced && elapsedDriveMs >= 120_000L
                                val isRoadChanged = lastAnnouncedRoadName != null &&
                                    !road.equals(lastAnnouncedRoadName, ignoreCase = true) &&
                                    !road.equals(prevRoad, ignoreCase = true)
                                if (isTwoMinTrigger || isRoadChanged) {
                                    if (isTwoMinTrigger) twoMinuteRoadAnnounced = true
                                    lastAnnouncedRoadName = road
                                    val headingDir = hub.compassReading.cardinal
                                    val spoken = if (isTwoMinTrigger) {
                                        "You are driving on $road, heading $headingDir."
                                    } else {
                                        "Now entering $road, heading $headingDir."
                                    }
                                    tripActivityLogger.logActivity("ROAD_ROUTE", spoken, now)
                                    if (!s.alertsMuted && s.navVoiceGuidanceEnabled) {
                                        alertCoordinator.submit(
                                            alert = AlertMessage(
                                                id = UUID.randomUUID().toString(),
                                                priority = AlertPriority.NAVIGATION_INSTRUCTION,
                                                spokenText = spoken,
                                                displayText = "Road: $road ($headingDir)",
                                                dedupKey = "road_name_${road.lowercase()}",
                                                timestampMillis = now,
                                            ),
                                            speechRate = s.voiceSpeed,
                                            language = s.language,
                                            globalMute = s.alertsMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (obdConnected) {
                        tel.mafGramsPerSec?.let { maf ->
                            if (maf > 0f) {
                                // Gasoline stoichiometric ~14.7:1, density ~745 g/L -> L/h = (maf * 3600) / (14.7 * 745)
                                val lph = (maf * 3600.0) / (14.7 * 745.0)
                                fuelRateSum += lph
                                fuelRateCount += 1
                            }
                        }
                        (tel.controlModuleVoltageV ?: tel.adapterVoltageV)?.let { v ->
                            if (v in 6.0f..18.0f) {
                                voltSum += v
                                voltCount += 1
                                if (v < voltMin) voltMin = v
                                if (v > voltMax) voltMax = v
                            }
                        }
                    }
                }

                // Auto-stop trip and sensors when car stays stopped for 90s
                if (isDriving.value) {
                    if (effectiveSpeedKmh >= 3.0f || isSensorMotion) {
                        lastVehicleMovingMs = now
                    } else if (lastVehicleMovingMs > 0L && (now - lastVehicleMovingMs) >= 90_000L) {
                        lastVehicleMovingMs = 0L
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            endTrip()
                        }
                    }
                }

                val evt = realTimeIntelligence.update(
                    RealTimeIntelligenceInput(
                        nowMillis = now,
                        isJourneyActive = isDriving.value,
                        motionAnalysis = hub.motionAnalysis,
                        gpsSpeedKmh = fix?.speedKmh,
                        speedLimitKmh = signLimit,
                        obdConnected = obdConnected,
                        obdRpm = tel.engineRpm,
                        obdThrottlePct = tel.throttlePositionPct,
                        obdEngineLoadPct = tel.engineLoadPct,
                        obdMafGramsPerSec = tel.mafGramsPerSec,
                    ),
                )
                if (evt != null && isDriving.value) {
                    tripActivityLogger.logActivity(
                        type = "DRIVING_EVENT",
                        details = "${evt.title}: ${evt.detail} (Safety ${evt.safetyImpact}, Fuel ${evt.fuelImpact})",
                        timestampMillis = evt.timestampMillis,
                    )
                    val s = uiState.value.settings
                    val allowRoadBump = evt.type != ke.co.kopilot.feature.sensors.DrivingEventType.POTHOLE_IMPACT || s.alertRoadQualityEnabled
                    val shouldSpeak = allowRoadBump &&
                        evt.type.isSafetyCritical &&
                        !s.alertsMuted &&
                        (evt.type.name != lastIntelVoiceType || (now - lastIntelVoiceAlertMs) >= 30_000L)
                    if (shouldSpeak && (s.voiceMode != VoiceMode.QUIET || evt.severity == ke.co.kopilot.feature.sensors.EventSeverity.HIGH)) {
                        lastIntelVoiceAlertMs = now
                        lastIntelVoiceType = evt.type.name
                        val spokenMsg = if (evt.type == ke.co.kopilot.feature.sensors.DrivingEventType.POTHOLE_IMPACT) {
                            evt.detail
                        } else {
                            "${evt.title}. ${evt.detail}"
                        }
                        alertCoordinator.submit(
                            alert = AlertMessage(
                                id = UUID.randomUUID().toString(),
                                priority = AlertPriority.ROAD_OBSERVATION,
                                spokenText = spokenMsg,
                                displayText = evt.detail,
                                dedupKey = "intel_${evt.type.name}",
                                timestampMillis = now,
                            ),
                            speechRate = s.voiceSpeed,
                            language = s.language,
                            globalMute = s.alertsMuted,
                        )
                    }
                }
                kotlinx.coroutines.delay(250L)
            }
        }
        viewModelScope.launch {
            obdManager.liveTelemetry.collect { tel ->
                if (!obdManager.state.value.obdConnected) return@collect
                val s = uiState.value.settings
                val now = System.currentTimeMillis()
                if ((now - lastEcuAlertMs) < 25_000L) return@collect

                val coolant = tel.coolantTempC
                val volt = tel.controlModuleVoltageV ?: tel.adapterVoltageV
                val rpm = tel.engineRpm
                val dtcs = obdManager.state.value.dtcCodes
                val ecuWarning: Pair<String, String>? = when {
                    coolant != null && coolant >= 102 ->
                        "Warning: Engine coolant temperature high at $coolant degrees Celsius." to "ECU Coolant High ($coolant°C)"
                    volt != null && volt in 5.0f..11.7f ->
                        "Warning: Vehicle battery voltage low at ${"%.1f".format(volt)} volts." to "ECU Voltage Low (${"%.1f".format(volt)}V)"
                    rpm != null && rpm >= 6000 ->
                        "Warning: High engine RPM at $rpm." to "High Engine RPM ($rpm)"
                    dtcs.isNotEmpty() ->
                        "Vehicle diagnostic trouble code detected: ${dtcs.take(2).joinToString(", ")}." to "OBD-II DTC: ${dtcs.first()}"
                    else -> null
                }
                if (ecuWarning != null) {
                    lastEcuAlertMs = now
                    if (isDriving.value) {
                        tripActivityLogger.logActivity("ECU_ALERT", ecuWarning.second, now)
                    }
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = ecuWarning.first,
                            displayText = ecuWarning.second,
                            dedupKey = "ecu_${ecuWarning.second}",
                            timestampMillis = now,
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }
            }
        }
        viewModelScope.launch {
            sensorManagerRepo.state.collect { hub ->
                if (!isDriving.value) return@collect
                val now = System.currentTimeMillis()
                if ((now - lastSensorSnapshotLoggedMs) < 10_000L) return@collect
                lastSensorSnapshotLoggedMs = now

                val motion = hub.motionAnalysis
                val stats = motion.behaviourStats
                val fix = locationTracker.state.value.currentFix
                val gpsStr = if (fix != null) {
                    "Speed=${fix.speedKmh?.let { "%.1f".format(it) } ?: "--"}km/h Acc=${fix.horizontalAccuracyMeters?.let { "%.0fm".format(it) } ?: "--"}"
                } else {
                    "GPS=Unavailable"
                }
                val sensorsStr = hub.physicalSensors.filter { it.isAvailable }.take(10).joinToString("; ") { s ->
                    "${s.typeName}=${s.formattedReading}"
                }.ifBlank { "No physical sensors" }
                val obd = obdManager.liveTelemetry.value
                val obdStr = if (obdManager.state.value.obdConnected) {
                    " | OBD: RPM=${obd.engineRpm ?: "--"} ECU_Spd=${obd.vehicleSpeedKmh ?: "--"} Coolant=${obd.coolantTempC ?: "--"}C Volt=${obd.controlModuleVoltageV?.let { "%.1fV".format(it) } ?: "--"}"
                } else {
                    ""
                }
                tripActivityLogger.logActivity(
                    type = "SENSOR_SNAPSHOT",
                    details = "$gpsStr | RoadScore=${stats.roadSmoothnessScore}/100 (${motion.roadVibrationLevel.name}) RMS=${"%.2f".format(motion.verticalVibrationRmsMps2)}m/s2 Bumps=${stats.potholeBumpCount} | " +
                        "DriverScore=${stats.driverBehaviourScore}/100 Long=${"%.2f".format(motion.longitudinalAccelMps2)} Lat=${"%.2f".format(motion.lateralAccelMps2)} Yaw=${"%.2f".format(motion.yawRateRadPerSec)} " +
                        "Pitch=${"%.1f".format(motion.pitchDegrees)} Roll=${"%.1f".format(motion.rollDegrees)} Az=${"%.0f".format(motion.azimuthDegrees)}$obdStr | Sensors($sensorsStr)",
                    timestampMillis = now,
                )
            }
        }
        viewModelScope.launch {
            visionPipeline.state.collect { vision ->
                val s = uiState.value.settings
                val currentSpeed = locationTracker.state.value.currentFix?.speedKmh ?: 0f
                val moving = isDriving.value && currentSpeed >= 8f
                val now = System.currentTimeMillis()
                val plate = vision.lastDetectedPlate
                if (isDriving.value && plate != null && plate != lastLoggedPlate) {
                    lastLoggedPlate = plate
                    tripActivityLogger.logActivity("PLATE_DETECTED", "Vehicle plate spotted: $plate", now)
                }
                val plateObs = vision.lastPlateObservation
                if (plateObs != null && s.alertNumberPlatesEnabled) {
                    val watchedMatch = KenyanPlateValidator.matchesWatchedPlate(
                        plateObs.rawNormalizedPlate,
                        s.watchedPlatesList(),
                    )
                    if (watchedMatch != null &&
                        (watchedMatch != lastWatchedPlateAlerted || (now - lastWatchedPlateAlertMs) >= 15_000L)
                    ) {
                        lastWatchedPlateAlerted = watchedMatch
                        lastWatchedPlateAlertMs = now
                        if (isDriving.value) {
                            tripActivityLogger.logActivity("WATCHED_PLATE_MATCH", "Watched plate spotted ahead: ${plateObs.rawNormalizedPlate}", now)
                        }
                        val spokenPlate = KenyanPlateValidator.formatForSpeech(plateObs.rawNormalizedPlate)
                        alertCoordinator.submit(
                            alert = AlertMessage(
                                id = UUID.randomUUID().toString(),
                                priority = AlertPriority.ROAD_OBSERVATION,
                                spokenText = "Watched number plate spotted ahead: $spokenPlate",
                                displayText = "Watched Plate Ahead: ${plateObs.rawNormalizedPlate}",
                                dedupKey = "watched_plate_${plateObs.rawNormalizedPlate}",
                                timestampMillis = now,
                            ),
                            speechRate = s.voiceSpeed,
                            language = s.language,
                            globalMute = s.alertsMuted,
                        )
                    }
                }
                val roadObjects = vision.trackedObjects.filter { it.label != "Road Object" }
                val closeVehicleAhead = roadObjects.any {
                    val cx = (it.normalizedLeft + it.normalizedRight) / 2f
                    cx in 0.28f..0.72f && it.normalizedBottom >= 0.62f
                }
                // Only warn about car ahead braking when actively driving >= 20 km/h AND a close vehicle ahead has confirmed red brake lights
                if (isDriving.value && currentSpeed >= 20f && closeVehicleAhead && vision.redBrakeLightsAhead && s.alertTrafficLightsEnabled) {
                    if ((now - lastLoggedBrakeLightMs) >= 25_000L) {
                        lastLoggedBrakeLightMs = now
                        tripActivityLogger.logActivity("BRAKE_LIGHTS_AHEAD", "Red brake lights ahead — Slow down", now)
                        alertCoordinator.submit(
                            alert = AlertMessage(
                                id = UUID.randomUUID().toString(),
                                priority = AlertPriority.ROAD_OBSERVATION,
                                spokenText = "Car in front is braking. Slow down.",
                                displayText = "Car braking ahead — Slow down",
                                dedupKey = "front_brake_lights",
                                timestampMillis = now,
                            ),
                            speechRate = s.voiceSpeed,
                            language = s.language,
                            globalMute = s.alertsMuted,
                        )
                    }
                }
                if (isDriving.value && s.sensorCameraEnabled) {
                    if (roadObjects.isNotEmpty() || vision.redBrakeLightsAhead) {
                        clearRoadSinceMs = 0L
                    } else if (clearRoadSinceMs == 0L) {
                        clearRoadSinceMs = now
                    }

                    if (roadObjects.isEmpty() && !vision.redBrakeLightsAhead &&
                        clearRoadSinceMs > 0L && (now - clearRoadSinceMs) >= 4_000L &&
                        currentSpeed in 40f..80f &&
                        (vision.lastSpeedLimitKmh == null || currentSpeed < (vision.lastSpeedLimitKmh!! - 5)) &&
                        (now - lastTutorClearRoadMs) >= 25_000L
                    ) {
                        lastTutorClearRoadMs = now
                        tripActivityLogger.logActivity("ROAD_TUTOR", "Clear road at ${currentSpeed.toInt()} km/h — pick up pace", now)
                        alertCoordinator.submit(
                            alert = AlertMessage(
                                id = UUID.randomUUID().toString(),
                                priority = AlertPriority.TRAFFIC_UPDATE,
                                spokenText = "Clear road ahead. Please pick up the pace and speed up a bit.",
                                displayText = "Clear road — Pick up pace",
                                dedupKey = "tutor_clear_road",
                                timestampMillis = now,
                            ),
                            speechRate = s.voiceSpeed,
                            language = s.language,
                            globalMute = s.alertsMuted,
                        )
                    }
                }
                if (isDriving.value && vision.trafficLightSignal != TrafficLightSignal.NONE &&
                    (vision.trafficLightSignal != lastLoggedTrafficSignal || (now - lastLoggedTrafficSignalMs) >= 8_000L)
                ) {
                    lastLoggedTrafficSignal = vision.trafficLightSignal
                    lastLoggedTrafficSignalMs = now
                    tripActivityLogger.logActivity("TRAFFIC_LIGHT", "${vision.trafficLightSignal.name} traffic light ahead", now)
                }
                if (s.alertTrafficLightsEnabled) when (vision.trafficLightSignal) {
                    TrafficLightSignal.RED -> alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = "Red traffic light ahead. Prepare to stop.",
                            displayText = "Red Traffic Light Ahead — Stop",
                            dedupKey = "front_tl_red",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                    TrafficLightSignal.AMBER -> alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = "Amber traffic light ahead. Prepare to stop if safe.",
                            displayText = "Amber Traffic Light Ahead",
                            dedupKey = "front_tl_amber",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                    TrafficLightSignal.GREEN -> if (s.voiceMode != VoiceMode.QUIET) alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.TRAFFIC_UPDATE,
                            spokenText = "Green traffic light ahead. Proceed with caution.",
                            displayText = "Green Traffic Light Ahead",
                            dedupKey = "front_tl_green",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                    TrafficLightSignal.UNCERTAIN, TrafficLightSignal.NONE -> Unit
                }
                if (vision.isDarkScene && moving && s.alertWeatherEnabled) {
                    if (isDriving.value && (now - lastLoggedDarkMs) >= 15_000L) {
                        lastLoggedDarkMs = now
                        tripActivityLogger.logActivity("HEADLIGHT_REMINDER", "Low ambient light (${vision.averageLuminance.toInt()}) — Turn on headlights", now)
                    }
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.WEATHER_NOTIFICATION,
                            spokenText = "It is getting dark. Please turn on your headlights.",
                            displayText = "Low light — Turn on headlights",
                            dedupKey = "headlight_darkness_reminder",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }
                val shift = vision.laneOverlay.shiftEvent
                val laneDirection = when (shift) {
                    LaneShiftEvent.SHIFTING_LEFT, LaneShiftEvent.LANE_DEPARTURE_LEFT -> "LEFT"
                    LaneShiftEvent.SHIFTING_RIGHT, LaneShiftEvent.LANE_DEPARTURE_RIGHT -> "RIGHT"
                    LaneShiftEvent.NONE -> null
                }
                if (!vision.laneOverlay.lanesDetected || laneDirection == null) {
                    lastAnnouncedLaneDirection = null
                } else if (isDriving.value && s.alertLaneTurnEnabled && laneDirection != lastAnnouncedLaneDirection && (now - lastLaneAlertMs) >= 1_500L) {
                    lastAnnouncedLaneDirection = laneDirection
                    lastLaneAlertMs = now
                    val laneText = if (laneDirection == "LEFT") "Drifting left" else "Drifting right"
                    tripActivityLogger.logActivity("LANE_SHIFT", laneText, now)
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = laneText,
                            displayText = laneText,
                            dedupKey = "lane_shift_$laneDirection",
                            timestampMillis = now,
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }
            }
        }
        viewModelScope.launch {
            sensorManagerRepo.state.collect { sensorState ->
                if (!isDriving.value) {
                    lastTurnReferenceHeadingDeg = null
                    lastTurnReferenceCardinal = null
                    lastAnnouncedTurnState = LateralMotionState.STRAIGHT
                    return@collect
                }
                val s = uiState.value.settings
                if (!s.alertLaneTurnEnabled) return@collect
                val gpsSpeed = locationTracker.state.value.currentFix?.speedKmh ?: 0f
                val obdSpeed = obdManager.liveTelemetry.value.vehicleSpeedKmh?.toFloat() ?: 0f
                val effectiveSpeed = maxOf(gpsSpeed, obdSpeed)
                if (effectiveSpeed < 10f) return@collect

                val compass = sensorState.compassReading
                val currentDeg = compass.degrees.toFloat()
                val currentCardinal = compass.cardinal
                val refDeg = lastTurnReferenceHeadingDeg
                val refCardinal = lastTurnReferenceCardinal

                if (refDeg == null || refCardinal == null) {
                    lastTurnReferenceHeadingDeg = currentDeg
                    lastTurnReferenceCardinal = currentCardinal
                    return@collect
                }

                // Turning signal informed by the Compass: requires a cardinal direction change
                // (e.g., heading NE -> N) AND at least a 20-degree (20-point) change in compass heading.
                val deltaDeg = ke.co.kopilot.feature.sensors.CompassHeadingEstimator.signedAngleDelta(refDeg, currentDeg)
                if (currentCardinal != refCardinal && kotlin.math.abs(deltaDeg) >= 20f) {
                    val now = System.currentTimeMillis()
                    lastTurnReferenceHeadingDeg = currentDeg
                    lastTurnReferenceCardinal = currentCardinal
                    if ((now - lastTurnAlertMs) < 5_000L) return@collect
                    lastTurnAlertMs = now

                    val isLeftTurn = deltaDeg < 0f
                    val tag = if (isLeftTurn) "TURNING_LEFT" else "TURNING_RIGHT"
                    val fullCardinal = when (currentCardinal) {
                        "N" -> "North"
                        "NE" -> "North East"
                        "E" -> "East"
                        "SE" -> "South East"
                        "S" -> "South"
                        "SW" -> "South West"
                        "W" -> "West"
                        "NW" -> "North West"
                        else -> currentCardinal
                    }
                    val spokenMsg = if (isLeftTurn) {
                        "Turning left, now heading $fullCardinal"
                    } else {
                        "Turning right, now heading $fullCardinal"
                    }
                    val displayMsg = if (isLeftTurn) {
                        "Turning Left • Heading $currentCardinal (${compass.degrees}°)"
                    } else {
                        "Turning Right • Heading $currentCardinal (${compass.degrees}°)"
                    }
                    tripActivityLogger.logActivity(tag, "$displayMsg (from $refCardinal)", now)
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = spokenMsg,
                            displayText = displayMsg,
                            dedupKey = "compass_turn_${tag}_$currentCardinal",
                            timestampMillis = now,
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                } else if (currentCardinal == refCardinal && kotlin.math.abs(deltaDeg) < 10f) {
                    // Gently keep reference aligned with current cardinal cruise direction
                    lastTurnReferenceHeadingDeg = currentDeg
                }
            }
        }
        viewModelScope.launch {
            locationTracker.state.collect { locState ->
                val fix = locState.currentFix ?: return@collect
                val kmh = fix.speedKmh
                if (isDriving.value && kmh != null) {
                    maxSpeedObservedKmh = maxOf(maxSpeedObservedKmh, kmh)
                    speedSampleSum += kmh
                    speedSampleCount += 1
                    val now = System.currentTimeMillis()
                    val speedDelta = kotlin.math.abs(kmh - lastLoggedSpeedKmh)
                    if ((now - lastSpeedLogMs) >= 20_000L || (lastLoggedSpeedKmh >= 0f && speedDelta >= 15f)) {
                        lastSpeedLogMs = now
                        lastLoggedSpeedKmh = kmh
                        val distKm = locState.distanceTraveledMeters / 1000.0
                        tripActivityLogger.logActivity(
                            type = "GPS_SPEED",
                            details = "Speed=${"%.1f".format(kmh)} km/h | Distance=${"%.2f".format(distKm)} km | GPS=${"%.5f".format(fix.latitude)},${"%.5f".format(fix.longitude)}",
                            timestampMillis = now,
                        )
                    }
                }
                weatherRepo.refreshWeather(fix.latitude, fix.longitude)
                navigationRepo.refreshRoadIntelligence(fix.latitude, fix.longitude)
                val roadIntel = navigationRepo.state.value.roadIntelligence
                val camDist = roadIntel.nearestSpeedCameraMeters
                if (camDist != null && camDist <= 500 && uiState.value.settings.alertSpeedLimitEnabled) {
                    val s = uiState.value.settings
                    val now = System.currentTimeMillis()
                    if (isDriving.value && (now - lastLoggedCameraMs) >= 15_000L) {
                        lastLoggedCameraMs = now
                        tripActivityLogger.logActivity("SPEED_CAMERA", "Mapped speed camera $camDist m ahead", now)
                    }
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = "Mapped speed camera $camDist metres ahead.",
                            displayText = "Speed camera $camDist m ahead",
                            dedupKey = "osm_speed_camera",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }
                weatherRepo.state.value.advisoryMessage?.let { weatherAdv ->
                    val s = uiState.value.settings
                    if (!s.alertWeatherEnabled) return@let
                    if (isDriving.value && weatherAdv != lastLoggedWeather) {
                        lastLoggedWeather = weatherAdv
                        tripActivityLogger.logActivity("WEATHER", weatherAdv)
                    }
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.WEATHER_NOTIFICATION,
                            spokenText = weatherAdv,
                            displayText = weatherAdv,
                            dedupKey = "weather_$weatherAdv",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }

                val speedEval = speedAlertEngine.evaluate(
                    fix = fix,
                    latestSign = visionPipeline.state.value.lastObservation,
                    thresholdKmh = uiState.value.settings.speedAlertThresholdKmh,
                )
                if (speedEval.decision == SpeedAlertDecision.POSSIBLE_OVERSPEED_WARNING &&
                    speedEval.applicableSpeedLimitKmh != null &&
                    uiState.value.settings.alertSpeedLimitEnabled
                ) {
                    val s = uiState.value.settings
                    val limit = speedEval.applicableSpeedLimitKmh!!
                    val now = System.currentTimeMillis()
                    if (isDriving.value && (now - lastLoggedOverspeedMs) >= 10_000L) {
                        lastLoggedOverspeedMs = now
                        val cur = speedEval.currentSpeedKmh?.toInt() ?: 0
                        tripActivityLogger.logActivity("OVERSPEED_WARNING", "Current speed $cur km/h exceeds $limit km/h limit", now)
                    }
                    val msg = "You are exceeding the observed $limit kilometres per hour speed limit. Please slow down."
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = msg,
                            displayText = "Overspeed: Observed $limit km/h limit",
                            dedupKey = "overspeed_limit_$limit",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }

                val zoneEval = speedZoneMonitor.evaluate(
                    fix = fix,
                    signalState = speedEval.signalState,
                    isDrivingActive = isDriving.value,
                )
                currentSpeedZone.value = zoneEval.zone
                val spoken = zoneEval.zone.spokenAlert
                if (isDriving.value && zoneEval.shouldNotify && spoken != null) {
                    tripActivityLogger.logActivity("SPEED_ZONE", "${zoneEval.zone.name}: $spoken")
                    val s = uiState.value.settings
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = if (zoneEval.zone == SpeedZone.DANGEROUS_DRIVING || zoneEval.zone == SpeedZone.REDUCE_SPEED) {
                                AlertPriority.ROAD_OBSERVATION
                            } else {
                                AlertPriority.TRAFFIC_UPDATE
                            },
                            spokenText = spoken,
                            displayText = zoneEval.zone.displayBanner ?: spoken,
                            dedupKey = "speed_zone_${zoneEval.zone.name}",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }
            }
        }
    }

    private fun tryAutoConnectObd(s: KoPilotSettings = uiState.value.settings) {
        if (!s.sensorObdEnabled || !bluetoothGranted || obdManager.state.value.obdConnected) return
        if (autoObdConnectAttempted) return
        val paired = obdManager.listPairedDevices(bluetoothGranted)
        val targetAddress = when {
            s.lastObdDeviceAddress.isNotBlank() -> s.lastObdDeviceAddress
            else -> paired.firstOrNull { dev ->
                val n = dev.name.uppercase()
                n.contains("OBD") || n.contains("ELM") || n.contains("V-LINK") ||
                    n.contains("VLINK") || n.contains("VEEPEAK") || n.contains("KONNWEI") ||
                    n.contains("VGATE") || n.contains("CARISTA") || n.contains("CHX") ||
                    n.contains("CBT") || n.contains("SCAN")
            }?.address ?: ""
        }
        if (targetAddress.isNotBlank()) {
            autoObdConnectAttempted = true
            val matchedName = paired.firstOrNull { it.address == targetAddress }?.name
                ?: s.lastObdDeviceName.ifBlank { "OBD-II Adapter" }
            viewModelScope.launch {
                prefsRepo.setLastObdDevice(targetAddress, matchedName)
            }
            obdManager.connectAndMonitor(targetAddress, bluetoothGranted)
        }
    }

    fun updatePermissions(
        cameraGranted: Boolean,
        locationGranted: Boolean,
        micGranted: Boolean,
        bluetoothGranted: Boolean = this.bluetoothGranted,
    ) {
        this.bluetoothGranted = bluetoothGranted
        cameraController.setPermissionGranted(cameraGranted)
        locationTracker.setPermissionGranted(locationGranted)
        voiceClient.setMicPermissionGranted(micGranted)
        tryAutoConnectObd(uiState.value.settings)
        smartwatchConnectionManager.tryRestoreSavedWatch(bluetoothGranted)
        if (locationGranted && isDriving.value) {
            locationTracker.startTracking(highAccuracy = true)
        }
    }

    fun acceptSafetyDisclosure() = viewModelScope.launch {
        prefsRepo.setSafetyDisclosureAccepted(true)
    }

    fun recalibratePhoneMount() {
        sensorManagerRepo.recalibrateMount()
    }

    fun startEmergencyCountdown(assessment: AccidentAssessment) {
        if (pendingAccidentState.value != null) return
        val totalSec = uiState.value.settings.emergencyCountdownSeconds.coerceIn(5, 30)
        pendingAccidentState.value = assessment
        countdownRemainingState.value = totalSec
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = totalSec
            while (remaining > 0 && pendingAccidentState.value != null) {
                countdownRemainingState.value = remaining
                if (uiState.value.settings.accidentVoiceWarningsEnabled) {
                    (speechSink as? AndroidTtsSpeechSink)?.playConnectionBeep()
                }
                kotlinx.coroutines.delay(1_000L)
                remaining--
            }
            if (pendingAccidentState.value != null) {
                countdownRemainingState.value = 0
                emergencyDispatchTrigger.value = UUID.randomUUID().toString()
            }
        }
    }

    fun cancelEmergencyCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        val cancelled = pendingAccidentState.value
        pendingAccidentState.value = null
        countdownRemainingState.value = 0
        if (cancelled != null && isDriving.value) {
            tripActivityLogger.logActivity(
                type = "ACCIDENT_CANCELLED",
                details = "Driver cancelled ${cancelled.eventClass.label} (${cancelled.confidencePercent}%)",
                timestampMillis = System.currentTimeMillis(),
            )
        }
    }

    fun sendEmergencyAlertNow() {
        countdownJob?.cancel()
        countdownJob = null
        countdownRemainingState.value = 0
        if (pendingAccidentState.value != null) {
            emergencyDispatchTrigger.value = UUID.randomUUID().toString()
        }
    }

    fun acknowledgeEmergencyDispatch() {
        pendingAccidentState.value = null
        countdownRemainingState.value = 0
        emergencyDispatchTrigger.value = null
    }

    fun triggerTestEmergencyWorkflow() {
        val fix = locationTracker.state.value.currentFix
        val testAssessment = AccidentAssessment(
            eventClass = AccidentEventClass.SUSPECTED_COLLISION,
            confidencePercent = 82,
            timestampMillis = System.currentTimeMillis(),
            preEventSpeedKmh = fix?.speedKmh ?: 45f,
            postEventSpeedKmh = 0f,
            impactIntensityMps2 = 23.4f,
            orientationChangeDeg = 18f,
            gpsAccuracyMeters = fix?.horizontalAccuracyMeters,
            sensorReliability = "Test Simulation",
            triggeringMeasurements = listOf("Simulated test workflow (no real collision)"),
            missingEvidence = emptyList(),
        )
        startEmergencyCountdown(testAssessment)
    }

    fun startDriving() {
        val started = System.currentTimeMillis()
        val tripId = UUID.randomUUID().toString()
        currentActiveTripId = tripId
        lastLoggedHeartRateMs = 0L
        lastRoadLookupMs = 0L
        lastAnnouncedRoadName = null
        twoMinuteRoadAnnounced = false
        tripBreadcrumbFlow.value = emptyList()
        isDriving.value = true
        tripStartedAt.value = started
        maxSpeedObservedKmh = 0f
        speedSampleSum = 0f
        speedSampleCount = 0
        signsDetectedInTrip = 0
        lastSpeedLogMs = 0L
        lastLoggedSpeedKmh = -1f
        lastLoggedPlate = null
        lastLoggedTrafficSignal = TrafficLightSignal.NONE
        lastLoggedTrafficSignalMs = 0L
        lastLoggedBrakeLightMs = 0L
        lastLoggedDarkMs = 0L
        lastLoggedCameraMs = 0L
        lastLoggedOverspeedMs = 0L
        lastLoggedWeather = null
        lastLaneAlertMs = 0L
        lastTurnAlertMs = 0L
        lastAnnouncedLaneDirection = null
        lastAnnouncedTurnState = LateralMotionState.STRAIGHT
        lastTutorVehicleAheadMs = 0L
        lastTutorBusyRoadMs = 0L
        lastTutorClearRoadMs = 0L
        clearRoadSinceMs = 0L
        lastIntelVoiceAlertMs = 0L
        lastIntelVoiceType = null
        lastVehicleMovingMs = started
        motionDetectedSinceMs = 0L
        lastTrackLat = null
        lastTrackLng = null
        lastTrackMs = 0L
        fuelRateSum = 0.0
        fuelRateCount = 0
        voltSum = 0.0
        voltCount = 0
        voltMin = Float.MAX_VALUE
        voltMax = 0f
        explainedSignsInTrip.clear()
        realTimeIntelligence.resetForNewJourney(started)
        (speechSink as? AndroidTtsSpeechSink)?.playConnectionBeep()

        // Phase 6: Check smartwatch connection, attach active tripId, and avoid unnecessary BLE scans
        smartwatchConnectionManager.setActiveTripId(tripId)
        heartRateRepository.startTripMonitoring(tripId, started)
        smartwatchConnectionManager.tryRestoreSavedWatch(bluetoothGranted)

        val fix = locationTracker.state.value.currentFix
        val dest = navigationRepo.state.value.destinationName
        tripActivityLogger.startTripLog(
            startedAtMillis = started,
            originLabel = "Current Location",
            destinationLabel = dest,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
        )

        val s = uiState.value.settings
        locationTracker.resetTripDistance()
        if (s.sensorGpsEnabled) {
            locationTracker.startTracking(highAccuracy = true)
        }
        if (s.sensorMotionEnabled) {
            sensorManagerRepo.startMonitoring(SensorSamplingProfile.DRIVING)
        }
        // Voice AI is ready on-demand via the Voice AI button (avoids continuous SpeechRecognizer beep loop)
        if (isDriving.value) {
            val watchDiag = smartwatchConnectionManager.diagnostics.value
            val latestSleep = sleepAnalyticsRepository.state.value.latestSession
            val sleepContext = latestSleep?.let {
                "Last recorded sleep: ${SleepAnalyticsCalculator.formatDurationHoursMinutes(it.totalSleepDurationMinutes)} (${it.sourceAppName})"
            } ?: "No synchronized sleep record"

            tripActivityLogger.logActivity(
                type = "SENSORS_ACTIVE",
                details = "Camera=${s.sensorCameraEnabled}, GPS=${s.sensorGpsEnabled}, Motion=${s.sensorMotionEnabled}, Mic=${s.sensorMicEnabled}, OBD=${s.sensorObdEnabled}, Smartwatch=${watchDiag.connectionState.name}",
                timestampMillis = started,
            )
            tripActivityLogger.logActivity(
                type = "PRE_DRIVE_WELLNESS",
                details = "Smartwatch=${watchDiag.connectedName ?: "Not connected"} (${watchDiag.connectionState.displayName}) | $sleepContext",
                timestampMillis = started,
            )
        }
    }

    fun endTrip() {
        val started = tripStartedAt.value
        val ended = System.currentTimeMillis()
        val tripId = currentActiveTripId ?: UUID.randomUUID().toString()
        currentActiveTripId = null
        val distanceKm = locationTracker.state.value.distanceTraveledMeters / 1000.0
        val avgKmh = if (speedSampleCount > 0) speedSampleSum / speedSampleCount else 0f
        val destLabel = navigationRepo.state.value.destinationName
        val alertsCount = alertCoordinator.state.value.alertsTriggeredCount

        // Finalize Smartwatch Heart-Rate summary for this trip
        val hrSummary = heartRateRepository.finalizeTripMonitoring(ended)
        smartwatchConnectionManager.setActiveTripId(null)
        val latestSleepMins = sleepAnalyticsRepository.state.value.latestSession?.totalSleepDurationMinutes

        val hubState = sensorManagerRepo.state.value
        val motion = hubState.motionAnalysis
        val motionStats = motion.behaviourStats
        tripActivityLogger.logActivity(
            type = "ROAD_QUALITY",
            details = "Avg Score=${motionStats.averageRoadScore}/100 | Smooth=${motionStats.smoothPct}% | Moderate=${motionStats.moderatePct}% | Rough=${motionStats.roughPct}% | VeryRough=${motionStats.veryRoughPct}% | RMS=${"%.2f".format(motion.verticalVibrationRmsMps2)}m/s2 | Bumps=${motionStats.potholeBumpCount}",
            timestampMillis = ended,
        )
        tripActivityLogger.logActivity(
            type = "DRIVING_BEHAVIOUR",
            details = "Driver Score=${motionStats.driverBehaviourScore}/100 | Braking=${motionStats.brakingCount} (Harsh=${motionStats.harshBrakingCount}) | Accel=${motionStats.accelerationCount} (Hard=${motionStats.hardAccelerationCount}) | Sharp Turns=${motionStats.sharpCornerCount}",
            timestampMillis = ended,
        )
        val hrSummaryLine = if (hrSummary.sampleCount > 0) {
            "Avg=${hrSummary.avgBpm ?: "--"} BPM | Min=${hrSummary.minBpm ?: "--"} BPM | Max=${hrSummary.maxBpm ?: "--"} BPM | Samples=${hrSummary.sampleCount} | DisconnectGaps=${hrSummary.disconnectedGapsCount}"
        } else {
            "No live heart-rate samples recorded during trip (DisconnectGaps=${hrSummary.disconnectedGapsCount})"
        }
        val sleepSummaryLine = latestSleepMins?.let {
            "Pre-Drive Sleep=${SleepAnalyticsCalculator.formatDurationHoursMinutes(it)}"
        } ?: "Pre-Drive Sleep=Not available"
        tripActivityLogger.logActivity(
            type = "SMARTWATCH_HEALTH",
            details = "$hrSummaryLine | $sleepSummaryLine",
            timestampMillis = ended,
        )
        val allSensorsLine = hubState.physicalSensors.filter { it.isAvailable }.joinToString("; ") { s ->
            "${s.typeName}=${s.formattedReading}"
        }.ifBlank { "None" }
        val obdLive = obdManager.liveTelemetry.value
        val obdSummary = if (obdManager.state.value.obdConnected) {
            " | OBD(RPM=${obdLive.engineRpm ?: "--"}, Coolant=${obdLive.coolantTempC ?: "--"}C, Volt=${obdLive.controlModuleVoltageV?.let { "%.1fV".format(it) } ?: "--"})"
        } else {
            ""
        }
        val avgFuelLph = if (fuelRateCount > 0) fuelRateSum / fuelRateCount else null
        val durationHours = if (started != null) ((ended - started) / 3_600_000.0).coerceAtLeast(0.0) else 0.0
        val totalFuelLiters = avgFuelLph?.let { it * durationHours }
        val fuelSummary = when {
            totalFuelLiters != null && distanceKm >= 0.3 ->
                "${"%.2f".format(java.util.Locale.US, totalFuelLiters)} L (${"%.1f".format(java.util.Locale.US, (totalFuelLiters / distanceKm) * 100.0)} L/100km)"
            avgFuelLph != null -> "${"%.2f".format(java.util.Locale.US, avgFuelLph)} L/h"
            else -> "OBD Offline"
        }
        val voltSummary = when {
            voltCount > 0 -> "${"%.1f".format(java.util.Locale.US, voltSum / voltCount)}V (${"%.1f".format(java.util.Locale.US, voltMin)}-${"%.1f".format(java.util.Locale.US, voltMax)}V)"
            obdManager.state.value.obdConnected ->
                (obdLive.controlModuleVoltageV ?: obdLive.adapterVoltageV)?.let { "${"%.1f".format(java.util.Locale.US, it)}V" } ?: "N/A"
            else -> "OBD Offline"
        }
        val roadLabel = when {
            motionStats.averageRoadScore >= 85 -> "Smooth"
            motionStats.averageRoadScore >= 65 -> "Fair"
            else -> "Rough"
        }
        val hrTag = hrSummary.avgBpm?.let { " • HR ${it} BPM (${hrSummary.minBpm}-${hrSummary.maxBpm})" } ?: ""
        val overallSummary = "Safety ${motionStats.driverBehaviourScore}/100 • Road $roadLabel (${motionStats.averageRoadScore}/100)$hrTag"
        tripActivityLogger.logActivity(
            type = "TRIP_SUMMARY",
            details = "Fuel=$fuelSummary | Battery=$voltSummary | Summary=$overallSummary",
            timestampMillis = ended,
        )
        tripActivityLogger.logActivity(
            type = "ALL_SENSORS_SUMMARY",
            details = "Orientation(Pitch=${"%.1f".format(motion.pitchDegrees)}, Roll=${"%.1f".format(motion.rollDegrees)}, Az=${"%.0f".format(motion.azimuthDegrees)})$obdSummary | PhysicalSensors($allSensorsLine)",
            timestampMillis = ended,
        )

        tripActivityLogger.endTripLog(
            endedAtMillis = ended,
            distanceKm = distanceKm,
            maxSpeedKmh = maxSpeedObservedKmh,
            avgSpeedKmh = avgKmh,
            signsDetectedCount = signsDetectedInTrip,
            alertsTriggeredCount = alertsCount,
        )

        isDriving.value = false
        tripStartedAt.value = null
        lastTurnReferenceHeadingDeg = null
        lastTurnReferenceCardinal = null
        sensorManagerRepo.stopMonitoring()
        locationTracker.stopTracking()
        voiceClient.disconnect()
        speedAlertEngine.reset()
        speedZoneMonitor.reset()
        currentSpeedZone.value = SpeedZone.NORMAL

        if (started != null && uiState.value.settings.tripHistoryEnabled) {
            viewModelScope.launch {
                tripRepo.saveCompletedTrip(
                    tripId = tripId,
                    originLabel = "Current Location",
                    destinationLabel = destLabel,
                    startedAtMillis = started,
                    endedAtMillis = ended,
                    distanceKm = distanceKm,
                    maxSpeedKmh = maxSpeedObservedKmh,
                    avgSpeedKmh = avgKmh,
                    signsDetectedCount = signsDetectedInTrip,
                    alertsTriggeredCount = alertsCount,
                    avgHeartRateBpm = hrSummary.avgBpm,
                    minHeartRateBpm = hrSummary.minBpm,
                    maxHeartRateBpm = hrSummary.maxBpm,
                    heartRateSamplesCount = hrSummary.sampleCount,
                    watchDisconnectedGapsCount = hrSummary.disconnectedGapsCount,
                    preDriveSleepMinutes = latestSleepMins,
                )
            }
        }
    }

    fun onConfirmedSign(observation: TrafficSignObservation) {
        signsDetectedInTrip += 1
        val label = observation.customLabel ?: observation.category.displayName
        if (isDriving.value) {
            val pct = (observation.confidence * 100).toInt()
            val certainty = if (observation.isUncertain) "unsure" else "confirmed"
            tripActivityLogger.logActivity(
                type = "SIGN_DETECTED",
                details = "$label ($pct% $certainty, source=${observation.modelVersion})",
                timestampMillis = observation.timestampMillis,
            )
        }
        val settings = uiState.value.settings
        if (!settings.alertRoadSignsEnabled) return
        if (observation.isUncertain || observation.applicableToDriverLane == false) {
            return
        }
        if (!settings.announceAllSigns && observation.category.speedLimitKmh == null) {
            return
        }

        // Three Voice Modes: QUIET (warnings only), SMART_CO_DRIVER (default), LEARNING (explain each sign once)
        when (settings.voiceMode) {
            VoiceMode.QUIET -> {
                val isCriticalWarningSign = observation.category in setOf(
                    TrafficSignCategory.STOP,
                    TrafficSignCategory.GIVE_WAY,
                    TrafficSignCategory.NO_ENTRY,
                    TrafficSignCategory.RAILWAY_CROSSING,
                    TrafficSignCategory.BLACKSPOT,
                    TrafficSignCategory.POLICE_CHECKPOINT,
                )
                if (!isCriticalWarningSign) return
            }
            VoiceMode.LEARNING -> {
                if (!explainedSignsInTrip.add(observation.category)) {
                    return
                }
            }
            VoiceMode.SMART_CO_DRIVER -> Unit
        }

        val text = visionPipeline.signCatalog.getSpokenAdvice(observation.category, observation.customLabel)
        if (settings.alertChimeEnabled && !settings.alertsMuted && speechSink is AndroidTtsSpeechSink) {
            speechSink.playChime()
        }
        alertCoordinator.submit(
            alert = AlertMessage(
                id = UUID.randomUUID().toString(),
                priority = AlertPriority.ROAD_OBSERVATION,
                spokenText = text,
                displayText = text,
                dedupKey = "sign_${observation.category.name}_${observation.customLabel.orEmpty()}",
                timestampMillis = observation.timestampMillis,
            ),
            speechRate = settings.voiceSpeed,
            language = settings.language,
            globalMute = settings.alertsMuted,
        )
    }

    fun setVoiceMode(mode: VoiceMode) = viewModelScope.launch {
        prefsRepo.setVoiceMode(mode)
    }

    fun cycleCameraZoom() = viewModelScope.launch {
        val current = uiState.value.settings.cameraZoomRatio
        val next = when {
            current < 1.2f -> 1.3f
            current < 1.5f -> 1.6f
            current < 1.9f -> 2.0f
            else -> 1.0f
        }
        prefsRepo.setCameraZoomRatio(next)
    }

    fun toggleAlertsMute() = viewModelScope.launch {
        val next = !uiState.value.settings.alertsMuted
        prefsRepo.setAlertsMuted(next)
        if (next) alertCoordinator.muteForMinutes(60) else alertCoordinator.clearMute()
    }

    fun toggleCameraPrimaryLayout() = viewModelScope.launch {
        val speedKmh = uiState.value.speedEvaluation.currentSpeedKmh ?: 0f
        if (speedKmh < 5f) {
            prefsRepo.setCameraPrimaryLayout(!uiState.value.settings.cameraPrimaryLayout)
        }
    }

    fun toggleVoiceConnection() {
        val voiceState = uiState.value.voiceState
        val isActive = voiceState.connectionState == ke.co.kopilot.feature.voice.VoiceConnectionState.CONNECTED ||
            voiceState.connectionState == ke.co.kopilot.feature.voice.VoiceConnectionState.CONNECTING ||
            voiceState.connectionState == ke.co.kopilot.feature.voice.VoiceConnectionState.LOCAL_FALLBACK
        if (isActive) {
            if (isDriving.value) tripActivityLogger.logActivity("VOICE_ASSISTANT", "Voice assistant disconnected")
            voiceClient.disconnect()
        } else {
            val s = uiState.value.settings
            if (!s.sensorMicEnabled) return
            if (isDriving.value) tripActivityLogger.logActivity("VOICE_ASSISTANT", "Offline Voice AI started (${s.language}, voice=${s.aiVoiceName})")
            voiceClient.connect(language = s.language, voice = s.aiVoiceName, preferOffline = true)
        }
    }

    fun toggleVoiceMute() = voiceClient.toggleMute()

    fun searchDestinations(query: String) = viewModelScope.launch {
        val fix = locationTracker.state.value.currentFix
        navigationRepo.searchPlaces(query, fix?.latitude, fix?.longitude)
    }

    fun navigateToCoordinates(name: String, lat: Double, lng: Double) = viewModelScope.launch {
        val fix = locationTracker.state.value.currentFix
        val originLat = fix?.latitude ?: -1.286389
        val originLng = fix?.longitude ?: 36.817223
        val ok = navigationRepo.navigateTo(
            originLat = originLat,
            originLng = originLng,
            destName = name,
            destLat = lat,
            destLng = lng,
            avoidTolls = uiState.value.settings.avoidTolls,
        )
        if (ok) {
            prefsRepo.setCameraPrimaryLayout(false)
            val nav = navigationRepo.state.value
            val active = nav.activeRoute
            if (active != null) {
                val mins = (active.durationSeconds / 60).coerceAtLeast(1)
                val km = "%.1f".format(active.distanceMeters / 1000.0)
                val alt = nav.alternativeRoutes.firstOrNull()
                val comparison = if (alt != null && alt.durationSeconds > active.durationSeconds) {
                    val savedMin = ((alt.durationSeconds - active.durationSeconds) / 60).coerceAtLeast(1)
                    " Selected this route as it is $savedMin minutes faster than the alternative with heavier traffic."
                } else {
                    ""
                }
                val s = uiState.value.settings
                if (isDriving.value) {
                    tripActivityLogger.logActivity(
                        type = "NAVIGATION_ROUTE",
                        details = "Route to $name: $mins min, $km km via ${active.summary}",
                    )
                }
                alertCoordinator.submit(
                    alert = AlertMessage(
                        id = UUID.randomUUID().toString(),
                        priority = AlertPriority.NAVIGATION_INSTRUCTION,
                        spokenText = "Route to $name ready: $mins minutes, $km kilometres via ${active.summary}.$comparison",
                        displayText = "$name: $mins min ($km km) via ${active.summary}",
                        dedupKey = "nav_route_$name",
                        timestampMillis = System.currentTimeMillis(),
                    ),
                    speechRate = s.voiceSpeed,
                    language = s.language,
                    globalMute = s.alertsMuted,
                )
            }
        }
    }

    fun analysisIntervalFor(quality: DetectionQuality): Long = when (quality) {
        DetectionQuality.FAST -> 80L
        DetectionQuality.BALANCED -> 120L
        DetectionQuality.HIGH -> 180L
    }

    fun selectAcceleratorBackend(backend: ke.co.kopilot.feature.vision.LiteRtAcceleratorBackend) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            visionPipeline.selectAcceleratorBackend(backend)
        }
    }

    fun runAcceleratorBenchmark() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            visionPipeline.runAcceleratorBenchmark()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveRoadNameForLocation(lat: Double, lng: Double): String? {
        return runCatching {
            if (!android.location.Geocoder.isPresent()) return null
            val geocoder = android.location.Geocoder(appContext, java.util.Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            val addr = addresses?.firstOrNull() ?: return null
            val road = addr.thoroughfare?.takeIf { it.isNotBlank() && !it.equals("Unnamed Road", ignoreCase = true) }
                ?: addr.featureName?.takeIf { it.isNotBlank() && it.any { ch -> ch.isLetter() } && !it.equals("Unnamed Road", ignoreCase = true) }
                ?: addr.subLocality?.takeIf { it.isNotBlank() }
                ?: addr.locality?.takeIf { it.isNotBlank() }
            road?.trim()
        }.getOrNull()
    }

    val kenyanCityPresets get() = navigationRepo.kenyanCities

    private data class CoreBundle(
        val driving: Boolean,
        val startedAt: Long?,
        val online: Boolean,
        val settings: KoPilotSettings,
        val camera: CameraStatusState,
    )

    private data class SensorBundle(
        val vision: VisionPipelineState,
        val location: LocationTrackingState,
        val navigation: NavigationUiState,
        val weather: WeatherState,
        val voice: VoiceAssistantState,
    )
}
