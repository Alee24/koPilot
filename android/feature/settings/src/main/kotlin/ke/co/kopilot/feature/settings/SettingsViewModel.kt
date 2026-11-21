package ke.co.kopilot.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.co.kopilot.core.common.model.VehicleProfile
import ke.co.kopilot.core.database.dao.CachedWeatherDao
import ke.co.kopilot.core.database.dao.RoadObservationDao
import ke.co.kopilot.core.database.preferences.DetectionQuality
import ke.co.kopilot.core.database.preferences.KoPilotSettings
import ke.co.kopilot.core.database.preferences.MicMode
import ke.co.kopilot.core.database.preferences.PreferencesRepository
import ke.co.kopilot.core.database.preferences.ResponseLength
import ke.co.kopilot.core.database.preferences.VoiceMode
import ke.co.kopilot.core.network.api.KoPilotApiService
import ke.co.kopilot.core.security.SecureTokenStore
import ke.co.kopilot.feature.alerts.AndroidTtsSpeechSink
import ke.co.kopilot.feature.alerts.SpeechSink
import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.diagnostics.PairedBluetoothDevice
import ke.co.kopilot.feature.trips.TripRepository
import ke.co.kopilot.feature.vehicle.VehicleProfileRepository
import ke.co.kopilot.feature.vision.LiteRtSignClassifier
import ke.co.kopilot.feature.vision.LiteRtYoloSignDetector
import ke.co.kopilot.feature.vision.LocalRoadSignCatalog
import ke.co.kopilot.feature.vision.LocalRoadSignEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepo: PreferencesRepository,
    private val vehicleRepo: VehicleProfileRepository,
    private val obdManager: ObdBluetoothManager,
    private val tripRepo: TripRepository,
    private val weatherDao: CachedWeatherDao,
    private val observationDao: RoadObservationDao,
    private val apiService: KoPilotApiService,
    private val tokenStore: SecureTokenStore,
    private val okHttpClient: OkHttpClient,
    private val speechSink: SpeechSink,
    private val signCatalog: LocalRoadSignCatalog,
    private val liteRtSignClassifier: LiteRtSignClassifier,
    private val liteRtYoloSignDetector: LiteRtYoloSignDetector,
) : ViewModel() {
    val roadSignDatabase: List<LocalRoadSignEntry> get() = signCatalog.allEntries()
    val isCustomSignModelLoaded: Boolean get() = liteRtYoloSignDetector.isAvailable || liteRtSignClassifier.isAvailable

    private val _backendUrl = MutableStateFlow(tokenStore.getBackendBaseUrl())
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    private val _openAiKey = MutableStateFlow(tokenStore.getOpenAiApiKey().orEmpty())
    val openAiKey: StateFlow<String> = _openAiKey.asStateFlow()

    private val _googleMapsKey = MutableStateFlow(tokenStore.getGoogleMapsApiKey().orEmpty())
    val googleMapsKey: StateFlow<String> = _googleMapsKey.asStateFlow()

    val settings: StateFlow<KoPilotSettings> = prefsRepo.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = KoPilotSettings(),
    )

    val vehicleProfile: StateFlow<VehicleProfile> = vehicleRepo.activeProfile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VehicleProfile(),
    )

    private val _pairedDevices = MutableStateFlow<List<PairedBluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedBluetoothDevice>> = _pairedDevices.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    fun setLanguage(lang: String) = viewModelScope.launch { prefsRepo.setLanguage(lang) }
    fun setDarkTheme(dark: Boolean) = viewModelScope.launch { prefsRepo.setDarkTheme(dark) }
    fun setDistanceUnit(unit: String) = viewModelScope.launch { prefsRepo.setDistanceUnit(unit) }
    fun setVoiceSpeed(speed: Float) = viewModelScope.launch { prefsRepo.setVoiceSpeed(speed) }
    fun setSpeedThreshold(kmh: Int) = viewModelScope.launch { prefsRepo.setSpeedAlertThresholdKmh(kmh) }
    fun setDetectionQuality(q: DetectionQuality) = viewModelScope.launch { prefsRepo.setDetectionQuality(q) }
    fun setCameraZoomRatio(zoom: Float) = viewModelScope.launch { prefsRepo.setCameraZoomRatio(zoom) }
    fun setHazardAdvisories(enabled: Boolean) = viewModelScope.launch { prefsRepo.setHazardAdvisoriesEnabled(enabled) }
    fun setAnnounceAllSigns(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAnnounceAllSigns(enabled) }
    fun setAvoidTolls(avoid: Boolean) = viewModelScope.launch { prefsRepo.setAvoidTolls(avoid) }
    fun setTrafficAlerts(enabled: Boolean) = viewModelScope.launch { prefsRepo.setTrafficAlertsEnabled(enabled) }
    fun setNavVoice(enabled: Boolean) = viewModelScope.launch { prefsRepo.setNavVoiceGuidanceEnabled(enabled) }
    fun setVoiceMode(mode: VoiceMode) = viewModelScope.launch { prefsRepo.setVoiceMode(mode) }
    fun setAiVoiceName(voice: String) {
        if (speechSink is AndroidTtsSpeechSink) {
            speechSink.setVoiceProfile(voice)
        }
        viewModelScope.launch { prefsRepo.setAiVoiceName(voice) }
    }
    fun availableVoices(): List<String> =
        if (speechSink is AndroidTtsSpeechSink) speechSink.availableSystemVoices() else listOf("Default", "Feminine", "Masculine", "Futuristic")
    fun setAlertChimeEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertChimeEnabled(enabled) }
    fun setVoiceVolume(vol: Float) = viewModelScope.launch { prefsRepo.setVoiceVolume(vol) }
    fun setMicMode(mode: MicMode) = viewModelScope.launch { prefsRepo.setMicMode(mode) }
    fun setAlertRoadSignsEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertRoadSignsEnabled(enabled) }
    fun setAlertLaneTurnEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertLaneTurnEnabled(enabled) }
    fun setAlertTrafficLightsEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertTrafficLightsEnabled(enabled) }
    fun setAlertSpeedLimitEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertSpeedLimitEnabled(enabled) }
    fun setAlertWeatherEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertWeatherEnabled(enabled) }
    fun setAlertNumberPlatesEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertNumberPlatesEnabled(enabled) }
    fun setAlertRoadQualityEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertRoadQualityEnabled(enabled) }
    fun setAlertSmartwatchEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertSmartwatchEnabled(enabled) }
    fun setAlertTerrainEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertTerrainEnabled(enabled) }
    fun setAlertEcuEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAlertEcuEnabled(enabled) }
    fun addWatchedPlate(plate: String) = viewModelScope.launch { prefsRepo.addWatchedPlate(plate) }
    fun removeWatchedPlate(plate: String) = viewModelScope.launch { prefsRepo.removeWatchedPlate(plate) }
    fun setSensorCameraEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setSensorCameraEnabled(enabled) }
    fun setSensorGpsEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setSensorGpsEnabled(enabled) }
    fun setSensorMotionEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setSensorMotionEnabled(enabled) }
    fun setSensorMicEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setSensorMicEnabled(enabled) }
    fun setSensorObdEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setSensorObdEnabled(enabled) }
    fun setAllSensorsEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setAllSensorsEnabled(enabled) }

    fun testVoiceSound() {
        val s = settings.value
        if (speechSink is AndroidTtsSpeechSink) {
            speechSink.setVoiceProfile(s.aiVoiceName)
            if (s.alertChimeEnabled) {
                speechSink.playChime()
            }
        }
        speechSink.speak(
            text = "KoPilot voice and alert sound check. Bump ahead, please slow down and drive carefully.",
            flushQueue = true,
            speechRate = s.voiceSpeed,
            languageTag = s.language,
        )
    }
    fun setResponseLength(len: ResponseLength) = viewModelScope.launch { prefsRepo.setResponseLength(len) }
    fun setTripHistory(enabled: Boolean) = viewModelScope.launch { prefsRepo.setTripHistoryEnabled(enabled) }
    fun setTelemetryOptIn(optIn: Boolean) = viewModelScope.launch { prefsRepo.setTelemetryOptIn(optIn) }
    fun setEmergencyContact(name: String, phone: String) = viewModelScope.launch {
        prefsRepo.setEmergencyContact(name, phone)
        _statusBanner.value = "Emergency contact updated"
    }

    fun saveApiConfiguration(backendUrl: String, openAiKey: String, googleMapsKey: String) {
        tokenStore.saveBackendBaseUrl(backendUrl)
        tokenStore.saveOpenAiApiKey(openAiKey)
        tokenStore.saveGoogleMapsApiKey(googleMapsKey)
        _backendUrl.value = tokenStore.getBackendBaseUrl()
        _openAiKey.value = tokenStore.getOpenAiApiKey().orEmpty()
        _googleMapsKey.value = tokenStore.getGoogleMapsApiKey().orEmpty()
        _statusBanner.value = "API credentials saved securely in EncryptedSharedPreferences"
    }

    fun clearApiCredentials() {
        tokenStore.clearCredentials()
        _backendUrl.value = tokenStore.getBackendBaseUrl()
        _openAiKey.value = ""
        _googleMapsKey.value = ""
        _statusBanner.value = "API credentials cleared"
    }

    fun testBackendConnection(urlInput: String) = viewModelScope.launch {
        val base = urlInput.trim().let { if (it.endsWith("/")) it else "$it/" }
        _statusBanner.value = "Testing ${base}health..."
        val resultMsg = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("${base}health").build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        "Backend Connected (HTTP ${resp.code}): ${resp.body?.string().orEmpty()}"
                    } else {
                        "Backend returned HTTP ${resp.code}"
                    }
                }
            }.getOrElse { err ->
                "Connection failed: ${err.localizedMessage ?: "unreachable"}"
            }
        }
        _statusBanner.value = resultMsg
    }

    fun testEnteredApiKeys(openAiKeyInput: String, googleKeyInput: String) = viewModelScope.launch {
        saveApiConfiguration(_backendUrl.value, openAiKeyInput, googleKeyInput)
        _statusBanner.value = "Testing OpenAI and Google API keys..."
        val openAi = openAiKeyInput.trim()
        val google = googleKeyInput.trim()
        val msg = withContext(Dispatchers.IO) {
            val openAiStatus = if (openAi.isBlank()) {
                "OpenAI: not entered"
            } else {
                runCatching {
                    val body = """{"expires_after":{"anchor":"created_at","seconds":60},"session":{"type":"realtime","model":"gpt-realtime"}}"""
                    val req = Request.Builder()
                        .url("https://api.openai.com/v1/realtime/client_secrets")
                        .addHeader("Authorization", "Bearer $openAi")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    okHttpClient.newCall(req).execute().use { r ->
                        if (r.isSuccessful) {
                            "OpenAI: OK"
                        } else {
                            val errBody = r.body?.string().orEmpty()
                            when (r.code) {
                                429 -> "OpenAI: Quota/Rate Limit (429)"
                                401 -> "OpenAI: Invalid Key (401)"
                                else -> "OpenAI: HTTP ${r.code} (${errBody.take(60)})"
                            }
                        }
                    }
                }.getOrElse { "OpenAI: ${it.localizedMessage ?: "error"}" }
            }

            val googleStatus = if (google.isBlank()) {
                "Google: not entered"
            } else {
                runCatching {
                    val body = """{"textQuery":"Nairobi","regionCode":"KE","maxResultCount":1}"""
                    val req = Request.Builder()
                        .url("https://places.googleapis.com/v1/places:searchText")
                        .addHeader("X-Goog-Api-Key", google)
                        .addHeader("X-Goog-FieldMask", "places.id")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    okHttpClient.newCall(req).execute().use { r ->
                        if (r.isSuccessful) "Google Places/Routes: OK" else "Google: HTTP ${r.code}"
                    }
                }.getOrElse { "Google: ${it.localizedMessage ?: "error"}" }
            }
            "$openAiStatus • $googleStatus"
        }
        _statusBanner.value = msg
    }

    fun saveVehicleProfile(profile: VehicleProfile) = viewModelScope.launch {
        vehicleRepo.saveProfile(profile)
        _statusBanner.value = "Vehicle profile saved"
    }

    val obdConnectionState = obdManager.connectionState
    val discoveredObdDevices = obdManager.discoveredDevices
    val liveObdTelemetry = obdManager.liveTelemetry
    val obdCapabilities = obdManager.capabilities
    val obdStatusMessage = obdManager.connectionManager.statusMessage

    fun startObdDiscovery(bluetoothPermissionGranted: Boolean) {
        obdManager.startScan(bluetoothPermissionGranted)
        _pairedDevices.value = obdManager.listPairedDevices(bluetoothPermissionGranted)
    }

    fun stopObdDiscovery() {
        obdManager.stopScan()
    }

    fun disconnectObd() {
        obdManager.disconnect()
    }

    fun refreshPairedBluetoothDevices(bluetoothPermissionGranted: Boolean) {
        _pairedDevices.value = obdManager.listPairedDevices(bluetoothPermissionGranted)
    }

    fun connectObd(address: String, bluetoothPermissionGranted: Boolean, deviceName: String = "") {
        obdManager.connectAndMonitor(address, bluetoothPermissionGranted)
        viewModelScope.launch {
            val resolvedName = deviceName.ifBlank {
                _pairedDevices.value.firstOrNull { it.address == address }?.name
                    ?: discoveredObdDevices.value.firstOrNull { it.address == address }?.name
                    ?: settings.value.lastObdDeviceName.ifBlank { "OBD-II Adapter" }
            }
            prefsRepo.setLastObdDevice(address, resolvedName)
            val current = vehicleProfile.value
            vehicleRepo.saveProfile(current.copy(obdAdapterAddress = address))
            _statusBanner.value = "Connecting to $resolvedName ($address) — auto-connect enabled on app start"
        }
    }

    fun quickConnectObd(bluetoothPermissionGranted: Boolean): Boolean {
        val paired = obdManager.listPairedDevices(bluetoothPermissionGranted)
        _pairedDevices.value = paired
        val s = settings.value
        val savedAddr = s.lastObdDeviceAddress.ifBlank { vehicleProfile.value.obdAdapterAddress.orEmpty() }
        val targetDevice = when {
            savedAddr.isNotBlank() -> paired.firstOrNull { it.address == savedAddr }
                ?: PairedBluetoothDevice(s.lastObdDeviceName.ifBlank { "Saved OBD-II Adapter" }, savedAddr)
            else -> paired.firstOrNull { dev ->
                val n = dev.name.uppercase()
                n.contains("OBD") || n.contains("ELM") || n.contains("V-LINK") ||
                    n.contains("VLINK") || n.contains("VEEPEAK") || n.contains("KONNWEI") ||
                    n.contains("VGATE") || n.contains("CARISTA") || n.contains("CHX") ||
                    n.contains("CBT") || n.contains("SCAN")
            } ?: paired.singleOrNull()
        }
        return if (targetDevice != null && targetDevice.address.isNotBlank()) {
            connectObd(targetDevice.address, bluetoothPermissionGranted, targetDevice.name)
            true
        } else {
            startObdDiscovery(bluetoothPermissionGranted)
            false
        }
    }

    fun exportTripsJson(onExportReady: (String) -> Unit) = viewModelScope.launch {
        val trips = tripRepo.trips.firstOrNull().orEmpty()
        val json = trips.joinToString(prefix = "[", postfix = "]", separator = ",\n") { t ->
            """{"id":"${t.id}","origin":"${t.originLabel.orEmpty()}","destination":"${t.destinationLabel.orEmpty()}","distanceKm":${t.distanceKm},"durationSeconds":${t.durationSeconds}}"""
        }
        onExportReady(json)
    }

    fun deleteAllData() = viewModelScope.launch {
        tripRepo.deleteAllLocalTrips()
        weatherDao.clearAll()
        observationDao.clearAll()
        runCatching { apiService.deleteAccountData() }
        tokenStore.clearAll()
        _statusBanner.value = "Local and cloud trip/telemetry data deleted"
    }
}
