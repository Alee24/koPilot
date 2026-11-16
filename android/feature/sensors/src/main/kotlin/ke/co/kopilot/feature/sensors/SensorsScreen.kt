package ke.co.kopilot.feature.sensors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.designsystem.component.CockpitPanel
import ke.co.kopilot.core.designsystem.theme.KoPilotColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SensorsScreen(
    onBack: () -> Unit,
    viewModel: SensorsViewModel = hiltViewModel(),
) {
    val hub by viewModel.hubState.collectAsStateWithLifecycle()
    val loc by viewModel.locationState.collectAsStateWithLifecycle()
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sensors & Motion Fusion") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshCapabilities) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Hardware")
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
            // Controls & Sampling Rate
            CockpitPanel {
                Text("Live Sensor Monitoring", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hub.isMonitoring) {
                        OutlinedButton(onClick = viewModel::stopMonitoring) {
                            Text("Stop Listeners")
                        }
                    } else {
                        Button(onClick = { viewModel.startMonitoring() }) {
                            Text("Start Monitoring")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SensorSamplingProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = hub.samplingProfile == profile,
                            onClick = { viewModel.setSamplingProfile(profile) },
                            label = { Text(profile.label) },
                        )
                    }
                }
            }

            // Driving Motion Intelligence
            CockpitPanel {
                val m = hub.motionAnalysis
                Text("Driving Motion Intelligence", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Longitudinal: ${m.longitudinalState.name} (${"%.2f".format(m.longitudinalAccelMps2)} m/s²)")
                Text("Lateral / Turn: ${m.lateralState.name} (${"%.2f".format(m.yawRateRadPerSec)} rad/s)")
                Text(
                    "Orientation: Azimuth ${"%.0f".format(m.azimuthDegrees)}° • Pitch ${"%.0f".format(m.pitchDegrees)}° • Roll ${"%.0f".format(m.rollDegrees)}°",
                )
                Text(
                    "Road Vibration (Experimental): ${m.roadVibrationLevel.name} (RMS ${"%.2f".format(m.verticalVibrationRmsMps2)} m/s²)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (m.phoneMovementDetected) {
                    Text("Phone movement detected", color = KoPilotColors.WarningAmber, fontWeight = FontWeight.SemiBold)
                }
                if (m.suddenMovementDetected) {
                    Text("Sudden impact/jerk detected", color = KoPilotColors.DangerRed, fontWeight = FontWeight.Bold)
                }
            }

            // GNSS Satellites & Freshness
            CockpitPanel {
                val fix = loc.currentFix
                Text("GNSS & Location Intelligence", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Satellites: ${loc.satellitesUsedInFix} used / ${loc.satellitesInView} in view (Avg C/N0: ${"%.1f".format(loc.averageCn0DbHz)} dB-Hz)")
                if (fix != null) {
                    val ageSec = ((System.currentTimeMillis() - fix.timestampMillis) / 1000L).coerceAtLeast(0L)
                    Text("Coordinates: ${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}")
                    Text("Horizontal Accuracy: ±${fix.horizontalAccuracyMeters?.let { "%.1f".format(it) } ?: "--"} m • Freshness: ${ageSec}s ago")
                } else {
                    Text("GPS fix unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Physical Sensors
            Text("Physical Hardware Sensors (${hub.physicalSensors.count { it.isAvailable }} available)", style = MaterialTheme.typography.titleMedium)
            hub.physicalSensors.forEach { s ->
                CockpitPanel {
                    Text("${s.typeName} — ${s.status.name}", fontWeight = FontWeight.SemiBold)
                    Text("${s.name} (${s.vendor})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Reading: ${s.formattedReading}", color = if (s.isAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.isAvailable) {
                        val ts = if (s.timestampMillis > 0L) timeFmt.format(Date(s.timestampMillis)) else "--"
                        Text(
                            "Accuracy: ${s.accuracyLabel} • Rate: ${"%.0f".format(s.samplingHz)} Hz • Power: ${s.powerMa} mA • Updated: $ts",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Calibration: ${s.calibrationNote}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Camera, Audio & Connectivity (Separate from physical sensors)
            Text("Camera, Audio & Connectivity Interfaces", style = MaterialTheme.typography.titleMedium)
            CockpitPanel {
                Text("Cameras (${hub.cameras.size})", fontWeight = FontWeight.SemiBold)
                hub.cameras.forEach { cam ->
                    Text(
                        "ID ${cam.cameraId} (${cam.lensFacing}): Focal ${cam.focalLengthsMm.joinToString()} mm • AF=${cam.autofocusSupported} • OIS=${cam.opticalStabilizationSupported} • EIS=${cam.videoStabilizationSupported}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                hub.audio?.let { a ->
                    Text("Audio: Mic=${a.microphoneFeaturePresent} • BT Audio=${a.bluetoothAudioConnected}", fontWeight = FontWeight.SemiBold)
                    Text("Inputs: ${a.inputDevices.joinToString().ifBlank { "None" }}", style = MaterialTheme.typography.labelMedium)
                    Text("Outputs: ${a.outputDevices.joinToString().ifBlank { "None" }}", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(6.dp))
                hub.connectivity?.let { c ->
                    Text("Connectivity", fontWeight = FontWeight.SemiBold)
                    Text("BT Classic=${c.bluetoothClassicSupported} (On=${c.bluetoothEnabled}) • BLE=${c.bleSupported}", style = MaterialTheme.typography.bodyMedium)
                    Text("Wi-Fi=${c.wifiSupported} (Connected=${c.wifiConnected}) • Cellular=${c.cellularAvailable}", style = MaterialTheme.typography.bodyMedium)
                    Text("USB Host=${c.usbHostSupported} • USB Acc=${c.usbAccessorySupported} (Attached=${c.attachedUsbDeviceCount}) • NFC=${c.nfcSupported} (On=${c.nfcEnabled})", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
