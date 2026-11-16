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
import ke.co.kopilot.feature.sensors.SensorManagerRepository
import ke.co.kopilot.feature.sensors.SensorSamplingProfile
import ke.co.kopilot.feature.navigation.NavigationRepository
import ke.co.kopilot.feature.navigation.NavigationUiState
import ke.co.kopilot.feature.traffic.TrafficAdvisor
import ke.co.kopilot.feature.traffic.TrafficEvaluation
import ke.co.kopilot.feature.trips.TripRepository
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
    private val obdManager: ObdBluetoothManager,
    private val tripRepo: TripRepository,
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
                if (vision.redBrakeLightsAhead && moving) {
                    alertCoordinator.submit(
                        alert = AlertMessage(
                            id = UUID.randomUUID().toString(),
                            priority = AlertPriority.ROAD_OBSERVATION,
                            spokenText = "Red brake lights ahead. Slow down or prepare to brake.",
                            displayText = "Red brake lights ahead — Slow down",
                            dedupKey = "front_brake_lights",
                            timestampMillis = System.currentTimeMillis(),
                        ),
                        speechRate = s.voiceSpeed,
                        language = s.language,
                        globalMute = s.alertsMuted,
                    )
                }
                when (vision.trafficLightSignal) {
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
                if (vision.isDarkScene && moving) {
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
                }
                weatherRepo.refreshWeather(fix.latitude, fix.longitude)
                navigationRepo.refreshRoadIntelligence(fix.latitude, fix.longitude)
                val roadIntel = navigationRepo.state.value.roadIntelligence
                val camDist = roadIntel.nearestSpeedCameraMeters
                if (camDist != null && camDist <= 500) {
                    val s = uiState.value.settings
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
                    speedEval.applicableSpeedLimitKmh != null
                ) {
                    val s = uiState.value.settings
                    val limit = speedEval.applicableSpeedLimitKmh!!
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
        if (locationGranted) {
            locationTracker.startTracking(highAccuracy = true)
            sensorManagerRepo.startMonitoring(SensorSamplingProfile.DRIVING)
        }
    }

    fun acceptSafetyDisclosure() = viewModelScope.launch {
        prefsRepo.setSafetyDisclosureAccepted(true)
    }

    fun startDriving() {
        isDriving.value = true
        tripStartedAt.value = System.currentTimeMillis()
        maxSpeedObservedKmh = 0f
        speedSampleSum = 0f
        speedSampleCount = 0
        signsDetectedInTrip = 0
        locationTracker.resetTripDistance()
        locationTracker.startTracking(highAccuracy = true)
        sensorManagerRepo.startMonitoring(SensorSamplingProfile.DRIVING)
    }

    fun endTrip() {
        val started = tripStartedAt.value
        val ended = System.currentTimeMillis()
        val distanceKm = locationTracker.state.value.distanceTraveledMeters / 1000.0
        val avgKmh = if (speedSampleCount > 0) speedSampleSum / speedSampleCount else 0f
        val destLabel = navigationRepo.state.value.destinationName
        val alertsCount = alertCoordinator.state.value.alertsTriggeredCount

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
        val settings = uiState.value.settings
        if (!settings.announceAllSigns && observation.category.speedLimitKmh == null) {
            return
        }
        val label = observation.customLabel ?: observation.category.displayName
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
            voiceClient.disconnect()
        } else {
            val s = uiState.value.settings
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
        DetectionQuality.FAST -> 150L
        DetectionQuality.BALANCED -> 250L
        DetectionQuality.HIGH -> 400L
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
