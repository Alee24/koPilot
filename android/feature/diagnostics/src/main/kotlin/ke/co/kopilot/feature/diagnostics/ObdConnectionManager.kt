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
import kotlinx.coroutines.channels.Channel
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

    private val _failureCategory = MutableStateFlow(ObdFailureCategory.NONE)
    val failureCategory: StateFlow<ObdFailureCategory> = _failureCategory.asStateFlow()

    fun setFailureCategory(category: ObdFailureCategory) {
        _failureCategory.value = category
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun appendLog(line: String) {
        _connectionLog.value = (_connectionLog.value + line).takeLast(30)
    }

    private var classicSocket: BluetoothSocket? = null
    private var classicRxChannel: Channel<Int>? = null
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
            _failureCategory.value = ObdFailureCategory.PERMISSION_DENIED
            _connectionState.value = ObdConnectionState.ERROR
            _statusMessage.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        if (btAdapter == null || !btAdapter.isEnabled) {
            val msg = "Bluetooth is turned OFF — enable Bluetooth and try again"
            appendLog(msg)
            _failureCategory.value = ObdFailureCategory.BLUETOOTH_DISABLED
            _connectionState.value = ObdConnectionState.ERROR
            _statusMessage.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        manualDisconnectRequested = false
        _failureCategory.value = ObdFailureCategory.NONE
        stopDiscovery()
        disconnectInternal(resetManualFlag = false)
        lastConnectedAddress = address

        val device = runCatching { btAdapter.getRemoteDevice(address) }.getOrElse {
            _failureCategory.value = ObdFailureCategory.ADAPTER_UNREACHABLE_OR_BUSY
            _connectionState.value = ObdConnectionState.ERROR
            _statusMessage.value = "Invalid Bluetooth address"
            return Result.failure(it)
        }

        // Explicitly initiate OS Bluetooth bonding when a Classic/Dual adapter is unpaired
        var pairingFailedHint = false
        if (device.type != BluetoothDevice.DEVICE_TYPE_LE && device.bondState == BluetoothDevice.BOND_NONE) {
            _connectionState.value = ObdConnectionState.PAIRING
            _statusMessage.value = "Pairing with ${device.name ?: address}... Confirm OS pairing prompt (PIN 1234 or 0000)."
            appendLog("Initiating OS createBond() for unpaired adapter ${device.address}...")
            val bonded = awaitBonding(device)
            if (bonded) {
                appendLog("OS pairing succeeded for ${device.name ?: address}.")
            } else {
                pairingFailedHint = true
                appendLog("OS bonding did not complete; attempting insecure Classic SPP fallback...")
            }
        } else if (device.bondState == BluetoothDevice.BOND_BONDING) {
            _connectionState.value = ObdConnectionState.PAIRING
            _statusMessage.value = "Pairing with ${device.name ?: address}..."
            var waitedMs = 0L
            while (waitedMs < 12_000L && device.bondState == BluetoothDevice.BOND_BONDING) {
                delay(300L)
                waitedMs += 300L
            }
        }

        _connectionState.value = ObdConnectionState.CONNECTING
        _statusMessage.value = "Connecting to ${device.name ?: address}..."

        // Try Classic SPP first unless device explicitly reports LE-only
        var classicError: Throwable? = null
        if (device.type != BluetoothDevice.DEVICE_TYPE_LE) {
            val sppResult = connectClassicSpp(device)
            if (sppResult.isSuccess) {
                _activeTransport.value = ObdTransportType.BLUETOOTH_CLASSIC_SPP
                return Result.success(ObdTransportType.BLUETOOTH_CLASSIC_SPP)
            }
            classicError = sppResult.exceptionOrNull()
        }

        // Fall back to BLE GATT only if the device supports LE / Dual / Unknown transport
        var bleError: Throwable? = null
        if (device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            val bleResult = connectBleGatt(device)
            if (bleResult.isSuccess) {
                _activeTransport.value = ObdTransportType.BLUETOOTH_LOW_ENERGY_GATT
                return Result.success(ObdTransportType.BLUETOOTH_LOW_ENERGY_GATT)
            }
            bleError = bleResult.exceptionOrNull()
        }

        if (pairingFailedHint && device.bondState != BluetoothDevice.BOND_BONDED) {
            val pairMsg = "OS pairing did not complete for ${device.name ?: address}. Pair in Android Bluetooth Settings (PIN 1234 or 0000)."
            appendLog(pairMsg)
            _failureCategory.value = ObdFailureCategory.PAIRING_FAILED
            _connectionState.value = ObdConnectionState.ERROR
            _statusMessage.value = pairMsg
            return Result.failure(IllegalStateException(pairMsg))
        }

        val err = classicError ?: bleError
            ?: IllegalStateException("Unsupported or unreachable OBD-II adapter")
        _failureCategory.value = if (err is UnsupportedOperationException) {
            ObdFailureCategory.UNSUPPORTED_PROTOCOL
        } else {
            ObdFailureCategory.ADAPTER_UNREACHABLE_OR_BUSY
        }
        _connectionState.value = if (err is UnsupportedOperationException) {
            ObdConnectionState.UNSUPPORTED
        } else {
            ObdConnectionState.ERROR
        }
        _statusMessage.value = err.localizedMessage ?: "Failed to connect to adapter"
        return Result.failure(err)
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitBonding(device: BluetoothDevice): Boolean {
        val bondResult = CompletableDeferred<Boolean>()
        var sawBondingState = false
        val bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (dev?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                when (state) {
                    BluetoothDevice.BOND_BONDING -> sawBondingState = true
                    BluetoothDevice.BOND_BONDED -> if (!bondResult.isCompleted) bondResult.complete(true)
                    BluetoothDevice.BOND_NONE -> if (sawBondingState && !bondResult.isCompleted) bondResult.complete(false)
                }
            }
        }
        val registered = runCatching {
            context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            true
        }.getOrDefault(false)

        return try {
            val initiated = runCatching { device.createBond() }.getOrDefault(false)
            if (!initiated) return device.bondState == BluetoothDevice.BOND_BONDED
            withTimeoutOrNull(15_000L) {
                // Poll alongside BroadcastReceiver in case OS already transitioned before receiver fired
                val pollJob = scope.launch {
                    var elapsed = 0L
                    while (elapsed < 15_000L && !bondResult.isCompleted) {
                        val st = device.bondState
                        if (st == BluetoothDevice.BOND_BONDED) {
                            bondResult.complete(true)
                            break
                        }
                        if (st == BluetoothDevice.BOND_BONDING) {
                            sawBondingState = true
                        } else if (sawBondingState && st == BluetoothDevice.BOND_NONE) {
                            bondResult.complete(false)
                            break
                        }
                        delay(300L)
                        elapsed += 300L
                    }
                }
                try {
                    bondResult.await()
                } finally {
                    pollJob.cancel()
                }
            } ?: (device.bondState == BluetoothDevice.BOND_BONDED)
        } finally {
            if (registered) runCatching { context.unregisterReceiver(bondReceiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectClassicSpp(device: BluetoothDevice): Result<Unit> {
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "Secure SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "Insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "Insecure RFCOMM Ch.1" to {
                @Suppress("UNCHECKED_CAST")
                device.javaClass
                    .getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
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
                delay(250L)
                sock = factory()
                val s = sock!!
                appendLog("Classic $label (${idx + 1}/${attempts.size})...")
                // Bounded 7-second socket connect timeout prevents indefinite RFCOMM hangs
                val connectedInTime = withTimeoutOrNull(7_000L) {
                    val connectDeferred = CompletableDeferred<Unit>()
                    val worker = Thread {
                        try {
                            s.connect()
                            connectDeferred.complete(Unit)
                        } catch (t: Throwable) {
                            connectDeferred.completeExceptionally(t)
                        }
                    }
                    worker.isDaemon = true
                    worker.start()
                    connectDeferred.await()
                    true
                } ?: false
                if (!connectedInTime || !s.isConnected) {
                    runCatching { s.close() }
                    throw IllegalStateException("$label timed out after 7s (adapter powered off or busy in another app)")
                }
                classicSocket = s
                startClassicReader(s)
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

    private fun startClassicReader(sock: BluetoothSocket) {
        classicRxChannel?.close()
        val channel = Channel<Int>(capacity = 4096)
        classicRxChannel = channel
        val readerThread = Thread({
            val buf = ByteArray(512)
            try {
                val input: InputStream = sock.inputStream
                while (classicSocket === sock && sock.isConnected) {
                    val count = input.read(buf)
                    if (count == -1) {
                        channel.trySend(-1)
                        if (classicSocket === sock) {
                            markUnexpectedSocketDrop("OBD-II stream closed by adapter (EOF)")
                        }
                        break
                    }
                    for (i in 0 until count) {
                        channel.trySend(buf[i].toInt() and 0xFF)
                    }
                }
            } catch (io: java.io.IOException) {
                channel.trySend(-1)
                if (classicSocket === sock) {
                    markUnexpectedSocketDrop("OBD-II socket I/O dropped: ${io.localizedMessage ?: "broken pipe"}")
                }
            } catch (_: Throwable) {
                channel.trySend(-1)
            } finally {
                channel.close()
            }
        }, "KoPilot-OBD-SPP-Reader")
        readerThread.isDaemon = true
        readerThread.start()
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
                    readyDeferred.complete(Result.failure(UnsupportedOperationException("BLE service discovery failed (status=$status)")))
                    return
                }
                val candidateServices = gatt.services.orEmpty()
                    .filterNot { isIgnoredStandardGattService(it.uuid) }
                    .sortedByDescending { obdGattServicePriority(it.uuid) }

                var writeChar: BluetoothGattCharacteristic? = null
                var notifyChar: BluetoothGattCharacteristic? = null
                var matchedServiceUuid: UUID? = null

                for (service in candidateServices) {
                    var svcWrite: BluetoothGattCharacteristic? = null
                    var svcNotify: BluetoothGattCharacteristic? = null
                    for (ch in service.characteristics.orEmpty()) {
                        val props = ch.properties
                        val writable = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        val notifiable = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        if (writable && svcWrite == null) svcWrite = ch
                        if (notifiable && svcNotify == null) svcNotify = ch
                    }
                    if (svcWrite != null && svcNotify != null) {
                        writeChar = svcWrite
                        notifyChar = svcNotify
                        matchedServiceUuid = service.uuid
                        break
                    }
                }
                if (writeChar == null || notifyChar == null) {
                    appendLog("BLE services found (${gatt.services.orEmpty().size}) but none expose OBD serial write+notify.")
                    readyDeferred.complete(
                        Result.failure(UnsupportedOperationException("Device exposes no serial BLE GATT command/notify characteristics")),
                    )
                    return
                }
                appendLog("BLE OBD service ${matchedServiceUuid.toString().take(8)} W=${writeChar.uuid.toString().take(8)} N=${notifyChar.uuid.toString().take(8)}")
                bleWriteChar = writeChar
                gatt.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val useIndicate = (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
                        (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    val enableVal = if (useIndicate) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, enableVal)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = enableVal
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                } else {
                    readyDeferred.complete(Result.success(Unit))
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                runCatching { gatt.requestMtu(247) }
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
        val clean = chunk.replace("\u0000", "")
        if (clean.isEmpty()) return
        bleResponseBuffer.append(clean)
        if (clean.contains('>')) {
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
        val decision = ObdReadOnlyFirewall.evaluate(command)
        if (!decision.allowed) {
            appendLog("FIREWALL BLOCKED '${decision.normalizedCommand}': ${decision.reason}")
            return "BLOCKED_READ_ONLY"
        }
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
        val sock = classicSocket ?: return "DISCONNECTED"
        val rx = classicRxChannel ?: return "DISCONNECTED"
        if (!sock.isConnected) {
            markUnexpectedSocketDrop("Classic SPP socket is no longer connected")
            return "DISCONNECTED"
        }
        return withTimeoutOrNull(timeoutMs) {
            try {
                val out: OutputStream = sock.outputStream
                // Drain any stale bytes already queued in the persistent reader channel
                while (rx.tryReceive().isSuccess) {
                    // discard stale prompt/echo
                }
                out.write("$command\r".toByteArray(Charsets.US_ASCII))
                out.flush()
                val sb = StringBuilder()
                while (true) {
                    val ch = rx.receiveCatching().getOrNull() ?: -1
                    if (ch == -1) {
                        markUnexpectedSocketDrop("OBD-II stream closed by adapter (EOF)")
                        return@withTimeoutOrNull "DISCONNECTED"
                    }
                    if (ch == 0) continue
                    val c = ch.toChar()
                    if (c == '>') break
                    sb.append(c)
                }
                sb.toString().trim()
            } catch (io: java.io.IOException) {
                markUnexpectedSocketDrop("OBD-II socket I/O dropped: ${io.localizedMessage ?: "broken pipe"}")
                "DISCONNECTED"
            } catch (_: Throwable) {
                ""
            }
        } ?: "TIMEOUT"
    }

    private fun markUnexpectedSocketDrop(reason: String) {
        appendLog(reason)
        runCatching { classicSocket?.close() }
        classicSocket = null
        runCatching { classicRxChannel?.close() }
        classicRxChannel = null
        if (!manualDisconnectRequested && _connectionState.value == ObdConnectionState.CONNECTED) {
            _failureCategory.value = ObdFailureCategory.UNEXPECTED_DISCONNECT
            _connectionState.value = ObdConnectionState.RECONNECTING
            _statusMessage.value = "OBD-II link dropped; reconnecting..."
        }
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
        runCatching { classicRxChannel?.close() }
        classicRxChannel = null
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
        private val HHOBD_NAME_HINTS = listOf(
            "HHOBD", "OBDII", "OBD2", "OBD-II", "ELM327",
            "VGATE", "VEEPEAK", "OBDLINK", "CARISTA", "KONNWEI", "V-LINK", "IOS-VLINK",
        )

        private val IGNORED_STANDARD_GATT_PREFIXES = setOf(
            "00001800", // Generic Access
            "00001801", // Generic Attribute
            "00001802", // Immediate Alert
            "00001803", // Link Loss
            "00001804", // Tx Power
            "00001805", // Current Time
            "0000180A", // Device Information
            "0000180D", // Heart Rate
            "0000180F", // Battery Service
        )

        fun isIgnoredStandardGattService(uuid: UUID): Boolean {
            val prefix = uuid.toString().uppercase().take(8)
            return prefix in IGNORED_STANDARD_GATT_PREFIXES
        }

        fun obdGattServicePriority(uuid: UUID): Int {
            val upper = uuid.toString().uppercase()
            return when {
                upper.startsWith("0000FFF0") -> 100 // ELM327 BLE / Vgate / Veepeak primary serial
                upper.startsWith("0000FFE0") -> 95  // HM-10 / ELM327 BLE serial
                upper.startsWith("000018F0") -> 90  // Vgate iCar Pro / V-Link BLE
                upper.startsWith("E7810A71") -> 88  // Common BLE OBD-II UART bridge
                upper.startsWith("6E400001") -> 85  // Nordic UART Service (NUS)
                else -> 10
            }
        }

        fun isHhobdNameHint(name: String): Boolean {
            val upper = name.uppercase()
            return HHOBD_NAME_HINTS.any { upper.contains(it) }
        }
    }
}
