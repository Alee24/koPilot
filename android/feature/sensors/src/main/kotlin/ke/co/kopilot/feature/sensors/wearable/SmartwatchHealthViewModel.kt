package ke.co.kopilot.feature.sensors.wearable

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.co.kopilot.core.database.dao.WearableDao
import ke.co.kopilot.core.database.entity.WearableConnectionEventEntity
import ke.co.kopilot.core.database.entity.WearableDeviceEntity
import ke.co.kopilot.core.database.entity.WearableSyncHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SmartwatchHealthViewModel @Inject constructor(
    private val connectionManager: SmartwatchConnectionManager,
    private val heartRateRepository: HeartRateRepository,
    private val sleepAnalyticsRepository: SleepAnalyticsRepository,
    private val wearableDao: WearableDao,
) : ViewModel() {

    val diagnostics: StateFlow<SmartwatchDiagnosticsState> = connectionManager.diagnostics
    val discoveredWatches: StateFlow<List<DiscoveredBleWatch>> = connectionManager.discoveredWatches
    val heartRateState: StateFlow<HeartRateTelemetryState> = heartRateRepository.state
    val sleepState: StateFlow<SleepAnalyticsUiState> = sleepAnalyticsRepository.state

    val savedDevices: StateFlow<List<WearableDeviceEntity>> = connectionManager.savedDevices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val connectionEvents: StateFlow<List<WearableConnectionEventEntity>> = connectionManager.connectionEvents.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val syncHistory: StateFlow<List<WearableSyncHistoryEntity>> = sleepAnalyticsRepository.syncHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val requiredHealthPermissions: Set<String> = sleepAnalyticsRepository.requiredHealthPermissions

    fun onScreenOpened(bluetoothPermissionGranted: Boolean) {
        sleepAnalyticsRepository.refreshAvailabilityAndCompanionApps()
        connectionManager.tryRestoreSavedWatch(bluetoothPermissionGranted)
    }

    fun startBleScan(bluetoothPermissionGranted: Boolean) {
        connectionManager.startBleScan(bluetoothPermissionGranted, durationMs = 12_000L)
    }

    fun stopBleScan(bluetoothPermissionGranted: Boolean) {
        connectionManager.stopBleScan(bluetoothPermissionGranted)
    }

    fun connectWatch(address: String, name: String, bluetoothPermissionGranted: Boolean) {
        connectionManager.connectToWatch(
            address = address,
            fallbackName = name,
            bluetoothPermissionGranted = bluetoothPermissionGranted,
            isAutoRestore = false,
        )
    }

    fun disconnectWatch() {
        connectionManager.disconnectWatch()
    }

    fun forgetWatch(address: String?) {
        connectionManager.forgetWatch(address)
    }

    fun syncHealthConnectNow() {
        viewModelScope.launch(Dispatchers.IO) {
            sleepAnalyticsRepository.checkPermissionsAndSync(autoTriggered = false)
        }
    }

    fun buildHealthConnectSettingsIntent(): Intent =
        sleepAnalyticsRepository.buildOpenHealthConnectSettingsIntent()

    fun deleteAllStoredHealthData() {
        viewModelScope.launch(Dispatchers.IO) {
            wearableDao.deleteAllHeartRates()
            wearableDao.deleteAllSleepStages()
            wearableDao.deleteAllSleepSessions()
            wearableDao.deleteAllSyncHistory()
            wearableDao.deleteAllConnectionEvents()
            heartRateRepository.clearAllInMemoryState()
            sleepAnalyticsRepository.refreshAvailabilityAndCompanionApps()
        }
    }
}
