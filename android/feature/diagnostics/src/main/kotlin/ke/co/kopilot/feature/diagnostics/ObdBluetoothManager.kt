package ke.co.kopilot.feature.diagnostics

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
import javax.inject.Inject
import javax.inject.Singleton

data class PairedBluetoothDevice(
    val name: String,
    val address: String,
)

@Singleton
class ObdBluetoothManager @Inject constructor(
    val connectionManager: ObdConnectionManager,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _state = MutableStateFlow(VehicleDiagnosticsState())
    val state: StateFlow<VehicleDiagnosticsState> = _state.asStateFlow()

    private val _liveTelemetry = MutableStateFlow(ObdLiveTelemetry())
    val liveTelemetry: StateFlow<ObdLiveTelemetry> = _liveTelemetry.asStateFlow()

    private val _capabilities = MutableStateFlow<List<ObdPidCapability>>(defaultCapabilityRegistry(emptySet()))
    val capabilities: StateFlow<List<ObdPidCapability>> = _capabilities.asStateFlow()

    val connectionState: StateFlow<ObdConnectionState> = connectionManager.connectionState
    val discoveredDevices: StateFlow<List<ObdDiscoveredDevice>> = connectionManager.discoveredDevices
    val connectionLog: StateFlow<List<String>> = connectionManager.connectionLog

    private var pollingJob: Job? = null

    fun listPairedDevices(bluetoothPermissionGranted: Boolean): List<PairedBluetoothDevice> {
        return connectionManager.listPairedDevices(bluetoothPermissionGranted).map {
            PairedBluetoothDevice(name = it.name, address = it.address)
        }
    }

    fun startScan(bluetoothPermissionGranted: Boolean) {
        connectionManager.startDiscovery(bluetoothPermissionGranted)
    }

    fun stopScan() {
        connectionManager.stopDiscovery()
    }

    fun connectAndMonitor(address: String, bluetoothPermissionGranted: Boolean) {
        pollingJob?.cancel()
        pollingJob = null
        connectionManager.disconnect()
        connectionManager.resetManualDisconnect()
        connectionManager.appendLog("Starting OBD-II connection to $address...")
        pollingJob = scope.launch {
            var reconnectAttempts = 0
            while (isActive && !connectionManager.isManualDisconnectRequested()) {
                val connRes = connectionManager.connect(address, bluetoothPermissionGranted)
                if (connRes.isFailure) {
                    val failureMsg = connRes.exceptionOrNull()?.localizedMessage ?: connectionManager.statusMessage.value
                    _state.value = _state.value.copy(
                        obdConnected = false,
                        statusMessage = failureMsg,
                    )
                    if (connectionManager.connectionState.value == ObdConnectionState.UNSUPPORTED || reconnectAttempts >= 2) {
                        connectionManager.setState(ObdConnectionState.ERROR, failureMsg)
                        break
                    }
                    reconnectAttempts++
                    connectionManager.setState(ObdConnectionState.RECONNECTING, "Retrying ($reconnectAttempts/2): $failureMsg")
                    delay(1_500L * reconnectAttempts)
                    continue
                }

                reconnectAttempts = 0
                val transport = connRes.getOrDefault(ObdTransportType.UNKNOWN)
                connectionManager.setState(ObdConnectionState.ADAPTER_INITIALIZING, "Initializing adapter...")

                // Wake up ELM327/HHOBD chip before ATZ
                connectionManager.sendCommand("", timeoutMs = 600L, retries = 0)
                val atz = connectionManager.sendCommand("ATZ", timeoutMs = 3_500L)
                connectionManager.appendLog("> ATZ -> ${atz.replace("\r", " ").take(40)}")
                delay(600L)
                val ate0 = connectionManager.sendCommand("ATE0")
                connectionManager.appendLog("> ATE0 -> ${ate0.replace("\r", " ").take(24)}")
                connectionManager.sendCommand("ATL0")
                connectionManager.sendCommand("ATS0")
                connectionManager.sendCommand("ATH0")
                val ati = connectionManager.sendCommand("ATI")
                val atrv = connectionManager.sendCommand("ATRV")
                connectionManager.appendLog("> ATI -> ${ati.take(30)} | ATRV -> $atrv")

                val adapterVerified = atz.contains("ELM", ignoreCase = true) ||
                    atz.contains("OBD", ignoreCase = true) ||
                    atz.contains("OK", ignoreCase = true) ||
                    ati.contains("ELM", ignoreCase = true) ||
                    ati.contains("OBD", ignoreCase = true) ||
                    ate0.contains("OK", ignoreCase = true) ||
                    atrv.contains("V", ignoreCase = true)

                if (!adapterVerified) {
                    connectionManager.appendLog("Adapter init failed: ATZ='$atz' ATE0='$ate0'")
                    connectionManager.setState(
                        ObdConnectionState.UNSUPPORTED,
                        "Device did not respond to ELM327-compatible initialization",
                    )
                    _state.value = _state.value.copy(
                        obdConnected = false,
                        statusMessage = "Unsupported adapter protocol",
                    )
                    connectionManager.disconnect()
                    break
                }

                connectionManager.setState(ObdConnectionState.ECU_CONNECTING, "Connecting to vehicle ECU...")
                var protoResp = ""
                var pid00Resp = ""
                for (protoCmd in listOf("ATSP0", "ATSP6", "ATSP7", "ATSP3", "ATSP5")) {
                    protoResp = connectionManager.sendCommand(protoCmd, timeoutMs = 3_000L)
                    pid00Resp = connectionManager.sendCommand("0100", timeoutMs = 12_000L)
                    connectionManager.appendLog("> $protoCmd + 0100 -> ${pid00Resp.replace("\r", " ").take(36)}")
                    if (!ObdPidParser.isAdapterError(pid00Resp) && pid00Resp.uppercase().replace(" ", "").contains("4100")) {
                        break
                    }
                }

                if (ObdPidParser.isAdapterError(pid00Resp) || !pid00Resp.uppercase().replace(" ", "").contains("4100")) {
                    connectionManager.setState(
                        ObdConnectionState.ERROR,
                        "ECU not responding (${pid00Resp.ifBlank { "TIMEOUT" }}). Ensure ignition is ON.",
                    )
                    _state.value = _state.value.copy(
                        obdConnected = false,
                        adapterName = ati.ifBlank { address },
                        statusMessage = "ECU unreachable — turn ignition ON",
                    )
                    delay(3_000L)
                    continue
                }

                val supportedPids = mutableSetOf<String>()
                supportedPids.addAll(ObdPidParser.parseSupportedPidsBitmap(pid00Resp, 0x00))
                if ("0120" in supportedPids) {
                    supportedPids.addAll(ObdPidParser.parseSupportedPidsBitmap(connectionManager.sendCommand("0120"), 0x20))
                }
                if ("0140" in supportedPids) {
                    supportedPids.addAll(ObdPidParser.parseSupportedPidsBitmap(connectionManager.sendCommand("0140"), 0x40))
                }
                if ("0160" in supportedPids) {
                    supportedPids.addAll(ObdPidParser.parseSupportedPidsBitmap(connectionManager.sendCommand("0160"), 0x60))
                }

                _capabilities.value = defaultCapabilityRegistry(supportedPids)
                val adapterVoltage = ObdPidParser.parseAdapterVoltage(atrv)
                val protoLabel = if (protoResp.contains("OK", ignoreCase = true)) "Auto OBD-II (ATSP0)" else protoResp.take(28)

                connectionManager.setState(ObdConnectionState.CONNECTED, "Connected (${supportedPids.size} Mode 01 PIDs)")
                _state.value = _state.value.copy(
                    obdConnected = true,
                    adapterName = ati.ifBlank { address },
                    protocolName = protoLabel,
                    statusMessage = "Connected • ${supportedPids.size} PIDs supported",
                )
                _liveTelemetry.value = _liveTelemetry.value.copy(
                    adapterInfo = ati.ifBlank { atz },
                    protocolName = protoLabel,
                    transportType = transport,
                    adapterVoltageV = adapterVoltage,
                    supportedPidHexes = supportedPids.toSet(),
                )

                val slowPids = listOf(
                    "0105", "010F", "0103", "0106", "0107", "0108", "0109", "010A",
                    "010B", "010E", "0110", "0114", "011F", "0123", "012F", "0133",
                    "0142", "0143", "0145", "0146", "015C", "015E", "DTC_03", "DTC_07", "DTC_0A",
                ).filter { it.startsWith("DTC") || it in supportedPids }

                var cycle = 0
                while (isActive && connectionManager.connectionState.value == ObdConnectionState.CONNECTED) {
                    val now = System.currentTimeMillis()
                    val currentTel = _liveTelemetry.value

                    // Real-time fast path: RPM (010C) and Throttle (0111) every cycle for zero-lag Sound Studio
                    val rpm = if ("010C" in supportedPids) ObdPidParser.parseRpm(connectionManager.sendCommand("010C")) else null
                    val throttle = if ("0111" in supportedPids) ObdPidParser.parseThrottlePosPct(connectionManager.sendCommand("0111")) else null
                    val speed = if (cycle % 2 == 0 && "010D" in supportedPids) ObdPidParser.parseVehicleSpeedKmh(connectionManager.sendCommand("010D")) else null
                    val load = if (cycle % 2 == 1 && "0104" in supportedPids) ObdPidParser.parseEngineLoadPct(connectionManager.sendCommand("0104")) else null

                    var coolant = currentTel.coolantTempC
                    var intake = currentTel.intakeAirTempC
                    var fuelSys = currentTel.fuelSystemStatus
                    var stft = currentTel.shortTermFuelTrimPct
                    var ltft = currentTel.longTermFuelTrimPct
                    var stftB2 = currentTel.shortTermFuelTrimB2Pct
                    var ltftB2 = currentTel.longTermFuelTrimB2Pct
                    var fuelPressGauge = currentTel.fuelPressureGaugeKpa
                    var mapKpa = currentTel.mapKpa
                    var timing = currentTel.timingAdvanceDeg
                    var maf = currentTel.mafGramsPerSec
                    var o2v = currentTel.o2SensorVoltageV
                    var runtime = currentTel.runtimeSinceStartSec
                    var fuelPress = currentTel.fuelPressureKpa
                    var fuelLevel = currentTel.fuelLevelPct
                    var baroKpa = currentTel.barometricPressureKpa
                    var ctrlVolt = currentTel.controlModuleVoltageV
                    var absLoad = currentTel.absoluteLoadPct
                    var relThrottle = currentTel.relativeThrottlePct
                    var ambient = currentTel.ambientAirTempC
                    var oilTemp = currentTel.engineOilTempC
                    var fuelRate = currentTel.engineFuelRateLph
                    var dtcs = _state.value.dtcCodes
                    var pendingDtcs = currentTel.pendingDtcCodes
                    var permanentDtcs = currentTel.permanentDtcCodes
                    var dtcScanCompleted = currentTel.dtcScanCompleted
                    var lastDtcScanMillis = currentTel.lastDtcScanMillis

                    // Round-robin: poll at most ONE slow PID per cycle so RPM never stalls
                    if (slowPids.isNotEmpty()) {
                        when (val slowCmd = slowPids[cycle % slowPids.size]) {
                            "0105" -> coolant = ObdPidParser.parseCoolantTempC(connectionManager.sendCommand("0105")) ?: coolant
                            "010F" -> intake = ObdPidParser.parseIntakeAirTempC(connectionManager.sendCommand("010F")) ?: intake
                            "0103" -> fuelSys = ObdPidParser.parseFuelSystemStatus(connectionManager.sendCommand("0103")) ?: fuelSys
                            "0106" -> stft = ObdPidParser.parseShortTermFuelTrimPct(connectionManager.sendCommand("0106")) ?: stft
                            "0107" -> ltft = ObdPidParser.parseLongTermFuelTrimPct(connectionManager.sendCommand("0107")) ?: ltft
                            "0108" -> stftB2 = ObdPidParser.parseShortTermFuelTrimB2Pct(connectionManager.sendCommand("0108")) ?: stftB2
                            "0109" -> ltftB2 = ObdPidParser.parseLongTermFuelTrimB2Pct(connectionManager.sendCommand("0109")) ?: ltftB2
                            "010A" -> fuelPressGauge = ObdPidParser.parseFuelPressureGaugeKpa(connectionManager.sendCommand("010A")) ?: fuelPressGauge
                            "010B" -> mapKpa = ObdPidParser.parseMapKpa(connectionManager.sendCommand("010B")) ?: mapKpa
                            "010E" -> timing = ObdPidParser.parseTimingAdvanceDeg(connectionManager.sendCommand("010E")) ?: timing
                            "0110" -> maf = ObdPidParser.parseMafGramsPerSec(connectionManager.sendCommand("0110")) ?: maf
                            "0114" -> o2v = ObdPidParser.parseO2Voltage(connectionManager.sendCommand("0114")) ?: o2v
                            "011F" -> runtime = ObdPidParser.parseRuntimeSec(connectionManager.sendCommand("011F")) ?: runtime
                            "0123" -> fuelPress = ObdPidParser.parseFuelPressureKpa(connectionManager.sendCommand("0123")) ?: fuelPress
                            "012F" -> fuelLevel = ObdPidParser.parseFuelLevelPct(connectionManager.sendCommand("012F")) ?: fuelLevel
                            "0133" -> baroKpa = ObdPidParser.parseBarometricPressureKpa(connectionManager.sendCommand("0133")) ?: baroKpa
                            "0142" -> ctrlVolt = ObdPidParser.parseControlModuleVoltage(connectionManager.sendCommand("0142")) ?: ctrlVolt
                            "0143" -> absLoad = ObdPidParser.parseAbsoluteLoadPct(connectionManager.sendCommand("0143")) ?: absLoad
                            "0145" -> relThrottle = ObdPidParser.parseRelativeThrottlePct(connectionManager.sendCommand("0145")) ?: relThrottle
                            "0146" -> ambient = ObdPidParser.parseAmbientTempC(connectionManager.sendCommand("0146")) ?: ambient
                            "015C" -> oilTemp = ObdPidParser.parseEngineOilTempC(connectionManager.sendCommand("015C")) ?: oilTemp
                            "015E" -> fuelRate = ObdPidParser.parseEngineFuelRateLph(connectionManager.sendCommand("015E")) ?: fuelRate
                            "DTC_03" -> {
                                val mode03Resp = connectionManager.sendCommand("03")
                                if (!ObdPidParser.isAdapterError(mode03Resp) && mode03Resp.uppercase().replace(" ", "").contains("43")) {
                                    dtcs = ObdPidParser.parseDtcCodes(mode03Resp, "43")
                                    dtcScanCompleted = true
                                    lastDtcScanMillis = now
                                }
                            }
                            "DTC_07" -> {
                                val mode07Resp = connectionManager.sendCommand("07")
                                if (!ObdPidParser.isAdapterError(mode07Resp) && mode07Resp.uppercase().replace(" ", "").contains("47")) {
                                    pendingDtcs = ObdPidParser.parseDtcCodes(mode07Resp, "47")
                                }
                            }
                            "DTC_0A" -> {
                                val mode0AResp = connectionManager.sendCommand("0A")
                                if (!ObdPidParser.isAdapterError(mode0AResp) && mode0AResp.uppercase().replace(" ", "").contains("4A")) {
                                    permanentDtcs = ObdPidParser.parseDtcCodes(mode0AResp, "4A")
                                }
                            }
                            else -> Unit
                        }
                    }

                    val nextCoolantHistory = if (coolant != null && cycle % 4 == 0) {
                        (currentTel.coolantHistoryC + coolant).takeLast(20)
                    } else {
                        currentTel.coolantHistoryC
                    }
                    val ratio = if (rpm != null && speed != null && speed >= 10) {
                        rpm.toFloat() / speed.toFloat()
                    } else {
                        null
                    }
                    val transDtcs = ObdPidParser.filterTransmissionDtcs(dtcs)

                    _liveTelemetry.value = currentTel.copy(
                        engineRpm = rpm ?: currentTel.engineRpm,
                        vehicleSpeedKmh = speed ?: currentTel.vehicleSpeedKmh,
                        coolantTempC = coolant,
                        coolantHistoryC = nextCoolantHistory,
                        engineLoadPct = load ?: currentTel.engineLoadPct,
                        throttlePositionPct = throttle ?: currentTel.throttlePositionPct,
                        intakeAirTempC = intake,
                        fuelSystemStatus = fuelSys,
                        shortTermFuelTrimPct = stft,
                        longTermFuelTrimPct = ltft,
                        shortTermFuelTrimB2Pct = stftB2,
                        longTermFuelTrimB2Pct = ltftB2,
                        fuelPressureGaugeKpa = fuelPressGauge,
                        mapKpa = mapKpa,
                        timingAdvanceDeg = timing,
                        mafGramsPerSec = maf,
                        o2SensorVoltageV = o2v,
                        runtimeSinceStartSec = runtime,
                        fuelPressureKpa = fuelPress,
                        fuelLevelPct = fuelLevel,
                        barometricPressureKpa = baroKpa,
                        controlModuleVoltageV = ctrlVolt ?: currentTel.adapterVoltageV,
                        absoluteLoadPct = absLoad,
                        relativeThrottlePct = relThrottle,
                        ambientAirTempC = ambient,
                        engineOilTempC = oilTemp,
                        engineFuelRateLph = fuelRate,
                        pendingDtcCodes = pendingDtcs,
                        permanentDtcCodes = permanentDtcs,
                        dtcScanCompleted = dtcScanCompleted,
                        lastDtcScanMillis = lastDtcScanMillis,
                        supportedPidHexes = supportedPids.toSet(),
                        transmissionDtcCodes = transDtcs,
                        observedRpmSpeedRatio = ratio,
                        lastUpdatedMillis = now,
                    )

                    _state.value = _state.value.copy(
                        engineRpm = rpm ?: _state.value.engineRpm,
                        coolantTempC = coolant,
                        coolantTimestampMillis = if (coolant != null) now else _state.value.coolantTimestampMillis,
                        obdSpeedKmh = speed ?: _state.value.obdSpeedKmh,
                        dtcCodes = dtcs,
                    )

                    cycle++
                    delay(40L)
                }
            }
        }
    }

    suspend fun requestSafeDtcScan(): Boolean {
        if (connectionManager.connectionState.value != ObdConnectionState.CONNECTED) return false
        val now = System.currentTimeMillis()
        val mode03 = connectionManager.sendCommand("03")
        val mode07 = connectionManager.sendCommand("07")
        val mode0A = connectionManager.sendCommand("0A")
        val valid03 = !ObdPidParser.isAdapterError(mode03) && mode03.uppercase().replace(" ", "").contains("43")
        if (!valid03) return false
        val stored = ObdPidParser.parseDtcCodes(mode03, "43")
        val pending = ObdPidParser.parseDtcCodes(mode07, "47")
        val permanent = ObdPidParser.parseDtcCodes(mode0A, "4A")
        _liveTelemetry.value = _liveTelemetry.value.copy(
            pendingDtcCodes = pending,
            permanentDtcCodes = permanent,
            dtcScanCompleted = true,
            lastDtcScanMillis = now,
            transmissionDtcCodes = ObdPidParser.filterTransmissionDtcs(stored),
        )
        _state.value = _state.value.copy(dtcCodes = stored)
        return true
    }

    fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        connectionManager.disconnect()
        _state.value = _state.value.copy(
            obdConnected = false,
            statusMessage = "OBD-II adapter not connected",
        )
    }

    companion object {
        fun defaultCapabilityRegistry(supportedPids: Set<String>): List<ObdPidCapability> {
            fun statusFor(pid: String): PidSupportStatus =
                if (pid in supportedPids) PidSupportStatus.SUPPORTED else PidSupportStatus.UNSUPPORTED

            return listOf(
                ObdPidCapability("010C", "Engine RPM", "rpm", statusFor("010C")),
                ObdPidCapability("010D", "Vehicle Speed", "km/h", statusFor("010D")),
                ObdPidCapability("0105", "Coolant Temperature", "°C", statusFor("0105")),
                ObdPidCapability("0104", "Calculated Engine Load", "%", statusFor("0104")),
                ObdPidCapability("0111", "Throttle Position", "%", statusFor("0111")),
                ObdPidCapability("010F", "Intake Air Temperature", "°C", statusFor("010F")),
                ObdPidCapability("0103", "Fuel System Status", "", statusFor("0103")),
                ObdPidCapability("0106", "Short-Term Fuel Trim (B1)", "%", statusFor("0106")),
                ObdPidCapability("0107", "Long-Term Fuel Trim (B1)", "%", statusFor("0107")),
                ObdPidCapability("0108", "Short-Term Fuel Trim (B2)", "%", statusFor("0108")),
                ObdPidCapability("0109", "Long-Term Fuel Trim (B2)", "%", statusFor("0109")),
                ObdPidCapability("010A", "Fuel Pressure (Gauge)", "kPa", statusFor("010A")),
                ObdPidCapability("010B", "Manifold Absolute Pressure", "kPa", statusFor("010B")),
                ObdPidCapability("010E", "Timing Advance", "°", statusFor("010E")),
                ObdPidCapability("0110", "Mass Airflow (MAF)", "g/s", statusFor("0110")),
                ObdPidCapability("0114", "O2 Sensor B1S1", "V", statusFor("0114")),
                ObdPidCapability("011F", "Engine Runtime", "s", statusFor("011F")),
                ObdPidCapability("0123", "Fuel Rail Pressure", "kPa", statusFor("0123")),
                ObdPidCapability("012F", "Fuel Tank Level", "%", statusFor("012F")),
                ObdPidCapability("0133", "Barometric Pressure", "kPa", statusFor("0133")),
                ObdPidCapability("0142", "Control Module Voltage", "V", statusFor("0142")),
                ObdPidCapability("0143", "Absolute Engine Load", "%", statusFor("0143")),
                ObdPidCapability("0145", "Relative Throttle Position", "%", statusFor("0145")),
                ObdPidCapability("0146", "Ambient Air Temp", "°C", statusFor("0146")),
                ObdPidCapability("015C", "Engine Oil Temp", "°C", statusFor("015C")),
                ObdPidCapability("015E", "Engine Fuel Rate", "L/h", statusFor("015E")),
                ObdPidCapability("21/22-CVT", "CVT Fluid Temperature", "°C", PidSupportStatus.REQUIRES_MANUFACTURER_INTEGRATION),
                ObdPidCapability("21/22-LP", "Transmission Line Pressure", "kPa", PidSupportStatus.REQUIRES_MANUFACTURER_INTEGRATION),
                ObdPidCapability("21/22-TC", "Torque Converter Lockup", "", PidSupportStatus.REQUIRES_MANUFACTURER_INTEGRATION),
            )
        }
    }
}
