package ke.co.kopilot.feature.diagnostics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reusable OBD-II connection engine supporting Bluetooth Classic (RFCOMM SPP)
 * and Bluetooth Low Energy (GATT dynamic service/characteristic discovery) with a
 * serialized command queue, state machine, capability discovery, and auto-reconnect.
 */
@Singleton
class ObdConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val commandMutex = Mutex()

    private val _connectionState = MutableStateFlow(ObdConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ObdDiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ObdDiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _statusMessage = MutableStateFlow("OBD-II adapter not connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _activeTransport = MutableStateFlow(ObdTransportType.UNKNOWN)
    val activeTransport: StateFlow<ObdTransportType> = _activeTransport.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    fun appendLog(line: String) {
        _connectionLog.value = (_connectionLog.value + line).takeLast(20)
    }

    private var classicSocket: BluetoothSocket? = null
    private var bleGatt: BluetoothGatt? = null
    private var bleWriteChar: BluetoothGattCharacteristic? = null
    private var bleResponseBuffer = StringBuilder()
    private var blePendingDeferred: CompletableDeferred<String>? = null

    private var scanJob: Job? = null
    private var bleScanCallback: ScanCallback? = null
    private var classicReceiverRegistered = false
    private var lastConnectedAddress: String? = null
    private var manualDisconnectRequested = false

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (dev != null) {
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                            .takeIf { it != Short.MIN_VALUE }?.toInt()
                        upsertDiscoveredDevice(
                            device = dev,
                            rssi = rssi,
                            transportHint = if (dev.type == BluetoothDevice.DEVICE_TYPE_LE) {
                                ObdTransportType.BLUETOOTH_LOW_ENERGY_GATT
                            } else {
                                ObdTransportType.BLUETOOTH_CLASSIC_SPP
                            },
                        )
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_connectionState.value == ObdConnectionState.SCANNING) {
                        _connectionState.value = if (_discoveredDevices.value.isNotEmpty()) {
                            ObdConnectionState.DEVICE_FOUND
                        } else {
                            ObdConnectionState.DISCONNECTED
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun listPairedDevices(permissionGranted: Boolean): List<ObdDiscoveredDevice> {
        if (!permissionGranted || adapter == null || !adapter.isEnabled) return emptyList()
        val paired = adapter.bondedDevices.orEmpty().map { dev ->
            val name = dev.name ?: "Bluetooth Device"
            ObdDiscoveredDevice(
                name = name,
                address = dev.address,
                rssiDbm = null,
                isPaired = true,
                transportHint = if (dev.type == BluetoothDevice.DEVICE_TYPE_LE) {
                    ObdTransportType.BLUETOOTH_LOW_ENERGY_GATT
                } else {
                    ObdTransportType.BLUETOOTH_CLASSIC_SPP
                },
                isHhobdHint = isHhobdNameHint(name),
            )
        }
        val merged = (_discoveredDevices.value + paired).distinctBy { it.address }
        _discoveredDevices.value = merged
        return paired
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(permissionGranted: Boolean) {
        if (!permissionGranted || adapter == null || !adapter.isEnabled) {
            _connectionState.value = ObdConnectionState.ERROR
            _statusMessage.value = "Bluetooth unavailable or scan permission missing"
            return
        }
        stopDiscovery()
        listPairedDevices(permissionGranted)
        _connectionState.value = ObdConnectionState.SCANNING
        _statusMessage.value = "Scanning Bluetooth Classic & BLE for OBD-II adapters..."

        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(classicReceiver, filter)
            classicReceiverRegistered = true
            adapter?.startDiscovery()
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner != null) {
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val dev = result?.device ?: return
                    upsertDiscoveredDevice(
                        device = dev,
                        rssi = result.rssi,
                        transportHint = ObdTransportType.BLUETOOTH_LOW_ENERGY_GATT,
                    )
                }
            }
            bleScanCallback = cb
            runCatching { scanner.startScan(cb) }
        }

        scanJob = scope.launch {
            delay(10_000L)
            stopDiscovery()
            if (_connectionState.value == ObdConnectionState.SCANNING) {
                _connectionState.value = if (_discoveredDevices.value.isNotEmpty()) {
                    ObdConnectionState.DEVICE_FOUND
                } else {
                    ObdConnectionState.DISCONNECTED
                }
                _statusMessage.value = "Found ${_discoveredDevices.value.size} Bluetooth device(s)"
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        runCatching { adapter?.cancelDiscovery() }
        bleScanCallback?.let { cb ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        bleScanCallback = null
        if (classicReceiverRegistered) {
            runCatching { context.unregisterReceiver(classicReceiver) }
            classicReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, permissionGranted: Boolean): Result<ObdTransportType> {
        val btAdapter = adapter
        if (!permissionGranted) {
            val msg = "Bluetooth permission not granted — tap Grant Bluetooth Permissions"
            appendLog(msg)
            _connectionState.value = ObdConnectionState.ERROR
            _statusMessage.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        if (btAdapter == null || !btAdapter.isEnabled) {
            val msg = "Bluetooth is turned OFF — enable Bluetooth and try again"
            appendLog(msg)
            _connectionState.value = ObdConnectionState.ERROR
            _statusMessage.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        manualDisconnectRequested = false
        stopDiscovery()
        disconnectInternal(resetManualFlag = false)
        lastConnectedAddress = address

        val device = runCatching { btAdapter.getRemoteDevice(address) }.getOrElse {
            _connectionState.value = ObdConnectionState.ERROR
            _statusMessage.value = "Invalid Bluetooth address"
            return Result.failure(it)
        }

        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            _connectionState.value = ObdConnectionState.PAIRING
            _statusMessage.value = "Pairing with ${device.name ?: address}..."
            delay(2_000L)
        }

        _connectionState.value = ObdConnectionState.CONNECTING
        _statusMessage.value = "Connecting to ${device.name ?: address}..."

        // Try Classic SPP first unless device explicitly reports LE-only
        if (device.type != BluetoothDevice.DEVICE_TYPE_LE) {
            val sppResult = connectClassicSpp(device)
            if (sppResult.isSuccess) {
                _activeTransport.value = ObdTransportType.BLUETOOTH_CLASSIC_SPP
                return Result.success(ObdTransportType.BLUETOOTH_CLASSIC_SPP)
            }
        }

        // Fall back to BLE GATT dynamic service/characteristic discovery
        val bleResult = connectBleGatt(device)
        if (bleResult.isSuccess) {
            _activeTransport.value = ObdTransportType.BLUETOOTH_LOW_ENERGY_GATT
            return Result.success(ObdTransportType.BLUETOOTH_LOW_ENERGY_GATT)
        }

        val err = bleResult.exceptionOrNull() ?: IllegalStateException("Unsupported or unreachable OBD-II adapter")
        _connectionState.value = if (err is UnsupportedOperationException) {
            ObdConnectionState.UNSUPPORTED
        } else {
            ObdConnectionState.ERROR
        }
        _statusMessage.value = err.localizedMessage ?: "Failed to connect to adapter"
        return Result.failure(err)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectClassicSpp(device: BluetoothDevice): Result<Unit> {
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "Secure SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "Insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "RFCOMM Ch.1" to {
                @Suppress("UNCHECKED_CAST")
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
            "RFCOMM Ch.2" to {
                @Suppress("UNCHECKED_CAST")
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 2) as BluetoothSocket
            },
        )
        var lastError: Throwable? = null
        attempts.forEachIndexed { idx, (label, factory) ->
            var sock: BluetoothSocket? = null
            val ok = runCatching {
                runCatching { adapter?.cancelDiscovery() }
                delay(300L)
                sock = factory()
                val s = sock!!
                appendLog("Classic $label (${idx + 1}/${attempts.size})...")
                s.connect()
                if (!s.isConnected) {
                    runCatching { s.close() }
                    throw IllegalStateException("RFCOMM SPP socket not connected")
                }
                classicSocket = s
                appendLog("Classic $label connected!")
            }.onFailure { err ->
                lastError = err
                appendLog("$label failed: ${err.localizedMessage ?: err.javaClass.simpleName}")
                runCatching { sock?.close() }
            }.isSuccess
            if (ok) return Result.success(Unit)
        }
        return Result.failure(lastError ?: IllegalStateException("Classic SPP connection failed"))
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectBleGatt(device: BluetoothDevice): Result<Unit> {
        val readyDeferred = CompletableDeferred<Result<Unit>>()
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!readyDeferred.isCompleted) {
                        readyDeferred.complete(Result.failure(IllegalStateException("BLE connection failed (status=$status)")))
                    } else if (!manualDisconnectRequested && _connectionState.value == ObdConnectionState.CONNECTED) {
                        _connectionState.value = ObdConnectionState.RECONNECTING
                        _statusMessage.value = "BLE link dropped; reconnecting..."
                    }
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    readyDeferred.complete(Result.failure(UnsupportedOperationException("BLE service discovery failed")))
                    return
                }
                var writeChar: BluetoothGattCharacteristic? = null
                var notifyChar: BluetoothGattCharacteristic? = null
                for (service in gatt.services.orEmpty()) {
                    for (ch in service.characteristics.orEmpty()) {
                        val props = ch.properties
                        val writable = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        val notifiable = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        if (writable && writeChar == null) writeChar = ch
                        if (notifiable && notifyChar == null) notifyChar = ch
                    }
                }
                if (writeChar == null || notifyChar == null) {
                    readyDeferred.complete(
                        Result.failure(UnsupportedOperationException("Device exposes no serial BLE GATT command/notify characteristics")),
                    )
                    return
                }
                bleWriteChar = writeChar
                gatt.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                } else {
                    readyDeferred.complete(Result.success(Unit))
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (!readyDeferred.isCompleted) {
                    readyDeferred.complete(Result.success(Unit))
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleBleChunk(value.toString(Charsets.US_ASCII))
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                handleBleChunk(characteristic.value?.toString(Charsets.US_ASCII).orEmpty())
            }
        }

        bleGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        return withTimeoutOrNull(10_000L) { readyDeferred.await() }
            ?: Result.failure(IllegalStateException("BLE GATT service discovery timed out"))
    }

    private fun handleBleChunk(chunk: String) {
        bleResponseBuffer.append(chunk)
        if (chunk.contains('>')) {
            val full = bleResponseBuffer.toString().trim()
            bleResponseBuffer.clear()
            blePendingDeferred?.complete(full)
        }
    }

    /**
     * Serialized command queue ensuring only one OBD-II / AT command is in flight at a time,
     * with timeout and 1 automatic retry.
     */
    suspend fun sendCommand(command: String, timeoutMs: Long = 1_800L, retries: Int = 1): String {
        return commandMutex.withLock {
            var attempt = 0
            var lastResp = ""
            while (attempt <= retries) {
                lastResp = when (_activeTransport.value) {
                    ObdTransportType.BLUETOOTH_CLASSIC_SPP -> sendClassicCommand(command, timeoutMs)
                    ObdTransportType.BLUETOOTH_LOW_ENERGY_GATT -> sendBleCommand(command, timeoutMs)
                    ObdTransportType.UNKNOWN -> ""
                }
                if (lastResp.isNotBlank() && !lastResp.equals("TIMEOUT", ignoreCase = true)) {
                    return@withLock lastResp
                }
                attempt++
                if (attempt <= retries) delay(120L)
            }
            lastResp
        }
    }

    private suspend fun sendClassicCommand(command: String, timeoutMs: Long): String {
        val sock = classicSocket ?: return ""
        return withTimeoutOrNull(timeoutMs) {
            runCatching {
                val out: OutputStream = sock.outputStream
                val input: InputStream = sock.inputStream
                while (input.available() > 0) input.read()
                out.write("$command\r".toByteArray(Charsets.US_ASCII))
                out.flush()
                val sb = StringBuilder()
                while (true) {
                    if (input.available() > 0) {
                        val ch = input.read()
                        if (ch == -1) break
                        val c = ch.toChar()
                        if (c == '>') break
                        sb.append(c)
                    } else {
                        delay(15L)
                    }
                }
                sb.toString().trim()
            }.getOrDefault("")
        } ?: "TIMEOUT"
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendBleCommand(command: String, timeoutMs: Long): String {
        val gatt = bleGatt ?: return ""
        val writeChar = bleWriteChar ?: return ""
        val deferred = CompletableDeferred<String>()
        bleResponseBuffer.clear()
        blePendingDeferred = deferred

        val payload = "$command\r".toByteArray(Charsets.US_ASCII)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = if ((writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            gatt.writeCharacteristic(writeChar, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            writeChar.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(writeChar)
        }
        if (!ok) {
            blePendingDeferred = null
            return ""
        }
        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: "TIMEOUT"
        blePendingDeferred = null
        return resp
    }

    fun setState(state: ObdConnectionState, message: String) {
        _connectionState.value = state
        _statusMessage.value = message
    }

    fun isManualDisconnectRequested(): Boolean = manualDisconnectRequested

    fun resetManualDisconnect() {
        manualDisconnectRequested = false
    }

    fun getLastConnectedAddress(): String? = lastConnectedAddress

    fun disconnect() {
        manualDisconnectRequested = true
        disconnectInternal(resetManualFlag = false)
        _connectionState.value = ObdConnectionState.DISCONNECTED
        _statusMessage.value = "OBD-II adapter disconnected"
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(resetManualFlag: Boolean) {
        if (resetManualFlag) manualDisconnectRequested = false
        stopDiscovery()
        runCatching { classicSocket?.close() }
        classicSocket = null
        runCatching {
            bleGatt?.disconnect()
            bleGatt?.close()
        }
        bleGatt = null
        bleWriteChar = null
        blePendingDeferred?.cancel()
        blePendingDeferred = null
        _activeTransport.value = ObdTransportType.UNKNOWN
    }

    @SuppressLint("MissingPermission")
    private fun upsertDiscoveredDevice(
        device: BluetoothDevice,
        rssi: Int?,
        transportHint: ObdTransportType,
    ) {
        val name = device.name ?: "Bluetooth Device"
        val item = ObdDiscoveredDevice(
            name = name,
            address = device.address,
            rssiDbm = rssi,
            isPaired = device.bondState == BluetoothDevice.BOND_BONDED,
            transportHint = transportHint,
            isHhobdHint = isHhobdNameHint(name),
        )
        val current = _discoveredDevices.value.toMutableList()
        val idx = current.indexOfFirst { it.address == item.address }
        if (idx >= 0) current[idx] = item else current.add(item)
        _discoveredDevices.value = current.sortedWith(
            compareByDescending<ObdDiscoveredDevice> { it.isHhobdHint }
                .thenByDescending { it.isPaired }
                .thenBy { it.name },
        )
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val HHOBD_NAME_HINTS = listOf("HHOBD", "OBDII", "OBD2", "OBD-II", "ELM327")

        fun isHhobdNameHint(name: String): Boolean {
            val upper = name.uppercase()
            return HHOBD_NAME_HINTS.any { upper.contains(it) }
        }
    }
}
