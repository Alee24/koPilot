package ke.co.kopilot.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveApiConfiguration(backendUrlInput, openAiKeyInput, googleKeyInput)
                        },
                    ) {
                        Text("Save Keys")
                    }
                    OutlinedButton(onClick = { viewModel.testBackendConnection(backendUrlInput) }) {
                        Text("Test Backend")
                    }
                    OutlinedButton(onClick = viewModel::clearApiCredentials) {
                        Text("Clear")
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

            // AI Assistant
            CockpitPanel {
                Text("AI Voice Assistant", style = MaterialTheme.typography.titleMedium)
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
                OutlinedButton(onClick = { viewModel.refreshPairedBluetoothDevices(bluetoothPermissionGranted) }) {
                    Text("Scan Paired OBD-II Bluetooth Devices")
                }
                pairedDevices.forEach { dev ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${dev.name} (${dev.address})", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { viewModel.connectObd(dev.address, bluetoothPermissionGranted) }) {
                            Text("Connect")
                        }
                    }
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
