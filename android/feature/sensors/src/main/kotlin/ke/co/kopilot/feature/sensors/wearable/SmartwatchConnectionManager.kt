package ke.co.kopilot.feature.sensors.wearable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.database.dao.WearableDao
import ke.co.kopilot.core.database.entity.WearableConnectionEventEntity
import ke.co.kopilot.core.database.entity.WearableDeviceEntity
import ke.co.kopilot.core.database.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class SmartwatchConnectionState(val displayName: String) {
    DISCONNECTED("Disconnected"),
    BLUETOOTH_DISABLED("Bluetooth Disabled"),
    PERMISSION_REQUIRED("Permission Required"),
    SCANNING("Scanning Nearby BLE Watches"),
    BONDING("Pairing / Bonding"),
    CONNECTING_GATT("Connecting GATT Server"),
    DISCOVERING_SERVICES("Discovering GATT Services"),
    SUBSCRIBED_LIVE("Connected (Live Heart Rate Active)"),
    CONNECTED_NO_HR_SERVICE("Connected (No Standard 0x180D HR Service)"),
    RECONNECTING("Reconnecting (Exponential Backoff)"),
    CONNECTION_ERROR("Connection Error"),
}

data class DiscoveredBleWatch(
    val address: String,
    val name: String,
    val rssiDbm: Int,
    val isBonded: Boolean,
    val advertisesHeartRateService: Boolean,
    val advertisedServiceUuids: List<String>,
    val lastSeenAtMillis: Long,
)

data class SmartwatchDiagnosticsState(
    val connectionState: SmartwatchConnectionState = SmartwatchConnectionState.DISCONNECTED,
    val connectedAddress: String? = null,
    val connectedName: String? = null,
    val manufacturerName: String? = null,
    val modelNumber: String? = null,
    val firmwareRevision: String? = null,
    val bodySensorLocation: String? = null,
    val batteryPercent: Int? = null,
    val rssiDbm: Int? = null,
    val discoveredGattServices: List<String> = emptyList(),
    val heartRateServiceAvailable: Boolean = false,
    val heartRateCharacteristicAvailable: Boolean = false,
    val heartRateNotificationsSubscribed: Boolean = false,
    val batteryServiceAvailable: Boolean = false,
    val deviceInfoServiceAvailable: Boolean = false,
    val reconnectAttempt: Int = 0,
    val maxReconnectAttempts: Int = MAX_RECONNECT_ATTEMPTS,
    val lastConnectedAtMillis: Long? = null,
    val lastMeasurementAtMillis: Long? = null,
    val statusMessage: String = "No smartwatch connected. Scan or select a BLE watch.",
    val lastErrorMessage: String? = null,
    val recommendedAction: String? = null,
) {
    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
    }
}

/**
 * Phase 2 — Production BLE Smartwatch Connection Manager.
 *
 * - Scans for nearby BLE smartwatches for a bounded 12-second window (never continuous background scanning).
 * - Connects to the device's GATT server on a dedicated background dispatcher.
 * - Discovers GATT services (`0x180D` Heart Rate, `0x180F` Battery, `0x180A` Device Information).
 * - Subscribes to Heart Rate Measurement characteristic (`0x2A37`) via CCCD descriptor (`0x2902`).
 * - Decodes 8-bit and 16-bit heart-rate measurements, sensor contact status, energy expended, and RR intervals.
 * - Automatically reconnects with bounded exponential backoff (2s, 4s, 8s, 16s, 32s; max 5 attempts).
 * - Restores the previously selected watch when appropriate and supports disconnecting and forgetting.
 */
