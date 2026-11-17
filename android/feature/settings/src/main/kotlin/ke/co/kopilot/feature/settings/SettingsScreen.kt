package ke.co.kopilot.feature.settings

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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.database.preferences.DetectionQuality
import ke.co.kopilot.core.database.preferences.MicMode
import ke.co.kopilot.core.database.preferences.ResponseLength
import ke.co.kopilot.core.designsystem.component.CockpitPanel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenObd: () -> Unit = {},
    bluetoothPermissionGranted: Boolean,
    onExportJson: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val vehicle by viewModel.vehicleProfile.collectAsStateWithLifecycle()
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val savedBackendUrl by viewModel.backendUrl.collectAsStateWithLifecycle()
    val savedOpenAiKey by viewModel.openAiKey.collectAsStateWithLifecycle()
    val savedGoogleKey by viewModel.googleMapsKey.collectAsStateWithLifecycle()

    var backendUrlInput by remember(savedBackendUrl) { mutableStateOf(savedBackendUrl) }
    var openAiKeyInput by remember(savedOpenAiKey) { mutableStateOf(savedOpenAiKey) }
    var googleKeyInput by remember(savedGoogleKey) { mutableStateOf(savedGoogleKey) }
    var showKeys by remember { mutableStateOf(false) }

    var manufacturer by remember(vehicle.manufacturer) { mutableStateOf(vehicle.manufacturer) }
    var model by remember(vehicle.model) { mutableStateOf(vehicle.model) }
    var emergencyName by remember(settings.emergencyContactName) { mutableStateOf(settings.emergencyContactName) }
    var emergencyPhone by remember(settings.emergencyContactPhone) { mutableStateOf(settings.emergencyContactPhone) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KoPilot Settings") },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (statusBanner != null) {
                CockpitPanel {
                    Text(statusBanner!!, color = MaterialTheme.colorScheme.primary)
                }
            }

            // API Credentials & Server Configuration
            CockpitPanel {
                Text("API Credentials & Server", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Stored encrypted on-device. Enter your own OpenAI / Google keys or point to your KoPilot backend.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = backendUrlInput,
                    onValueChange = { backendUrlInput = it },
                    label = { Text("Backend Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = openAiKeyInput,
                    onValueChange = { openAiKeyInput = it },
                    label = { Text("OpenAI API Key (sk-...)") },
                    singleLine = true,
                    visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKeys = !showKeys }) {
                            Icon(
                                if (showKeys) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle visibility",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = googleKeyInput,
                    onValueChange = { googleKeyInput = it },
                    label = { Text("Google Routes / Places API Key (AIza...)") },
                    singleLine = true,
                    visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                val openAiReady = openAiKeyInput.trim().startsWith("sk-")
                val googleReady = googleKeyInput.trim().startsWith("AIza")
                Text(
                    text = "Status • OpenAI: ${if (openAiReady) "Configured" else "Not set"} | Google Maps: ${if (googleReady) "Configured" else "Not set"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveApiConfiguration(backendUrlInput, openAiKeyInput, googleKeyInput)
                            viewModel.testEnteredApiKeys(openAiKeyInput, googleKeyInput)
                        },
                    ) {
                        Text("Save & Connect")
                    }
                    OutlinedButton(onClick = { viewModel.testBackendConnection(backendUrlInput) }) {
                        Text("Test Server")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenObd) {
                        Text("Connect OBD-II Page")
                    }
                    OutlinedButton(onClick = viewModel::clearApiCredentials) {
                        Text("Clear Keys")
                    }
                }
            }

            // General
            CockpitPanel {
                Text("General", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.language == "en",
                        onClick = { viewModel.setLanguage("en") },
                        label = { Text("English") },
                    )
                    FilterChip(
                        selected = settings.language == "sw",
                        onClick = { viewModel.setLanguage("sw") },
                        label = { Text("Kiswahili") },
                    )
                }
                SettingSwitch("Dark Mode (Recommended for Driving)", settings.darkTheme, viewModel::setDarkTheme)
                Text("Overspeed Alert Threshold: +${settings.speedAlertThresholdKmh} km/h")
                Slider(
                    value = settings.speedAlertThresholdKmh.toFloat(),
                    onValueChange = { viewModel.setSpeedThreshold(it.toInt()) },
                    valueRange = 0f..20f,
                )
            }

            // Sensors & Hardware (only started when Start Driving is pressed)
            CockpitPanel {
                val allEnabled = settings.sensorCameraEnabled &&
                    settings.sensorGpsEnabled &&
                    settings.sensorMotionEnabled &&
                    settings.sensorMicEnabled &&
                    settings.sensorObdEnabled
                Text("Sensors & Hardware", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sensors stay idle until you press Start Driving. Choose which sensors activate during a trip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.setAllSensorsEnabled(!allEnabled) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (allEnabled) "Turn All Sensors OFF" else "Turn All Sensors ON")
                    }
                }
                Spacer(Modifier.height(6.dp))
                SettingSwitch("Camera (Road Signs & Traffic Lights)", settings.sensorCameraEnabled, viewModel::setSensorCameraEnabled)
                SettingSwitch("GPS Location & Speed", settings.sensorGpsEnabled, viewModel::setSensorGpsEnabled)
                SettingSwitch("Motion / IMU (Accelerometer & Gyro)", settings.sensorMotionEnabled, viewModel::setSensorMotionEnabled)
                SettingSwitch("Microphone (Voice Co-Pilot)", settings.sensorMicEnabled, viewModel::setSensorMicEnabled)
                SettingSwitch("OBD-II Vehicle Bluetooth", settings.sensorObdEnabled, viewModel::setSensorObdEnabled)
            }

            // Alert Preferences
            CockpitPanel {
                Text("Alert Preferences", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose which spoken & visual alerts KoPilot announces while driving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SettingSwitch("Road Sign Alerts", settings.alertRoadSignsEnabled, viewModel::setAlertRoadSignsEnabled)
                SettingSwitch("Lane Drifting & Turning Alerts", settings.alertLaneTurnEnabled, viewModel::setAlertLaneTurnEnabled)
                SettingSwitch("Traffic Light & Brake Light Alerts", settings.alertTrafficLightsEnabled, viewModel::setAlertTrafficLightsEnabled)
                SettingSwitch("Overspeed & Speed Camera Alerts", settings.alertSpeedLimitEnabled, viewModel::setAlertSpeedLimitEnabled)
                SettingSwitch("Weather & Headlight Advisories", settings.alertWeatherEnabled, viewModel::setAlertWeatherEnabled)
            }

            // Camera & Vision
            CockpitPanel {
                Text("Camera & Road Sign Vision", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Mount phone securely on dashboard or windscreen with an unobstructed view of the road ahead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetectionQuality.entries.forEach { q ->
                        FilterChip(
                            selected = settings.detectionQuality == q,
                            onClick = { viewModel.setDetectionQuality(q) },
                            label = { Text(q.name) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Camera Zoom for Road Signs (${"%.1f".format(settings.cameraZoomRatio)}x)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0f, 1.3f, 1.6f, 2.0f).forEach { z ->
                        FilterChip(
                            selected = kotlin.math.abs(settings.cameraZoomRatio - z) < 0.05f,
                            onClick = { viewModel.setCameraZoomRatio(z) },
                            label = { Text("${z}x") },
                        )
                    }
                }
                SettingSwitch(
                    "Announce All Road Signs Out Loud",
                    settings.announceAllSigns,
                    viewModel::setAnnounceAllSigns,
                )
                SettingSwitch(
                    "Experimental Hazard Advisories (Unvalidated)",
                    settings.hazardAdvisoriesEnabled,
                    viewModel::setHazardAdvisories,
                )
            }

            // Navigation
            CockpitPanel {
                Text("Navigation", style = MaterialTheme.typography.titleMedium)
                SettingSwitch("Avoid Tolls (e.g. Nairobi Expressway)", settings.avoidTolls, viewModel::setAvoidTolls)
                SettingSwitch("Traffic Reroute Alerts", settings.trafficAlertsEnabled, viewModel::setTrafficAlerts)
                SettingSwitch("Navigation Voice Prompts", settings.navVoiceGuidanceEnabled, viewModel::setNavVoice)
            }

            // AI Assistant & Sound
            CockpitPanel {
                Text("AI Voice & Sound", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Realtime AI Voice: ${settings.aiVoiceName}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("marin", "alloy", "ash", "coral", "echo", "sage", "shimmer", "verse").forEach { v ->
                        FilterChip(
                            selected = settings.aiVoiceName == v,
                            onClick = { viewModel.setAiVoiceName(v) },
                            label = { Text(v) },
                        )
                    }
                }
                Text("Speech Rate: ${"%.1f".format(settings.voiceSpeed)}x")
                Slider(
                    value = settings.voiceSpeed,
                    onValueChange = viewModel::setVoiceSpeed,
                    valueRange = 0.6f..1.5f,
                )
                SettingSwitch("Play Alert Chime Before Warnings", settings.alertChimeEnabled, viewModel::setAlertChimeEnabled)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MicMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.micMode == mode,
                            onClick = { viewModel.setMicMode(mode) },
                            label = { Text(mode.name.replace('_', ' ')) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResponseLength.entries.forEach { len ->
                        FilterChip(
                            selected = settings.responseLength == len,
                            onClick = { viewModel.setResponseLength(len) },
                            label = { Text(len.name) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = viewModel::testVoiceSound) {
                    Text("Test Voice & Chime Sound")
                }
            }

            // Vehicle & OBD-II
            CockpitPanel {
                Text("Vehicle & OBD-II Diagnostics", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manufacturer,
                    onValueChange = { manufacturer = it },
                    label = { Text("Manufacturer (e.g. Toyota)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model (e.g. Fielder)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.saveVehicleProfile(vehicle.copy(manufacturer = manufacturer, model = model))
                    },
                ) {
                    Text("Save Vehicle Profile")
                }
                Spacer(Modifier.height(8.dp))
                val obdConnState by viewModel.obdConnectionState.collectAsStateWithLifecycle()
                val discoveredDevices by viewModel.discoveredObdDevices.collectAsStateWithLifecycle()
                val obdTel by viewModel.liveObdTelemetry.collectAsStateWithLifecycle()
                val obdCaps by viewModel.obdCapabilities.collectAsStateWithLifecycle()
                val obdMsg by viewModel.obdStatusMessage.collectAsStateWithLifecycle()
                var showCapabilityDialog by remember { mutableStateOf(false) }

                Text(
                    text = "OBD State: ${obdConnState.name} • $obdMsg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.startObdDiscovery(bluetoothPermissionGranted) }) {
                        Text("Scan Classic + BLE")
                    }
                    OutlinedButton(onClick = { viewModel.disconnectObd() }) {
                        Text("Disconnect")
                    }
                }
                if (discoveredDevices.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    discoveredDevices.forEach { dev ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = buildString {
                                        append(dev.name)
                                        if (dev.isHhobdHint) append(" [HHOBD Hint]")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${dev.address} • ${dev.transportHint.name}${if (dev.rssiDbm != null) " • ${dev.rssiDbm} dBm" else ""}${if (dev.isPaired) " • Paired" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { viewModel.connectObd(dev.address, bluetoothPermissionGranted) }) {
                                Text("Connect")
                            }
                        }
                    }
                }
                if (obdTel.lastUpdatedMillis != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "RPM: ${obdTel.engineRpm ?: "--"} • Speed: ${obdTel.vehicleSpeedKmh ?: "--"} km/h • Coolant: ${obdTel.coolantTempC ?: "--"}°C • Load: ${obdTel.engineLoadPct?.toInt() ?: "--"}% • Volt: ${obdTel.controlModuleVoltageV?.let { "%.1fV".format(it) } ?: "--"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = obdTel.transmissionStatusNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { showCapabilityDialog = true }) {
                    Text("View ECU Capabilities & Engine/CVT Data")
                }
                if (showCapabilityDialog) {
                    AlertDialog(
                        onDismissRequest = { showCapabilityDialog = false },
                        title = { Text("ECU Capability Registry & CVT") },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Transmission DTCs: ${obdTel.transmissionDtcCodes.ifEmpty { listOf("None") }.joinToString(", ")}")
                                obdTel.observedRpmSpeedRatio?.let { ratio ->
                                    Text("Observed RPM/Speed Ratio: ${"%.1f".format(ratio)} rpm per km/h")
                                }
                                Spacer(Modifier.height(4.dp))
                                obdCaps.forEach { cap ->
                                    Text(
                                        text = "${cap.pidHex} ${cap.name}: ${cap.status.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showCapabilityDialog = false }) { Text("Close") }
                        },
                    )
                }
            }

            // Emergency Contact
            CockpitPanel {
                Text("Emergency Contact", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emergencyName,
                    onValueChange = { emergencyName = it },
                    label = { Text("Contact Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emergencyPhone,
                    onValueChange = { emergencyPhone = it },
                    label = { Text("Phone Number") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.setEmergencyContact(emergencyName, emergencyPhone) }) {
                    Text("Save Emergency Contact")
                }
            }

            // Privacy & Data
            CockpitPanel {
                Text("Privacy & Kenya Data Protection", style = MaterialTheme.typography.titleMedium)
                SettingSwitch("Save Local Trip History", settings.tripHistoryEnabled, viewModel::setTripHistory)
                SettingSwitch("Diagnostic Telemetry Opt-In", settings.telemetryOptIn, viewModel::setTelemetryOptIn)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.exportTripsJson(onExportJson) }) {
                        Text("Export Trip Data")
                    }
                    Button(
                        onClick = { viewModel.deleteAllData() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Delete All Data")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
