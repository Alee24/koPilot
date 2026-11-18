package ke.co.kopilot.feature.diagnostics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ObdScreen(
    onBack: () -> Unit,
    viewModel: ObdViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val requiredBtPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    var btGranted by remember {
        mutableStateOf(
            requiredBtPerms.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        btGranted = requiredBtPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (btGranted) {
            viewModel.loadPairedDevices(true)
        }
    }

    LaunchedEffect(btGranted) {
        if (btGranted) {
            viewModel.loadPairedDevices(true)
        }
    }

    val connState by viewModel.connectionState.collectAsStateWithLifecycle()
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val telemetry by viewModel.liveTelemetry.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()
    val connLog by viewModel.connectionLog.collectAsStateWithLifecycle()
    val diag by viewModel.diagnosticsState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ECU Gauges & OBD-II") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!btGranted) {
                ObdPanel {
                    Text("Bluetooth Permission Required", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Grant Bluetooth scan & connect permissions to discover and connect to your HHOBD Advanced adapter.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(requiredBtPerms) }) {
                        Text("Grant Bluetooth Permissions")
                    }
                }
            }

            // Connection Status & Controls
            ObdPanel {
                val isBusy = connState == ObdConnectionState.CONNECTING ||
                    connState == ObdConnectionState.ADAPTER_INITIALIZING ||
                    connState == ObdConnectionState.ECU_CONNECTING ||
                    connState == ObdConnectionState.RECONNECTING
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = "OBD State: ${connState.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(statusMsg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                telemetry.adapterInfo?.let {
                    Text("Adapter: $it • ${telemetry.transportType.name}", style = MaterialTheme.typography.bodySmall)
                }
                telemetry.protocolName?.let {
                    Text(
                        "Protocol: $it • Voltage: ${telemetry.controlModuleVoltageV?.let { v -> "%.2fV".format(v) } ?: "--"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (connLog.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    connLog.takeLast(4).forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (btGranted) viewModel.startScan(true) else permissionLauncher.launch(requiredBtPerms)
                        },
                    ) {
                        Text(if (connState == ObdConnectionState.SCANNING) "Scanning..." else "Scan Classic + BLE")
                    }
                    if (connState == ObdConnectionState.SCANNING) {
                        OutlinedButton(onClick = viewModel::stopScan) {
                            Text("Stop")
                        }
                    }
                    if (diag.obdConnected || isBusy) {
                        OutlinedButton(onClick = viewModel::disconnect) {
                            Text("Disconnect")
                        }
                    }
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    }) {
                        Text("BT Settings")
                    }
                }
            }

            // Device Picker
            ObdPanel {
                Text("OBD-II Bluetooth Devices (${devices.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (devices.isEmpty()) {
                    Text(
                        "Pair your HHOBD / ELM327 adapter in Android Bluetooth settings or tap Scan Classic + BLE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    devices.forEach { dev ->
                        val onConnectDevice = {
                            if (btGranted) viewModel.connect(dev.address, true) else permissionLauncher.launch(requiredBtPerms)
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(onClick = onConnectDevice),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (dev.isHhobdHint) "${dev.name} [HHOBD Hint]" else dev.name,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "${dev.address} • ${dev.transportHint.name}${if (dev.rssiDbm != null) " • ${dev.rssiDbm} dBm" else ""}${if (dev.isPaired) " • Paired" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = onConnectDevice) {
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }
            }

            // ECU Circular Gauge Dashboard (RPM, Speed, Coolant, Load, Throttle, Voltage)
            ObdPanel {
                Text("ECU Live Gauge Cluster", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EcuDialGauge(
                        title = "ENGINE RPM",
                        valueText = telemetry.engineRpm?.toString() ?: "--",
                        unit = "rpm",
                        fraction = ((telemetry.engineRpm ?: 0) / 8000f).coerceIn(0f, 1f),
                        accentColor = if ((telemetry.engineRpm ?: 0) >= 6000) Color(0xFFFF5252) else Color(0xFF18E2B8),
                    )
                    EcuDialGauge(
                        title = "ECU SPEED",
                        valueText = telemetry.vehicleSpeedKmh?.toString() ?: "--",
                        unit = "km/h",
                        fraction = ((telemetry.vehicleSpeedKmh ?: 0) / 220f).coerceIn(0f, 1f),
                        accentColor = Color(0xFF2B7FFF),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val coolant = telemetry.coolantTempC
                    EcuDialGauge(
                        title = "COOLANT TEMP",
                        valueText = coolant?.let { "$it°" } ?: "--",
                        unit = "°C",
                        fraction = if (coolant != null) ((coolant - 20f) / 110f).coerceIn(0f, 1f) else 0f,
                        accentColor = if ((coolant ?: 0) >= 105) Color(0xFFFF5252) else Color(0xFFFFB300),
                    )
                    EcuDialGauge(
                        title = "ENGINE LOAD",
                        valueText = telemetry.engineLoadPct?.let { "${it.toInt()}%" } ?: "--",
                        unit = "0104",
                        fraction = ((telemetry.engineLoadPct ?: 0f) / 100f).coerceIn(0f, 1f),
                        accentColor = Color(0xFF18E2B8),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EcuDialGauge(
                        title = "THROTTLE",
                        valueText = telemetry.throttlePositionPct?.let { "${it.toInt()}%" } ?: "--",
                        unit = "0111",
                        fraction = ((telemetry.throttlePositionPct ?: 0f) / 100f).coerceIn(0f, 1f),
                        accentColor = Color(0xFF2B7FFF),
                    )
                    val volt = telemetry.controlModuleVoltageV ?: telemetry.adapterVoltageV
                    EcuDialGauge(
                        title = "VOLTAGE",
                        valueText = volt?.let { "%.1f".format(it) } ?: "--",
                        unit = "V",
                        fraction = if (volt != null) ((volt - 10f) / 6f).coerceIn(0f, 1f) else 0f,
                        accentColor = if (volt != null && volt < 11.8f) Color(0xFFFF5252) else Color(0xFF18E2B8),
                    )
                }
            }

            // Extended ECU Sensors Grid
            ObdPanel {
                Text("Extended ECU Sensors & Fuel/Air", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val metrics = listOf(
                    "Intake Air Temp" to (telemetry.intakeAirTempC?.let { "$it °C" } ?: "--"),
                    "Ambient Air Temp" to (telemetry.ambientAirTempC?.let { "$it °C" } ?: "--"),
                    "Fuel Level" to (telemetry.fuelLevelPct?.let { "${"%.1f".format(it)} %" } ?: "--"),
                    "Fuel Rail Press." to (telemetry.fuelPressureKpa?.let { "$it kPa" } ?: "--"),
                    "STFT (Bank 1)" to (telemetry.shortTermFuelTrimPct?.let { "${"%.1f".format(it)} %" } ?: "--"),
                    "LTFT (Bank 1)" to (telemetry.longTermFuelTrimPct?.let { "${"%.1f".format(it)} %" } ?: "--"),
                    "MAP Pressure" to (telemetry.mapKpa?.let { "$it kPa" } ?: "--"),
                    "MAF Airflow" to (telemetry.mafGramsPerSec?.let { "${"%.2f".format(it)} g/s" } ?: "--"),
                    "Timing Advance" to (telemetry.timingAdvanceDeg?.let { "${"%.1f".format(it)} °" } ?: "--"),
                    "O2 Sensor B1S1" to (telemetry.o2SensorVoltageV?.let { "${"%.2f".format(it)} V" } ?: "--"),
                    "Engine Runtime" to (telemetry.runtimeSinceStartSec?.let { "${it / 60}m ${it % 60}s" } ?: "--"),
                    "Fuel System" to (telemetry.fuelSystemStatus ?: "--"),
                )
                metrics.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { (label, value) ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                if (telemetry.coolantHistoryC.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Coolant Trend: ${telemetry.coolantHistoryC.takeLast(10).joinToString("° → ")}°C",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Fault Codes & Transmission / CVT
            ObdPanel {
                Text("Diagnostics, Fault Codes & CVT", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Engine/OBD DTCs (Mode 03): ${diag.dtcCodes.ifEmpty { listOf("None") }.joinToString(", ")}")
                Text("Transmission DTCs (P07xx–P09xx): ${telemetry.transmissionDtcCodes.ifEmpty { listOf("None") }.joinToString(", ")}")
                telemetry.observedRpmSpeedRatio?.let { ratio ->
                    Text("Observed RPM/Speed Ratio: ${"%.1f".format(ratio)} rpm per km/h")
                }
                Text(
                    telemetry.transmissionStatusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Live Adapter Handshake Log (for verifying real hardware connection)
            ObdPanel {
                Text("Adapter Handshake & Protocol Log (${connLog.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (connLog.isEmpty()) {
                    Text("No commands sent yet. Tap Connect on an OBD-II device above.", style = MaterialTheme.typography.bodySmall)
                } else {
                    connLog.forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }

            // Capability Registry
            ObdPanel {
                val supportedCount = capabilities.count { it.status == PidSupportStatus.SUPPORTED }
                Text("ECU Capability Registry ($supportedCount/${capabilities.size} supported)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                capabilities.forEach { cap ->
                    Text(
                        text = "${cap.pidHex} ${cap.name}: ${cap.status.name}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EcuDialGauge(
    title: String,
    valueText: String,
    unit: String,
    fraction: Float,
    accentColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(124.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val startAngle = 150f
                val sweepTotal = 240f
                drawArc(
                    color = Color(0xFF233242),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = accentColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(valueText, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text(unit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ObdPanel(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}
