package ke.co.kopilot.feature.sensors

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.co.kopilot.feature.location.LocationTracker
import ke.co.kopilot.feature.location.LocationTrackingState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SensorsViewModel @Inject constructor(
    private val sensorRepo: SensorManagerRepository,
    locationTracker: LocationTracker,
) : ViewModel() {

    val hubState: StateFlow<SensorHubState> = sensorRepo.state
    val locationState: StateFlow<LocationTrackingState> = locationTracker.state

    fun startMonitoring(profile: SensorSamplingProfile = hubState.value.samplingProfile) {
        sensorRepo.startMonitoring(profile)
    }

    fun stopMonitoring() {
        sensorRepo.stopMonitoring()
    }

    fun setSamplingProfile(profile: SensorSamplingProfile) {
        sensorRepo.setSamplingProfile(profile)
    }

    fun refreshCapabilities() {
        sensorRepo.discoverAllCapabilities()
    }
}
