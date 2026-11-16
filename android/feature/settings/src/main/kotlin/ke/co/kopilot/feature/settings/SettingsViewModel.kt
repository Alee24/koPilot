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
import ke.co.kopilot.core.network.api.KoPilotApiService
import ke.co.kopilot.core.security.SecureTokenStore
import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.diagnostics.PairedBluetoothDevice
import ke.co.kopilot.feature.trips.TripRepository
import ke.co.kopilot.feature.vehicle.VehicleProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
) : ViewModel() {

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
    fun setHazardAdvisories(enabled: Boolean) = viewModelScope.launch { prefsRepo.setHazardAdvisoriesEnabled(enabled) }
    fun setAvoidTolls(avoid: Boolean) = viewModelScope.launch { prefsRepo.setAvoidTolls(avoid) }
    fun setTrafficAlerts(enabled: Boolean) = viewModelScope.launch { prefsRepo.setTrafficAlertsEnabled(enabled) }
    fun setNavVoice(enabled: Boolean) = viewModelScope.launch { prefsRepo.setNavVoiceGuidanceEnabled(enabled) }
    fun setMicMode(mode: MicMode) = viewModelScope.launch { prefsRepo.setMicMode(mode) }
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

    fun saveVehicleProfile(profile: VehicleProfile) = viewModelScope.launch {
        vehicleRepo.saveProfile(profile)
        _statusBanner.value = "Vehicle profile saved"
    }

    fun refreshPairedBluetoothDevices(bluetoothPermissionGranted: Boolean) {
        _pairedDevices.value = obdManager.listPairedDevices(bluetoothPermissionGranted)
    }

    fun connectObd(address: String, bluetoothPermissionGranted: Boolean) {
        obdManager.connectAndMonitor(address, bluetoothPermissionGranted)
        viewModelScope.launch {
            val current = vehicleProfile.value
            vehicleRepo.saveProfile(current.copy(obdAdapterAddress = address))
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
