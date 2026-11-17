package ke.co.kopilot.feature.diagnostics

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.co.kopilot.core.common.model.VehicleDiagnosticsState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ObdViewModel @Inject constructor(
    private val obdManager: ObdBluetoothManager,
) : ViewModel() {

    val connectionState: StateFlow<ObdConnectionState> = obdManager.connectionState
    val discoveredDevices: StateFlow<List<ObdDiscoveredDevice>> = obdManager.discoveredDevices
    val liveTelemetry: StateFlow<ObdLiveTelemetry> = obdManager.liveTelemetry
    val capabilities: StateFlow<List<ObdPidCapability>> = obdManager.capabilities
    val statusMessage: StateFlow<String> = obdManager.connectionManager.statusMessage
    val diagnosticsState: StateFlow<VehicleDiagnosticsState> = obdManager.state

    fun loadPairedDevices(bluetoothGranted: Boolean) {
        obdManager.listPairedDevices(bluetoothGranted)
    }

    fun startScan(bluetoothGranted: Boolean) {
        obdManager.startScan(bluetoothGranted)
    }

    fun stopScan() {
        obdManager.stopScan()
    }

    fun connect(address: String, bluetoothGranted: Boolean) {
        obdManager.connectAndMonitor(address, bluetoothGranted)
    }

    fun disconnect() {
        obdManager.disconnect()
    }
}