@Singleton
class SmartwatchConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wearableDao: WearableDao,
    private val prefsRepo: PreferencesRepository,
    private val heartRateRepository: HeartRateRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _diagnostics = MutableStateFlow(SmartwatchDiagnosticsState())
    val diagnostics: StateFlow<SmartwatchDiagnosticsState> = _diagnostics.asStateFlow()

    private val _discoveredWatches = MutableStateFlow<List<DiscoveredBleWatch>>(emptyList())
    val discoveredWatches: StateFlow<List<DiscoveredBleWatch>> = _discoveredWatches.asStateFlow()

    val savedDevices = wearableDao.observeDevices()
    val connectionEvents = wearableDao.observeConnectionEvents(limit = 30)

    private val discoveredMap = ConcurrentHashMap<String, DiscoveredBleWatch>()
    private var activeGatt: BluetoothGatt? = null
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var rssiPollJob: Job? = null
    private var userInitiatedDisconnect = false
    private var pendingReadQueue = ArrayDeque<BluetoothGattCharacteristic>()

    @Volatile
    private var activeTripId: String? = null

    fun setActiveTripId(tripId: String?) {
        activeTripId = tripId
        heartRateRepository.setActiveTripId(tripId)
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun tryRestoreSavedWatch(bluetoothPermissionGranted: Boolean) {
        if (!bluetoothPermissionGranted) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.PERMISSION_REQUIRED,
                statusMessage = "Bluetooth permission required to connect smartwatch.",
                recommendedAction = "Grant Nearby Devices (BLUETOOTH_SCAN & BLUETOOTH_CONNECT) permission.",
            )
            return
        }
        if (!isBluetoothEnabled()) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.BLUETOOTH_DISABLED,
                statusMessage = "Bluetooth is turned off.",
                recommendedAction = "Turn on Bluetooth in Android Settings to connect your smartwatch.",
            )
            return
        }
        val currentState = _diagnostics.value.connectionState
        if (currentState == SmartwatchConnectionState.SUBSCRIBED_LIVE ||
            currentState == SmartwatchConnectionState.CONNECTING_GATT ||
            currentState == SmartwatchConnectionState.DISCOVERING_SERVICES
        ) {
            return
        }

        scope.launch {
            val settings = prefsRepo.settingsFlow.firstOrNull() ?: return@launch
            if (!settings.smartwatchAutoConnectEnabled) return@launch
            val savedAddr = settings.lastSmartwatchAddress.ifBlank {
                wearableDao.getLastSelectedDevice()?.deviceAddress.orEmpty()
            }
            val savedName = settings.lastSmartwatchName.ifBlank {
                wearableDao.getLastSelectedDevice()?.deviceName ?: "Smartwatch"
            }
            if (savedAddr.isNotBlank()) {
                connectToWatch(
                    address = savedAddr,
                    fallbackName = savedName,
                    bluetoothPermissionGranted = bluetoothPermissionGranted,
                    isAutoRestore = true,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startBleScan(bluetoothPermissionGranted: Boolean, durationMs: Long = 12_000L) {
        if (!bluetoothPermissionGranted) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.PERMISSION_REQUIRED,
                statusMessage = "Bluetooth scan permission denied.",
                lastErrorMessage = "BLUETOOTH_SCAN and BLUETOOTH_CONNECT permissions are required.",
                recommendedAction = "Grant Nearby Devices permission in Android Settings.",
            )
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.BLUETOOTH_DISABLED,
                statusMessage = "Bluetooth is disabled on this phone.",
                lastErrorMessage = "Cannot scan for BLE smartwatches while Bluetooth is off.",
                recommendedAction = "Enable Bluetooth and tap Scan again.",
            )
            return
        }

        stopBleScan(bluetoothPermissionGranted)
        discoveredMap.clear()

        // Include already bonded BLE/Dual-mode devices so paired watches appear immediately
        runCatching {
            adapter.bondedDevices?.forEach { bonded ->
                val type = bonded.type
                if (type == BluetoothDevice.DEVICE_TYPE_LE ||
                    type == BluetoothDevice.DEVICE_TYPE_DUAL ||
                    type == BluetoothDevice.DEVICE_TYPE_UNKNOWN
                ) {
                    val name = bonded.name?.takeIf { it.isNotBlank() } ?: "Paired Device (${bonded.address.takeLast(5)})"
                    discoveredMap[bonded.address] = DiscoveredBleWatch(
                        address = bonded.address,
                        name = name,
                        rssiDbm = -60,
                        isBonded = true,
                        advertisesHeartRateService = false,
                        advertisedServiceUuids = emptyList(),
                        lastSeenAtMillis = System.currentTimeMillis(),
                    )
                }
            }
        }
        publishDiscoveredWatches()

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.CONNECTION_ERROR,
                statusMessage = "BLE scanner unavailable on this device.",
                lastErrorMessage = "BluetoothAdapter.getBluetoothLeScanner() returned null.",
                recommendedAction = "Toggle Bluetooth off and on, then retry.",
            )
            return
        }

        _diagnostics.value = _diagnostics.value.copy(
            connectionState = SmartwatchConnectionState.SCANNING,
            statusMessage = "Scanning for nearby BLE smartwatches (${durationMs / 1000}s window)...",
            lastErrorMessage = null,
            recommendedAction = "Ensure your smartwatch is awake and Heart Rate broadcasting / BLE is enabled on the watch.",
        )

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            scanner.startScan(null, scanSettings, bleScanCallback)
        }.onFailure { err ->
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.CONNECTION_ERROR,
                statusMessage = "BLE scan failed to start.",
                lastErrorMessage = err.localizedMessage ?: "Scanner exception",
                recommendedAction = "Verify Bluetooth and Location services are enabled.",
            )
            return
        }

        scanJob = scope.launch {
            delay(durationMs.coerceIn(4_000L, 20_000L))
            stopBleScan(bluetoothPermissionGranted)
            if (_diagnostics.value.connectionState == SmartwatchConnectionState.SCANNING) {
                val count = _discoveredWatches.value.size
                _diagnostics.value = _diagnostics.value.copy(
                    connectionState = if (activeGatt != null) {
                        SmartwatchConnectionState.SUBSCRIBED_LIVE
                    } else {
                        SmartwatchConnectionState.DISCONNECTED
                    },
                    statusMessage = if (count > 0) {
                        "Scan complete: $count BLE device(s) found. Select your smartwatch to connect."
                    } else {
                        "No BLE smartwatches discovered during scan."
                    },
                    recommendedAction = if (count == 0) {
                        "Wake your smartwatch screen, enable Heart Rate Broadcast in watch settings, and scan again."
                    } else {
                        null
                    },
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan(bluetoothPermissionGranted: Boolean = true) {
        scanJob?.cancel()
        scanJob = null
        if (bluetoothPermissionGranted && isBluetoothEnabled()) {
            runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback) }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val rawName = result.scanRecord?.deviceName
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: discoveredMap[address]?.name
                ?: "BLE Device (${address.takeLast(5)})"

            val serviceUuids = result.scanRecord?.serviceUuids
                ?.map { it.uuid.toString().uppercase() }
                .orEmpty()
            val hasHrService = serviceUuids.any { it.contains("180D") }

            val item = DiscoveredBleWatch(
                address = address,
                name = rawName,
                rssiDbm = result.rssi,
                isBonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
                advertisesHeartRateService = hasHrService,
                advertisedServiceUuids = serviceUuids,
                lastSeenAtMillis = System.currentTimeMillis(),
            )
            discoveredMap[address] = item
            publishDiscoveredWatches()
        }

        override fun onScanFailed(errorCode: Int) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.CONNECTION_ERROR,
                statusMessage = "BLE scan error (code $errorCode).",
                lastErrorMessage = "Android ScanCallback failed with code $errorCode.",
                recommendedAction = "Wait 5 seconds and retry scan, or toggle Bluetooth.",
            )
        }
    }

    private fun publishDiscoveredWatches() {
        val sorted = discoveredMap.values.sortedWith(
            compareByDescending<DiscoveredBleWatch> { it.advertisesHeartRateService }
                .thenByDescending { it.isBonded }
                .thenByDescending { !it.name.startsWith("BLE Device") }
                .thenByDescending { it.rssiDbm },
        )
        _discoveredWatches.value = sorted
    }

    @SuppressLint("MissingPermission")
    fun connectToWatch(
        address: String,
        fallbackName: String = "Smartwatch",
        bluetoothPermissionGranted: Boolean,
        isAutoRestore: Boolean = false,
    ) {
        if (!bluetoothPermissionGranted) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.PERMISSION_REQUIRED,
                statusMessage = "Bluetooth permission required to connect smartwatch.",
                recommendedAction = "Grant BLUETOOTH_CONNECT permission.",
            )
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.BLUETOOTH_DISABLED,
                statusMessage = "Bluetooth is disabled.",
                recommendedAction = "Turn on Bluetooth to connect your smartwatch.",
            )
            return
        }

        stopBleScan(bluetoothPermissionGranted)
        userInitiatedDisconnect = false
        if (!isAutoRestore) {
            reconnectJob?.cancel()
            reconnectJob = null
        }

        val device = runCatching { adapter.getRemoteDevice(address.uppercase()) }.getOrNull()
        if (device == null) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.CONNECTION_ERROR,
                statusMessage = "Invalid Bluetooth MAC address: $address",
                lastErrorMessage = "BluetoothAdapter.getRemoteDevice($address) failed.",
                recommendedAction = "Scan and select a valid nearby smartwatch.",
            )
            return
        }

        val resolvedName = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: discoveredMap[address]?.name
            ?: fallbackName

        closeActiveGatt()

        _diagnostics.value = _diagnostics.value.copy(
            connectionState = SmartwatchConnectionState.CONNECTING_GATT,
            connectedAddress = address,
            connectedName = resolvedName,
            statusMessage = "Connecting to $resolvedName ($address)...",
            lastErrorMessage = null,
            recommendedAction = "Keep your smartwatch close to the phone and confirm pairing if prompted.",
        )
        heartRateRepository.onConnectionStateChanged(
            stateName = SmartwatchConnectionState.CONNECTING_GATT.name,
            deviceAddress = address,
            deviceName = resolvedName,
        )
        recordEvent(address, resolvedName, "CONNECT_ATTEMPT", "Connecting GATT server (autoRestore=$isAutoRestore)")

        scope.launch {
            prefsRepo.setLastSmartwatchDevice(address, resolvedName)
        }

        // Start 15-second connection timeout guard
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(15_000L)
            val st = _diagnostics.value.connectionState
            if (st == SmartwatchConnectionState.CONNECTING_GATT ||
                st == SmartwatchConnectionState.DISCOVERING_SERVICES
            ) {
                recordEvent(address, resolvedName, "CONNECTION_TIMEOUT", "GATT connection timed out after 15 seconds")
                handleDisconnectionOrFailure(
                    address = address,
                    name = resolvedName,
                    reason = "Connection timed out after 15 seconds. Ensure the watch is awake and in range.",
                    bluetoothPermissionGranted = bluetoothPermissionGranted,
                )
            }
        }

        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectWatch() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        rssiPollJob?.cancel()
        rssiPollJob = null

        val addr = _diagnostics.value.connectedAddress
        val name = _diagnostics.value.connectedName
        closeActiveGatt()

        _diagnostics.value = _diagnostics.value.copy(
            connectionState = SmartwatchConnectionState.DISCONNECTED,
            heartRateNotificationsSubscribed = false,
            reconnectAttempt = 0,
            statusMessage = if (name != null) "Disconnected from $name." else "Smartwatch disconnected.",
            recommendedAction = null,
        )
        heartRateRepository.onConnectionStateChanged(
            stateName = SmartwatchConnectionState.DISCONNECTED.name,
            deviceAddress = addr,
            deviceName = name,
        )
        if (addr != null) {
            recordEvent(addr, name, "USER_DISCONNECT", "User disconnected smartwatch")
        }
    }

    @SuppressLint("MissingPermission")
    fun forgetWatch(address: String? = _diagnostics.value.connectedAddress) {
        disconnectWatch()
        scope.launch {
            prefsRepo.clearLastSmartwatchDevice()
            if (!address.isNullOrBlank()) {
                wearableDao.deleteDevice(address)
                recordEvent(address, null, "FORGET_DEVICE", "Removed saved smartwatch profile")
            }
            _diagnostics.value = SmartwatchDiagnosticsState(
                statusMessage = "Smartwatch forgotten. Scan to pair a new wearable.",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeActiveGatt() {
        val gatt = activeGatt
        activeGatt = null
        pendingReadQueue.clear()
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device?.address ?: _diagnostics.value.connectedAddress ?: "UNKNOWN"
            val name = runCatching { gatt.device?.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: _diagnostics.value.connectedName
                ?: "Smartwatch"

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectionTimeoutJob?.cancel()
                _diagnostics.value = _diagnostics.value.copy(
                    connectionState = SmartwatchConnectionState.DISCOVERING_SERVICES,
                    connectedAddress = address,
                    connectedName = name,
                    reconnectAttempt = 0,
                    lastConnectedAtMillis = System.currentTimeMillis(),
                    statusMessage = "Connected to $name. Discovering GATT services...",
                    lastErrorMessage = null,
                )
                recordEvent(address, name, "GATT_CONNECTED", "GATT connected; starting service discovery")
                runCatching { gatt.readRemoteRssi() }
                runCatching { gatt.discoverServices() }
                startPeriodicRssiAndBatteryRefresh()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionTimeoutJob?.cancel()
                rssiPollJob?.cancel()
                val reason = if (status == BluetoothGatt.GATT_SUCCESS) {
                    "Watch disconnected."
                } else {
                    "GATT link lost (status=$status)."
                }
                recordEvent(address, name, "GATT_DISCONNECTED", reason)
                if (userInitiatedDisconnect) {
                    closeActiveGatt()
                    _diagnostics.value = _diagnostics.value.copy(
                        connectionState = SmartwatchConnectionState.DISCONNECTED,
                        heartRateNotificationsSubscribed = false,
                        statusMessage = "Disconnected from $name.",
                    )
                    heartRateRepository.onConnectionStateChanged(
                        stateName = SmartwatchConnectionState.DISCONNECTED.name,
                        deviceAddress = address,
                        deviceName = name,
                    )
                } else {
                    handleDisconnectionOrFailure(
                        address = address,
                        name = name,
                        reason = reason,
                        bluetoothPermissionGranted = true,
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device?.address ?: _diagnostics.value.connectedAddress ?: "UNKNOWN"
            val name = _diagnostics.value.connectedName ?: "Smartwatch"

            if (status != BluetoothGatt.GATT_SUCCESS) {
                recordEvent(address, name, "SERVICE_DISCOVERY_FAILED", "onServicesDiscovered status=$status")
                _diagnostics.value = _diagnostics.value.copy(
                    connectionState = SmartwatchConnectionState.CONNECTION_ERROR,
                    statusMessage = "Failed to discover GATT services (status $status).",
                    lastErrorMessage = "GATT service discovery failed with status $status.",
                    recommendedAction = "Disconnect, toggle Bluetooth on your watch, and reconnect.",
                )
                return
            }

            val services = gatt.services.orEmpty()
            val serviceLabels = services.map { svc -> formatServiceUuidLabel(svc.uuid) }

            val hrService = gatt.getService(UUID_HEART_RATE_SERVICE)
            val hrMeasurementChar = hrService?.getCharacteristic(UUID_HEART_RATE_MEASUREMENT)
            val bodyLocationChar = hrService?.getCharacteristic(UUID_BODY_SENSOR_LOCATION)

            val batteryService = gatt.getService(UUID_BATTERY_SERVICE)
            val batteryLevelChar = batteryService?.getCharacteristic(UUID_BATTERY_LEVEL)

            val devInfoService = gatt.getService(UUID_DEVICE_INFO_SERVICE)
            val manufacturerChar = devInfoService?.getCharacteristic(UUID_MANUFACTURER_NAME)
            val modelChar = devInfoService?.getCharacteristic(UUID_MODEL_NUMBER)
            val firmwareChar = devInfoService?.getCharacteristic(UUID_FIRMWARE_REVISION)

            pendingReadQueue.clear()
            listOfNotNull(manufacturerChar, modelChar, firmwareChar, bodyLocationChar, batteryLevelChar)
                .forEach { pendingReadQueue.addLast(it) }

            val hrAvailable = hrService != null && hrMeasurementChar != null
            _diagnostics.value = _diagnostics.value.copy(
                discoveredGattServices = serviceLabels,
                heartRateServiceAvailable = hrService != null,
                heartRateCharacteristicAvailable = hrMeasurementChar != null,
                batteryServiceAvailable = batteryService != null,
                deviceInfoServiceAvailable = devInfoService != null,
            )

            persistDiscoveredDeviceMetadata(
                address = address,
                name = name,
                servicesCsv = serviceLabels.joinToString(", "),
                hasHr = hrAvailable,
                hasBattery = batteryService != null,
            )

            if (hrMeasurementChar != null) {
                subscribeToHeartRateNotifications(gatt, hrMeasurementChar)
            } else {
                _diagnostics.value = _diagnostics.value.copy(
                    connectionState = SmartwatchConnectionState.CONNECTED_NO_HR_SERVICE,
                    heartRateNotificationsSubscribed = false,
                    statusMessage = "Connected to $name, but standard BLE Heart Rate Service (0x180D) is not exposed.",
                    lastErrorMessage = "GATT service 0x180D / characteristic 0x2A37 not found among ${serviceLabels.size} discovered services.",
                    recommendedAction = "Enable 'Heart Rate Broadcast' or 'Share HR over BLE' in your smartwatch settings, or sync via Health Connect.",
                )
                heartRateRepository.onConnectionStateChanged(
                    stateName = SmartwatchConnectionState.CONNECTED_NO_HR_SERVICE.name,
                    deviceAddress = address,
                    deviceName = name,
                )
                recordEvent(
                    address,
                    name,
                    "NO_STANDARD_HR_SERVICE",
                    "Services discovered (${serviceLabels.size}), but 0x180D/0x2A37 not exposed",
                )
                drainNextCharacteristicRead(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val address = gatt.device?.address ?: _diagnostics.value.connectedAddress ?: "UNKNOWN"
            val name = _diagnostics.value.connectedName ?: "Smartwatch"

            if (descriptor.uuid == UUID_CCCD &&
                descriptor.characteristic.uuid == UUID_HEART_RATE_MEASUREMENT
            ) {
                val ok = (status == BluetoothGatt.GATT_SUCCESS)
                _diagnostics.value = _diagnostics.value.copy(
                    connectionState = if (ok) {
                        SmartwatchConnectionState.SUBSCRIBED_LIVE
                    } else {
                        SmartwatchConnectionState.CONNECTION_ERROR
                    },
                    heartRateNotificationsSubscribed = ok,
                    statusMessage = if (ok) {
                        "Connected to $name — Live BLE Heart Rate notifications active (0x2A37)."
                    } else {
                        "Failed to enable Heart Rate notifications (CCCD status $status)."
                    },
                    recommendedAction = if (ok) null else "Ensure the watch is unlocked on your wrist and retry.",
                )
                heartRateRepository.onConnectionStateChanged(
                    stateName = if (ok) {
                        SmartwatchConnectionState.SUBSCRIBED_LIVE.name
                    } else {
                        SmartwatchConnectionState.CONNECTION_ERROR.name
                    },
                    deviceAddress = address,
                    deviceName = name,
                )
                recordEvent(
                    address,
                    name,
                    if (ok) "HR_SUBSCRIBED" else "HR_SUBSCRIBE_FAILED",
                    "CCCD 0x2902 write status=$status",
                )
                drainNextCharacteristicRead(gatt)
            }
        }

        @Deprecated("Deprecated in API 33, kept for API 26-32 compatibility")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleCharacteristicPayload(gatt, characteristic.uuid, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicPayload(gatt, characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33, kept for API 26-32 compatibility")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    handleCharacteristicPayload(gatt, characteristic.uuid, characteristic.value)
                }
                drainNextCharacteristicRead(gatt)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicPayload(gatt, characteristic.uuid, value)
            }
            drainNextCharacteristicRead(gatt)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _diagnostics.value = _diagnostics.value.copy(rssiDbm = rssi)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToHeartRateNotifications(
        gatt: BluetoothGatt,
        hrChar: BluetoothGattCharacteristic,
    ) {
        val localSet = gatt.setCharacteristicNotification(hrChar, true)
        val cccd = hrChar.getDescriptor(UUID_CCCD)
        if (!localSet || cccd == null) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.SUBSCRIBED_LIVE,
                heartRateNotificationsSubscribed = localSet,
                statusMessage = "Heart Rate characteristic active (local notification=${localSet}).",
            )
            drainNextCharacteristicRead(gatt)
            return
        }

        val props = hrChar.properties
        val useIndication = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
            (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
        val payload = if (useIndication) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, payload)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = payload
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainNextCharacteristicRead(gatt: BluetoothGatt) {
        val next = pendingReadQueue.removeFirstOrNull() ?: return
        runCatching { gatt.readCharacteristic(next) }
    }

    private fun handleCharacteristicPayload(
        gatt: BluetoothGatt,
        uuid: UUID,
        bytes: ByteArray?,
    ) {
        if (bytes == null) return
        val address = gatt.device?.address ?: _diagnostics.value.connectedAddress ?: "UNKNOWN"
        val name = _diagnostics.value.connectedName ?: "Smartwatch"
        val now = System.currentTimeMillis()

        when (uuid) {
            UUID_HEART_RATE_MEASUREMENT -> {
                val decoded = BleHeartRateParser.decodeHeartRateMeasurement(bytes)
                _diagnostics.value = _diagnostics.value.copy(
                    lastMeasurementAtMillis = now,
                    lastErrorMessage = decoded.invalidReason,
                )
                heartRateRepository.onBleHeartRateReceived(
                    deviceAddress = address,
                    deviceName = name,
                    decoded = decoded,
                    timestampMillis = now,
                )
            }

            UUID_BATTERY_LEVEL -> {
                if (bytes.isNotEmpty()) {
                    val level = (bytes[0].toInt() and 0xFF).coerceIn(0, 100)
                    _diagnostics.value = _diagnostics.value.copy(batteryPercent = level)
                    updateStoredDeviceInfo(address) { it.copy(lastBatteryPercent = level, updatedAtMillis = now) }
                }
            }

            UUID_MANUFACTURER_NAME -> {
                val mfg = bytes.decodeToString().trim().takeIf { it.isNotBlank() }
                _diagnostics.value = _diagnostics.value.copy(manufacturerName = mfg)
                updateStoredDeviceInfo(address) { it.copy(manufacturerName = mfg, updatedAtMillis = now) }
            }

            UUID_MODEL_NUMBER -> {
                val mdl = bytes.decodeToString().trim().takeIf { it.isNotBlank() }
                _diagnostics.value = _diagnostics.value.copy(modelNumber = mdl)
                updateStoredDeviceInfo(address) { it.copy(modelNumber = mdl, updatedAtMillis = now) }
            }

            UUID_FIRMWARE_REVISION -> {
                val fw = bytes.decodeToString().trim().takeIf { it.isNotBlank() }
                _diagnostics.value = _diagnostics.value.copy(firmwareRevision = fw)
                updateStoredDeviceInfo(address) { it.copy(firmwareRevision = fw, updatedAtMillis = now) }
            }

            UUID_BODY_SENSOR_LOCATION -> {
                val loc = BleHeartRateParser.decodeBodySensorLocation(bytes)
                _diagnostics.value = _diagnostics.value.copy(bodySensorLocation = loc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPeriodicRssiAndBatteryRefresh() {
        rssiPollJob?.cancel()
        rssiPollJob = scope.launch {
            while (true) {
                delay(20_000L)
                val gatt = activeGatt ?: break
                runCatching { gatt.readRemoteRssi() }
                val batChar = gatt.getService(UUID_BATTERY_SERVICE)?.getCharacteristic(UUID_BATTERY_LEVEL)
                if (batChar != null && pendingReadQueue.isEmpty()) {
                    runCatching { gatt.readCharacteristic(batChar) }
                }
            }
        }
    }

    private fun handleDisconnectionOrFailure(
        address: String,
        name: String,
        reason: String,
        bluetoothPermissionGranted: Boolean,
    ) {
        closeActiveGatt()
        heartRateRepository.onConnectionStateChanged(
            stateName = SmartwatchConnectionState.RECONNECTING.name,
            deviceAddress = address,
            deviceName = name,
        )

        val currentAttempt = _diagnostics.value.reconnectAttempt
        if (currentAttempt >= SmartwatchDiagnosticsState.MAX_RECONNECT_ATTEMPTS || !isBluetoothEnabled()) {
            _diagnostics.value = _diagnostics.value.copy(
                connectionState = SmartwatchConnectionState.DISCONNECTED,
                heartRateNotificationsSubscribed = false,
                statusMessage = "$reason Automatic reconnection paused after $currentAttempt attempts.",
                lastErrorMessage = reason,
                recommendedAction = "Verify your smartwatch is powered on and within range, then tap Connect.",
            )
            heartRateRepository.onConnectionStateChanged(
                stateName = SmartwatchConnectionState.DISCONNECTED.name,
                deviceAddress = address,
                deviceName = name,
            )
            return
        }

        val nextAttempt = currentAttempt + 1
        // Bounded exponential backoff: 2s, 4s, 8s, 16s, 32s
        val backoffSeconds = (1L shl nextAttempt).coerceIn(2L, 32L)
        _diagnostics.value = _diagnostics.value.copy(
            connectionState = SmartwatchConnectionState.RECONNECTING,
            heartRateNotificationsSubscribed = false,
            reconnectAttempt = nextAttempt,
            statusMessage = "$reason Reconnecting in ${backoffSeconds}s (attempt $nextAttempt/${SmartwatchDiagnosticsState.MAX_RECONNECT_ATTEMPTS})...",
            lastErrorMessage = reason,
        )

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffSeconds * 1000L)
            if (!userInitiatedDisconnect && isBluetoothEnabled()) {
                connectToWatch(
                    address = address,
                    fallbackName = name,
                    bluetoothPermissionGranted = bluetoothPermissionGranted,
                    isAutoRestore = true,
                )
            }
        }
    }

    private fun persistDiscoveredDeviceMetadata(
        address: String,
        name: String,
        servicesCsv: String,
        hasHr: Boolean,
        hasBattery: Boolean,
    ) {
        scope.launch {
            val now = System.currentTimeMillis()
            val existing = wearableDao.getDeviceByAddress(address)
            val entity = WearableDeviceEntity(
                deviceAddress = address,
                deviceName = name,
                manufacturerName = _diagnostics.value.manufacturerName ?: existing?.manufacturerName,
                modelNumber = _diagnostics.value.modelNumber ?: existing?.modelNumber,
                firmwareRevision = _diagnostics.value.firmwareRevision ?: existing?.firmwareRevision,
                supportsHeartRateService = hasHr,
                supportsBatteryService = hasBattery,
                lastBatteryPercent = _diagnostics.value.batteryPercent ?: existing?.lastBatteryPercent,
                lastRssiDbm = _diagnostics.value.rssiDbm ?: existing?.lastRssiDbm,
                discoveredServicesCsv = servicesCsv,
                isLastSelected = true,
                firstPairedAtMillis = existing?.firstPairedAtMillis ?: now,
                lastConnectedAtMillis = now,
                updatedAtMillis = now,
            )
            wearableDao.upsertDevice(entity)
            wearableDao.markDeviceAsSelected(address)
        }
    }

    private fun updateStoredDeviceInfo(
        address: String,
        transform: (WearableDeviceEntity) -> WearableDeviceEntity,
    ) {
        scope.launch {
            val existing = wearableDao.getDeviceByAddress(address) ?: return@launch
            wearableDao.upsertDevice(transform(existing))
        }
    }

    private fun recordEvent(
        address: String,
        name: String?,
        eventType: String,
        detail: String,
    ) {
        scope.launch {
            wearableDao.insertConnectionEvent(
                WearableConnectionEventEntity(
                    deviceAddress = address,
                    deviceName = name,
                    tripId = activeTripId,
                    eventType = eventType,
                    detail = detail,
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun formatServiceUuidLabel(uuid: UUID): String {
        val upper = uuid.toString().uppercase()
        val shortCode = if (upper.endsWith("-0000-1000-8000-00805F9B34FB")) {
            "0x" + upper.substring(4, 8)
        } else {
            upper.take(13) + "..."
        }
        return when (shortCode) {
            "0x1800" -> "0x1800 (Generic Access)"
            "0x1801" -> "0x1801 (Generic Attribute)"
            "0x180A" -> "0x180A (Device Information)"
            "0x180D" -> "0x180D (Heart Rate Service)"
            "0x180F" -> "0x180F (Battery Service)"
            "0x1805" -> "0x1805 (Current Time)"
            "0x181C" -> "0x181C (User Data)"
            "0x1826" -> "0x1826 (Fitness Machine)"
            else -> shortCode
        }
    }

    companion object {
        val UUID_HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val UUID_BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val UUID_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val UUID_DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val UUID_MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val UUID_MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val UUID_FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
