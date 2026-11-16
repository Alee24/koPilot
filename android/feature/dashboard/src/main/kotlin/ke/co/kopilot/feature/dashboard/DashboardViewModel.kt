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
import ke.co.kopilot.feature.alerts.LocalAlertCoordinator
import ke.co.kopilot.feature.camera.CameraPerformanceController
import ke.co.kopilot.feature.camera.CameraStatusState
import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.location.LocationTracker
import ke.co.kopilot.feature.location.LocationTrackingState
import ke.co.kopilot.feature.location.SpeedAlertEngine
import ke.co.kopilot.feature.location.SpeedEvaluation
import ke.co.kopilot.feature.navigation.NavigationRepository
import ke.co.kopilot.feature.navigation.NavigationUiState
import ke.co.kopilot.feature.traffic.TrafficAdvisor
import ke.co.kopilot.feature.traffic.TrafficEvaluation
import ke.co.kopilot.feature.trips.TripRepository
import ke.co.kopilot.feature.vision.RoadVisionPipeline
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
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val prefsRepo: PreferencesRepository,
    val cameraController: CameraPerformanceController,
    val visionPipeline: RoadVisionPipeline,
    private val locationTracker: LocationTracker,
    private val speedAlertEngine: SpeedAlertEngine,
    private val navigationRepo: NavigationRepository,
    private val trafficAdvisor: TrafficAdvisor,
    private val weatherRepo: WeatherRepository,
    private val voiceClient: OpenAiRealtimeVoiceClient,
    private val alertCoordinator: LocalAlertCoordinator,
    private val obdManager: ObdBluetoothManager,
    private val tripRepo: TripRepository,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val isDriving = MutableStateFlow(false)
    private val tripStartedAt = MutableStateFlow<Long?>(null)
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
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState(),
    )

    init {
        visionPipeline.initializeIfNeeded()
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
            }
        }
    }

    fun updatePermissions(cameraGranted: Boolean, locationGranted: Boolean, micGranted: Boolean) {
        cameraController.setPermissionGranted(cameraGranted)
        locationTracker.setPermissionGranted(locationGranted)
        voiceClient.setMicPermissionGranted(micGranted)
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
        voiceClient.disconnect()
        speedAlertEngine.reset()

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
        val text = if (observation.applicableToDriverLane == false) {
            "${observation.category.displayName} observed nearby."
        } else {
            "${observation.category.displayName} observed."
        }
        alertCoordinator.submit(
            alert = AlertMessage(
                id = UUID.randomUUID().toString(),
                priority = AlertPriority.ROAD_OBSERVATION,
                spokenText = text,
                displayText = text,
                dedupKey = "sign_${observation.category.name}",
                timestampMillis = observation.timestampMillis,
            ),
            speechRate = settings.voiceSpeed,
            language = settings.language,
            globalMute = settings.alertsMuted,
        )
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
        navigationRepo.navigateTo(
            originLat = originLat,
            originLng = originLng,
            destName = name,
            destLat = lat,
            destLng = lng,
            avoidTolls = uiState.value.settings.avoidTolls,
        )
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
