package ke.co.kopilot.feature.diagnostics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    onOpenSoundStudio: () -> Unit = {},
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
                    OutlinedButton(onClick = onOpenSoundStudio) {
                        Text("Engine Sound Studio")
                    }
                }
            }

            // ECU Live Pointer Gauge Cluster (auto-loads all supported PIDs from ECU Capability Registry)
            ObdPanel {
                val supportedPids = capabilities.filter { it.status == PidSupportStatus.SUPPORTED }.map { it.pidHex }.toSet()
                val gaugeSpecs = buildGaugeSpecsForSupportedPids(supportedPids, telemetry)
                Text(
                    text = if (supportedPids.isNotEmpty()) {
                        "ECU Live Pointer Gauges (${supportedPids.size} Supported PIDs Active)"
                    } else {
                        "ECU Live Pointer Gauges (Standby)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                gaugeSpecs.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        pair.forEach { spec ->
                            EcuDialGauge(
                                title = spec.title,
                                valueText = spec.valueText,
                                unit = spec.unit,
                                fraction = spec.fraction,
                                accentColor = spec.accentColor,
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.size(124.dp))
                        }
                    }
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

            // Bluetooth OBD-II Devices Dropdown (below Extended ECU Sensors & Fuel/Air)
            ObdPanel {
                var dropdownExpanded by remember { mutableStateOf(false) }
                var selectedAddress by remember { mutableStateOf<String?>(null) }
                val selectedDevice = devices.firstOrNull { it.address == selectedAddress }
                    ?: devices.firstOrNull { it.isHhobdHint }
                    ?: devices.firstOrNull()

                Text("OBD-II Bluetooth Devices (${devices.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (devices.isEmpty()) {
                    Text(
                        "No Bluetooth devices found yet. Pair your HHOBD / ELM327 adapter in Android Bluetooth settings or tap Scan Classic + BLE above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                    ) {
                        val displayLabel = selectedDevice?.let { dev ->
                            val tag = if (dev.isHhobdHint) " [HHOBD]" else ""
                            "${dev.name}$tag (${dev.address})"
                        } ?: "Select Bluetooth OBD-II device"
                        OutlinedTextField(
                            value = displayLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select Bluetooth Adapter") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                        ) {
                            devices.forEach { dev ->
                                val tag = if (dev.isHhobdHint) " [HHOBD]" else ""
                                val pairedTag = if (dev.isPaired) " • Paired" else ""
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("${dev.name}$tag", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${dev.address} • ${dev.transportHint.name}$pairedTag",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedAddress = dev.address
                                        dropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = selectedDevice != null,
                            onClick = {
                                val target = selectedDevice ?: return@Button
                                if (btGranted) {
                                    viewModel.connect(target.address, true)
                                } else {
                                    permissionLauncher.launch(requiredBtPerms)
                                }
                            },
                        ) {
                            Text("Connect Selected")
                        }
                        OutlinedButton(
                            onClick = {
                                if (btGranted) viewModel.loadPairedDevices(true) else permissionLauncher.launch(requiredBtPerms)
                            },
                        ) {
                            Text("Refresh Paired")
                        }
                    }
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
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320),
        label = "ecuNeedle",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(128.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val startAngle = 150f
                val sweepTotal = 240f
                drawArc(
                    color = Color(0xFF233242),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal,
                    useCenter = false,
                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = accentColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * animatedFraction,
                    useCenter = false,
                    style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
                )
                // Tick marks around the 240° sweep
                val cx = size.width / 2f
                val cy = size.height / 2f
                val outerR = (size.minDimension / 2f) - 10.dp.toPx()
                val innerR = outerR - 6.dp.toPx()
                for (i in 0..8) {
                    val deg = startAngle + (sweepTotal * i / 8f)
                    val rad = (deg * PI / 180.0).toFloat()
                    drawLine(
                        color = Color(0xFF566B80),
                        start = Offset(cx + innerR * cos(rad), cy + innerR * sin(rad)),
                        end = Offset(cx + outerR * cos(rad), cy + outerR * sin(rad)),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
                // Animated moving pointer needle
                val needleDeg = startAngle + (sweepTotal * animatedFraction)
                val needleRad = (needleDeg * PI / 180.0).toFloat()
                val needleLen = (size.minDimension / 2f) - 12.dp.toPx()
                drawLine(
                    color = Color(0xFFFF5252),
                    start = Offset(cx, cy),
                    end = Offset(cx + needleLen * cos(needleRad), cy + needleLen * sin(needleRad)),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 34.dp),
            ) {
                Text(valueText, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(unit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class EcuGaugeSpec(
    val title: String,
    val valueText: String,
    val unit: String,
    val fraction: Float,
    val accentColor: Color,
)

private fun buildGaugeSpecsForSupportedPids(
    supportedPids: Set<String>,
    telemetry: ObdLiveTelemetry,
): List<EcuGaugeSpec> {
    val active = supportedPids.ifEmpty { setOf("010C", "010D", "0105", "0104", "0111", "0142") }
    val list = mutableListOf<EcuGaugeSpec>()
    if ("010C" in active) {
        val rpm = telemetry.engineRpm
        list += EcuGaugeSpec("ENGINE RPM", rpm?.toString() ?: "--", "rpm", ((rpm ?: 0) / 8000f).coerceIn(0f, 1f), if ((rpm ?: 0) >= 6000) Color(0xFFFF5252) else Color(0xFF18E2B8))
    }
    if ("010D" in active) {
        val spd = telemetry.vehicleSpeedKmh
        list += EcuGaugeSpec("ECU SPEED", spd?.toString() ?: "--", "km/h", ((spd ?: 0) / 220f).coerceIn(0f, 1f), Color(0xFF2B7FFF))
    }
    if ("0105" in active) {
        val cool = telemetry.coolantTempC
        list += EcuGaugeSpec("COOLANT TEMP", cool?.let { "$it°" } ?: "--", "°C", if (cool != null) ((cool - 20f) / 110f).coerceIn(0f, 1f) else 0f, if ((cool ?: 0) >= 102) Color(0xFFFF5252) else Color(0xFFFFB300))
    }
    if ("0104" in active) {
        val load = telemetry.engineLoadPct
        list += EcuGaugeSpec("ENGINE LOAD", load?.let { "${it.toInt()}%" } ?: "--", "%", ((load ?: 0f) / 100f).coerceIn(0f, 1f), Color(0xFF18E2B8))
    }
    if ("0111" in active) {
        val thr = telemetry.throttlePositionPct
        list += EcuGaugeSpec("THROTTLE", thr?.let { "${it.toInt()}%" } ?: "--", "%", ((thr ?: 0f) / 100f).coerceIn(0f, 1f), Color(0xFF2B7FFF))
    }
    if ("0142" in active) {
        val v = telemetry.controlModuleVoltageV ?: telemetry.adapterVoltageV
        list += EcuGaugeSpec("VOLTAGE", v?.let { "%.1f".format(it) } ?: "--", "V", if (v != null) ((v - 10f) / 6f).coerceIn(0f, 1f) else 0f, if (v != null && v < 11.8f) Color(0xFFFF5252) else Color(0xFF18E2B8))
    }
    if ("010F" in active) {
        val iat = telemetry.intakeAirTempC
        list += EcuGaugeSpec("INTAKE AIR", iat?.let { "$it°" } ?: "--", "°C", if (iat != null) ((iat + 10f) / 90f).coerceIn(0f, 1f) else 0f, Color(0xFFFFB300))
    }
    if ("0106" in active) {
        val stft = telemetry.shortTermFuelTrimPct
        list += EcuGaugeSpec("STFT (B1)", stft?.let { "${"%.1f".format(it)}%" } ?: "--", "%", if (stft != null) ((stft + 25f) / 50f).coerceIn(0f, 1f) else 0.5f, Color(0xFF18E2B8))
    }
    if ("0107" in active) {
        val ltft = telemetry.longTermFuelTrimPct
        list += EcuGaugeSpec("LTFT (B1)", ltft?.let { "${"%.1f".format(it)}%" } ?: "--", "%", if (ltft != null) ((ltft + 25f) / 50f).coerceIn(0f, 1f) else 0.5f, Color(0xFF2B7FFF))
    }
    if ("010E" in active) {
        val adv = telemetry.timingAdvanceDeg
        list += EcuGaugeSpec("TIMING ADV", adv?.let { "${"%.1f".format(it)}°" } ?: "--", "deg", if (adv != null) ((adv + 20f) / 80f).coerceIn(0f, 1f) else 0f, Color(0xFF18E2B8))
    }
    if ("0110" in active) {
        val maf = telemetry.mafGramsPerSec
        list += EcuGaugeSpec("MAF AIRFLOW", maf?.let { "%.1f".format(it) } ?: "--", "g/s", ((maf ?: 0f) / 150f).coerceIn(0f, 1f), Color(0xFF2B7FFF))
    }
    if ("011F" in active) {
        val rt = telemetry.runtimeSinceStartSec
        list += EcuGaugeSpec("RUNTIME", rt?.let { "${it / 60}m" } ?: "--", "${rt?.rem(60) ?: 0}s", ((rt ?: 0) / 3600f).coerceIn(0f, 1f), Color(0xFF18E2B8))
    }
    if ("0103" in active) {
        val fs = telemetry.fuelSystemStatus
        val closed = fs?.contains("Closed", ignoreCase = true) == true
        list += EcuGaugeSpec("FUEL SYS", if (fs != null) (if (closed) "CLOSED" else "OPEN") else "--", "0103", if (closed) 0.85f else 0.35f, if (closed) Color(0xFF18E2B8) else Color(0xFFFFB300))
    }
    if ("010B" in active) {
        val map = telemetry.mapKpa
        list += EcuGaugeSpec("MAP PRESS", map?.toString() ?: "--", "kPa", ((map ?: 0) / 255f).coerceIn(0f, 1f), Color(0xFF2B7FFF))
    }
    if ("0114" in active) {
        val o2 = telemetry.o2SensorVoltageV
        list += EcuGaugeSpec("O2 B1S1", o2?.let { "%.2f".format(it) } ?: "--", "V", ((o2 ?: 0f) / 1.2f).coerceIn(0f, 1f), Color(0xFF18E2B8))
    }
    if ("012F" in active) {
        val fl = telemetry.fuelLevelPct
        list += EcuGaugeSpec("FUEL LEVEL", fl?.let { "${it.toInt()}%" } ?: "--", "%", ((fl ?: 0f) / 100f).coerceIn(0f, 1f), if ((fl ?: 100f) < 15f) Color(0xFFFF5252) else Color(0xFF18E2B8))
    }
    return list
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
