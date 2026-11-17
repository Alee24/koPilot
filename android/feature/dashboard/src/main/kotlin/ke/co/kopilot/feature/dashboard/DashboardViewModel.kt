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
import ke.co.kopilot.core.database.preferences.PreferencesRepository
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
import ke.co.kopilot.feature.sensors.DrivingMotionAnalysis
import ke.co.kopilot.feature.sensors.LateralMotionState
import ke.co.kopilot.feature.sensors.SensorManagerRepository
import ke.co.kopilot.feature.sensors.SensorSamplingProfile
import ke.co.kopilot.feature.navigation.NavigationRepository
import ke.co.kopilot.feature.navigation.NavigationUiState
import ke.co.kopilot.feature.traffic.TrafficAdvisor
import ke.co.kopilot.feature.traffic.TrafficEvaluation
import ke.co.kopilot.feature.trips.TripActivityLogger
import ke.co.kopilot.feature.trips.TripRepository
import ke.co.kopilot.feature.vision.LaneShiftEvent
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
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
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

    val uiState: StateFlow<DashboardUiState> = combine(
        coreFlows,
        sensorFlows,
        alertCoordinator.state,
        obdManager.state,
        tripRepo.trips,
    ) { core, sensor, alerts, obd, trips ->
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
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(),
    )

    init {
        visionPipeline.initializeIfNeeded()
        viewModelScope.launch {
            visionPipeline.state.collect { vision ->
                val s = uiState.value.settings
                val moving = (locationTracker.state.value.currentFix?.speedKmh ?: 0f) >= 8f || isDriving.value
                val now = System.currentTimeMillis()
                val plate = vision.lastDetectedPlate
                if (isDriving.value && plate != null && plate != lastLoggedPlate) {
                    lastLoggedPlate = plate
                    tripActivityLogger.logActivity("PLATE_DETECTED", "Vehicle plate spotted: $plate", now)
                }
                if (vision.redBrakeLightsAhead && moving && s.alertTrafficLightsEnabled) {
                    if (isDriving.value && (now - lastLoggedBrakeLightMs) >= 8_000L) {
                        lastLoggedBrakeLightMs = now
                        tripActivityLogger.logActivity("BRAKE_LIGHTS_AHEAD", "Red brake lights ahead — Slow down", now)
                    }
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = "Car in front is braking. Brake now.",
                            displayText = "Car braking ahead — Brake!",
                            dedupKey = "front_brake_lights",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }
                if (isDriving.value && s.sensorCameraEnabled) {
                    val currentSpeed = locationTracker.state.value.currentFix?.speedKmh ?: 0f
                    val roadObjects = vision.trackedObjects.filter { it.label != "Road Object" }
                    val closeVehicleAhead = roadObjects.any {
                        val cx = (it.normalizedLeft + it.normalizedRight) / 2f
                        cx in 0.25f..0.75f && it.normalizedBottom >= 0.56f
                    }
                    if (roadObjects.isNotEmpty() || vision.redBrakeLightsAhead) {
                        clearRoadSinceMs = 0L
                    } else if (clearRoadSinceMs == 0L) {
                        clearRoadSinceMs = now
                    }

                    when {
                        roadObjects.size >= 3 && currentSpeed >= 10f && (now - lastTutorBusyRoadMs) >= 18_000L -> {
                            lastTutorBusyRoadMs = now
                            tripActivityLogger.logActivity("ROAD_TUTOR", "Busy road (${roadObjects.size} objects) — stay ready on brake", now)
                            alertCoordinator.submit(
                                alert = AlertMessage(
                                    id = UUID.randomUUID().toString(),
                                    priority = AlertPriority.ROAD_OBSERVATION,
                                    spokenText = "Road is busy. Concentrate and keep your foot on the brake.",
                                    displayText = "Busy road — Keep foot on brake",
                                    dedupKey = "tutor_busy_road",
                                    timestampMillis = now,
                                ),
                                speechRate = s.voiceSpeed,
                                language = s.language,
                                globalMute = s.alertsMuted,
                            )
                        }
                        closeVehicleAhead && !vision.redBrakeLightsAhead && (now - lastTutorVehicleAheadMs) >= 10_000L -> {
                            lastTutorVehicleAheadMs = now
                            tripActivityLogger.logActivity("ROAD_TUTOR", "Vehicle ahead in lane — prepare to brake", now)
                            alertCoordinator.submit(
                                alert = AlertMessage(
                                    id = UUID.randomUUID().toString(),
                                    priority = AlertPriority.ROAD_OBSERVATION,
                                    spokenText = "Car in front of you. Start braking.",
                                    displayText = "Vehicle ahead — Start braking",
                                    dedupKey = "tutor_vehicle_ahead",
                                    timestampMillis = now,
                                ),
                                speechRate = s.voiceSpeed,
                                language = s.language,
                                globalMute = s.alertsMuted,
                            )
                        }
                        roadObjects.isEmpty() && !vision.redBrakeLightsAhead &&
                            clearRoadSinceMs > 0L && (now - clearRoadSinceMs) >= 4_000L &&
                            currentSpeed in 40f..80f &&
                            (vision.lastSpeedLimitKmh == null || currentSpeed < (vision.lastSpeedLimitKmh!! - 5)) &&
                            (now - lastTutorClearRoadMs) >= 25_000L -> {
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
                    TrafficLightSignal.GREEN -> alertCoordinator.submit(
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
                    TrafficLightSignal.NONE -> Unit
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
                if (!isDriving.value) return@collect
                val lat = sensorState.motionAnalysis.lateralState
                if (lat == LateralMotionState.STRAIGHT) {
                    lastAnnouncedTurnState = LateralMotionState.STRAIGHT
                    return@collect
                }
                val s = uiState.value.settings
                if (!s.alertLaneTurnEnabled) return@collect
                val now = System.currentTimeMillis()
                if (lat == lastAnnouncedTurnState || (now - lastTurnAlertMs) < 1_500L) return@collect
                lastAnnouncedTurnState = lat
                lastTurnAlertMs = now
                val (tag, msg) = when (lat) {
                    LateralMotionState.LANE_SHIFT_LEFT -> "LANE_CHANGE" to "Drifting left"
                    LateralMotionState.LANE_SHIFT_RIGHT -> "LANE_CHANGE" to "Drifting right"
                    LateralMotionState.LEFT_TURN -> "TURNING_LEFT" to "Turning left"
                    LateralMotionState.RIGHT_TURN -> "TURNING_RIGHT" to "Turning right"
                    LateralMotionState.SHARP_CORNERING -> "SHARP_CORNERING" to if (sensorState.motionAnalysis.yawRateRadPerSec >= 0f) "Turning left" else "Turning right"
                    LateralMotionState.STRAIGHT -> return@collect
                }
                tripActivityLogger.logActivity(tag, msg, now)
                alertCoordinator.submit(
                    alert = AlertMessage(
                        id = UUID.randomUUID().toString(),
                        priority = AlertPriority.ROAD_OBSERVATION,
                        spokenText = msg,
                        displayText = msg,
                        dedupKey = "sensor_lat_${msg.replace(' ', '_')}",
                        timestampMillis = now,
                    ),
                    speechRate = s.voiceSpeed,
                    language = s.language,
                    globalMute = s.alertsMuted,
                )
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

                val isMoving = (fix.speedKmh ?: 0f) >= 5f
                val zoneEval = speedZoneMonitor.evaluate(
                    fix = fix,
                    signalState = speedEval.signalState,
                    isDrivingActive = isDriving.value || isMoving,
                )
                currentSpeedZone.value = zoneEval.zone
                val spoken = zoneEval.zone.spokenAlert
                if (zoneEval.shouldNotify && spoken != null) {
                    if (isDriving.value) {
                        tripActivityLogger.logActivity("SPEED_ZONE", "${zoneEval.zone.name}: $spoken")
                    }
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

    fun updatePermissions(cameraGranted: Boolean, locationGranted: Boolean, micGranted: Boolean) {
        cameraController.setPermissionGranted(cameraGranted)
        locationTracker.setPermissionGranted(locationGranted)
        voiceClient.setMicPermissionGranted(micGranted)
        // Sensors remain idle until the user explicitly taps Start Driving.
    }

    fun acceptSafetyDisclosure() = viewModelScope.launch {
        prefsRepo.setSafetyDisclosureAccepted(true)
    }

    fun startDriving() {
        val started = System.currentTimeMillis()
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
        if (isDriving.value) {
            tripActivityLogger.logActivity(
                type = "SENSORS_ACTIVE",
                details = "Camera=${s.sensorCameraEnabled}, GPS=${s.sensorGpsEnabled}, Motion=${s.sensorMotionEnabled}, Mic=${s.sensorMicEnabled}, OBD=${s.sensorObdEnabled}",
                timestampMillis = started,
            )
        }
    }

    fun endTrip() {
        val started = tripStartedAt.value
        val ended = System.currentTimeMillis()
        val distanceKm = locationTracker.state.value.distanceTraveledMeters / 1000.0
        val avgKmh = if (speedSampleCount > 0) speedSampleSum / speedSampleCount else 0f
        val destLabel = navigationRepo.state.value.destinationName
        val alertsCount = alertCoordinator.state.value.alertsTriggeredCount

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
        locationTracker.stopTracking()
        sensorManagerRepo.stopMonitoring()
        voiceClient.disconnect()
        speedAlertEngine.reset()
        speedZoneMonitor.reset()
        currentSpeedZone.value = SpeedZone.NORMAL

        if (started != null && uiState.value.settings.tripHistoryEnabled) {
            viewModelScope.launch {
                tripRepo.saveCompletedTrip(
                    originLabel = "Current Location",
                    destinationLabel = destLabel,
                    startedAtMillis = started,
                    endedAtMillis = ended,
                    distanceKm = distanceKm,
                    maxSpeedKmh = maxSpeedObservedKmh,
                    avgSpeedKmh = avgKmh,
                    signsDetectedCount = signsDetectedInTrip,
                    alertsTriggeredCount = alertsCount,
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
        if (!settings.announceAllSigns && observation.category.speedLimitKmh == null) {
            return
        }
        val text = if (observation.isUncertain) {
            "Road sign ahead is unsure. Possible $label. Adjusting camera."
        } else {
            visionPipeline.signCatalog.getSpokenAdvice(observation.category, observation.customLabel)
        }
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
            (voiceState.connectionState == ke.co.kopilot.feature.voice.VoiceConnectionState.LOCAL_FALLBACK && voiceState.isListening)
        if (isActive) {
            if (isDriving.value) tripActivityLogger.logActivity("VOICE_ASSISTANT", "Voice assistant disconnected")
            voiceClient.disconnect()
        } else {
            val s = uiState.value.settings
            if (!s.sensorMicEnabled) return
            if (isDriving.value) tripActivityLogger.logActivity("VOICE_ASSISTANT", "Voice assistant connected (${s.language}, voice=${s.aiVoiceName})")
            voiceClient.connect(language = s.language, voice = s.aiVoiceName)
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
