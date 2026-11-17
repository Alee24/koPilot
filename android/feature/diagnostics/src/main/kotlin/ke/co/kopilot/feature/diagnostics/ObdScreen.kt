package ke.co.kopilot.feature.diagnostics

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
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
    val diag by viewModel.diagnosticsState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OBD-II Connection & Engine") },
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

            // Connection Status Panel
            ObdPanel {
                Text("Adapter Status: ${connState.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(statusMsg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                telemetry.adapterInfo?.let {
                    Text("Adapter: $it • ${telemetry.transportType.name}", style = MaterialTheme.typography.bodySmall)
                }
                telemetry.protocolName?.let {
                    Text("Protocol: $it • Battery: ${telemetry.controlModuleVoltageV?.let { v -> "%.2fV".format(v) } ?: "--"}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (diag.obdConnected || connState == ObdConnectionState.CONNECTING || connState == ObdConnectionState.RECONNECTING) {
                        OutlinedButton(onClick = viewModel::disconnect) {
                            Text("Disconnect")
                        }
                    }
                }
            }

            // Device Selection Panel
            ObdPanel {
                Text("Available OBD-II Devices (${devices.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (devices.isEmpty()) {
                    Text(
                        "No Bluetooth devices listed yet. Pair your HHOBD adapter in Android Bluetooth settings or tap Scan Classic + BLE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    devices.forEach { dev ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                                Button(
                                    onClick = {
                                        if (btGranted) viewModel.connect(dev.address, true) else permissionLauncher.launch(requiredBtPerms)
                                    },
                                ) {
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }
            }

            // Live Engine Telemetry
            ObdPanel {
                Text("Live Engine Telemetry", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                val rpm = telemetry.engineRpm ?: 0
                Text("Engine RPM (010C): ${telemetry.engineRpm ?: "--"} rpm", fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(
                    progress = { (rpm / 7000f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Text("Vehicle Speed (010D): ${telemetry.vehicleSpeedKmh ?: "--"} km/h")
                Text("Coolant Temp (0105): ${telemetry.coolantTempC?.let { "$it°C" } ?: "--"}")
                if (telemetry.coolantHistoryC.isNotEmpty()) {
                    Text(
                        "Coolant Trend: ${telemetry.coolantHistoryC.takeLast(8).joinToString("° → ")}°C",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Engine Load (0104): ${telemetry.engineLoadPct?.let { "${"%.1f".format(it)}%" } ?: "--"} | Throttle (0111): ${telemetry.throttlePositionPct?.let { "${"%.1f".format(it)}%" } ?: "--"}")
                Text("Intake Temp (010F): ${telemetry.intakeAirTempC?.let { "$it°C" } ?: "--"} | Ambient (0146): ${telemetry.ambientAirTempC?.let { "$it°C" } ?: "--"}")
                Text("STFT: ${telemetry.shortTermFuelTrimPct?.let { "${"%.1f".format(it)}%" } ?: "--"} | LTFT: ${telemetry.longTermFuelTrimPct?.let { "${"%.1f".format(it)}%" } ?: "--"} | MAP: ${telemetry.mapKpa ?: "--"} kPa")
                Text("MAF: ${telemetry.mafGramsPerSec?.let { "${"%.2f".format(it)} g/s" } ?: "--"} | Timing: ${telemetry.timingAdvanceDeg?.let { "${"%.1f".format(it)}°" } ?: "--"} | O2: ${telemetry.o2SensorVoltageV?.let { "${"%.2f".format(it)}V" } ?: "--"}")
                Text("Fuel Level: ${telemetry.fuelLevelPct?.let { "${"%.1f".format(it)}%" } ?: "--"} | Runtime: ${telemetry.runtimeSinceStartSec ?: "--"}s")
                Text("Active Fault Codes (Mode 03): ${diag.dtcCodes.ifEmpty { listOf("None") }.joinToString(", ")}")
            }

            // Transmission & CVT
            ObdPanel {
                Text("Transmission & CVT Monitoring", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
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
