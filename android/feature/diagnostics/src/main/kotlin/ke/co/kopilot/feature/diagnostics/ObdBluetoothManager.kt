package ke.co.kopilot.feature.diagnostics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import ke.co.kopilot.core.common.model.VehicleDiagnosticsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PairedBluetoothDevice(
    val name: String,
    val address: String,
)

@Singleton
class ObdBluetoothManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _state = MutableStateFlow(VehicleDiagnosticsState())
    val state: StateFlow<VehicleDiagnosticsState> = _state.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var pollingJob: Job? = null

    @SuppressLint("MissingPermission")
    fun listPairedDevices(bluetoothPermissionGranted: Boolean): List<PairedBluetoothDevice> {
        if (!bluetoothPermissionGranted || adapter == null || !adapter.isEnabled) {
            return emptyList()
        }
        return adapter.bondedDevices.orEmpty().map { dev ->
            PairedBluetoothDevice(
                name = dev.name ?: "Bluetooth Device",
                address = dev.address,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun connectAndMonitor(address: String, bluetoothPermissionGranted: Boolean) {
        if (!bluetoothPermissionGranted || adapter == null || !adapter.isEnabled) {
            _state.value = _state.value.copy(
                obdConnected = false,
                statusMessage = "Bluetooth unavailable or permission missing",
            )
            return
        }

        disconnect()
        pollingJob = scope.launch {
            runCatching {
                val device: BluetoothDevice = adapter.getRemoteDevice(address)
                val sppSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter.cancelDiscovery()
                sppSocket.connect()
                socket = sppSocket

                val out = sppSocket.outputStream
                val reader = BufferedReader(InputStreamReader(sppSocket.inputStream))

                sendCommand(out, reader, "ATZ")
                sendCommand(out, reader, "ATE0")
                val protocol = sendCommand(out, reader, "ATSP0")
                val supportedPids = sendCommand(out, reader, "0100")

                _state.value = _state.value.copy(
                    obdConnected = true,
                    adapterName = device.name ?: address,
                    protocolName = if (protocol.contains("OK")) "Auto (OBD-II)" else protocol.take(24),
                    statusMessage = if (supportedPids.contains("41 00")) "Connected" else "Connected (limited PIDs)",
                )

                while (isActive && sppSocket.isConnected) {
                    val rpm = ObdPidParser.parseRpm(sendCommand(out, reader, "010C"))
                    val coolant = ObdPidParser.parseCoolantTempC(sendCommand(out, reader, "0105"))
                    val speed = ObdPidParser.parseVehicleSpeedKmh(sendCommand(out, reader, "010D"))
                    val dtcs = ObdPidParser.parseDtcCodes(sendCommand(out, reader, "03"))

                    _state.value = _state.value.copy(
                        engineRpm = rpm,
                        coolantTempC = coolant,
                        coolantTimestampMillis = if (coolant != null) System.currentTimeMillis() else _state.value.coolantTimestampMillis,
                        obdSpeedKmh = speed,
                        dtcCodes = dtcs,
                    )
                    delay(1500L)
                }
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    obdConnected = false,
                    statusMessage = "OBD-II disconnected: ${err.localizedMessage ?: "connection lost"}",
                )
                disconnect()
            }
        }
    }

    fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        runCatching { socket?.close() }
        socket = null
        _state.value = _state.value.copy(
            obdConnected = false,
            statusMessage = "OBD-II adapter not connected",
        )
    }

    private fun sendCommand(out: OutputStream, reader: BufferedReader, cmd: String): String {
        out.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        out.flush()
        val sb = StringBuilder()
        var ch = reader.read()
        while (ch != -1 && ch.toChar() != '>') {
            sb.append(ch.toChar())
            ch = reader.read()
        }
        return sb.toString().trim()
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
