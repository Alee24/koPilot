package ke.co.kopilot.feature.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.database.preferences.DetectionQuality
import ke.co.kopilot.core.database.preferences.MicMode
import ke.co.kopilot.core.database.preferences.ResponseLength
import ke.co.kopilot.core.database.preferences.VoiceMode
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens

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
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val savedBackendUrl by viewModel.backendUrl.collectAsStateWithLifecycle()
    val savedOpenAiKey by viewModel.openAiKey.collectAsStateWithLifecycle()
    val savedGoogleKey by viewModel.googleMapsKey.collectAsStateWithLifecycle()

    var backendUrlInput by remember(savedBackendUrl) { mutableStateOf(savedBackendUrl) }
    var openAiKeyInput by remember(savedOpenAiKey) { mutableStateOf(savedOpenAiKey) }
    var googleKeyInput by remember(savedGoogleKey) { mutableStateOf(savedGoogleKey) }
    var showKeys by remember { mutableStateOf(false) }

    var plateInput by remember { mutableStateOf("") }
    var manufacturer by remember(vehicle.manufacturer) { mutableStateOf(vehicle.manufacturer) }
    var model by remember(vehicle.model) { mutableStateOf(vehicle.model) }
    var emergencyName by remember(settings.emergencyContactName) { mutableStateOf(settings.emergencyContactName) }
    var emergencyPhone by remember(settings.emergencyContactPhone) { mutableStateOf(settings.emergencyContactPhone) }

    Scaffold(
        containerColor = KoPilotClusterTokens.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KoPilotClusterTokens.Background,
                    titleContentColor = KoPilotClusterTokens.PrimaryText,
                    navigationIconContentColor = KoPilotClusterTokens.PrimaryText,
                ),
                title = {
                    Column {
                        Text("KoPilot Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = "Offline AI, Alerts, Number Plate Watchlist & Vehicle",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                    }
                },
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
            if (statusBanner != null) {
                SettingsCard {
                    Text(statusBanner!!, color = KoPilotClusterTokens.PrimaryAccent, fontWeight = FontWeight.SemiBold)
                }
            }

            // 1. Alert Preferences (including Traffic Lights & Number Plate Watchlist toggles)
            SettingsCard {
                SectionTitle("Alert Preferences", "Choose which spoken & visual alerts KoPilot announces.")
                SettingSwitch("Traffic Light & Brake Light Alerts", settings.alertTrafficLightsEnabled, viewModel::setAlertTrafficLightsEnabled)
                SettingSwitch("Number Plate Watchlist Alerts", settings.alertNumberPlatesEnabled, viewModel::setAlertNumberPlatesEnabled)
                SettingSwitch("Road Sign Alerts", settings.alertRoadSignsEnabled, viewModel::setAlertRoadSignsEnabled)
                SettingSwitch("Lane Drifting & Turning Alerts", settings.alertLaneTurnEnabled, viewModel::setAlertLaneTurnEnabled)
                SettingSwitch("Overspeed & Speed Limit Alerts", settings.alertSpeedLimitEnabled, viewModel::setAlertSpeedLimitEnabled)
                SettingSwitch("Weather & Headlight Advisories", settings.alertWeatherEnabled, viewModel::setAlertWeatherEnabled)
                SettingSwitch("Road Bump & Rough Road Alerts", settings.alertRoadQualityEnabled, viewModel::setAlertRoadQualityEnabled)
                SettingSwitch("Play Chime Before Alerts", settings.alertChimeEnabled, viewModel::setAlertChimeEnabled)
            }

            // 1b. Voice Studio & System Voice Selection
            SettingsCard {
                SectionTitle("Voice Selection", "Choose a preset or installed offline system voice for KoPilot.")
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    viewModel.availableVoices().forEach { voice ->
                        val selected = settings.aiVoiceName.equals(voice, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setAiVoiceName(voice) },
                            label = { Text(voice) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::testVoiceSound,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Test Selected Voice")
                }
            }

            // 2. Kenyan Number Plate Watchlist (100% offline OCR matching)
            SettingsCard {
                SectionTitle(
                    "Number Plate Watchlist",
                    "Add Kenyan number plates (white or yellow rear). KoPilot alerts you offline whenever a listed plate is spotted ahead.",
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = plateInput,
                        onValueChange = { plateInput = it.uppercase() },
                        label = { Text("e.g. KDM 632A") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (plateInput.isNotBlank()) {
                                viewModel.addWatchedPlate(plateInput)
                                plateInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KoPilotClusterTokens.PrimaryAccent,
                            contentColor = KoPilotClusterTokens.Background,
                        ),
                    ) {
                        Text("Add")
                    }
                }
                val watched = settings.watchedPlatesList()
                if (watched.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "No watched number plates added yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        watched.forEach { plate ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.removeWatchedPlate(plate) },
                                label = { Text(plate, fontWeight = FontWeight.Bold) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove $plate",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // 3. General & Speed Threshold
            SettingsCard {
                SectionTitle("General & Speed")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Spacer(Modifier.height(6.dp))
                Text("Overspeed Alert Threshold: +${settings.speedAlertThresholdKmh} km/h", color = KoPilotClusterTokens.PrimaryText)
                Slider(
                    value = settings.speedAlertThresholdKmh.toFloat(),
                    onValueChange = { viewModel.setSpeedThreshold(it.toInt()) },
                    valueRange = 0f..25f,
                )
            }

            // 4. Sensors & Hardware
            SettingsCard {
                SectionTitle("Sensors & Hardware", "Sensors activate automatically when you press Start Driving.")
                val allEnabled = settings.sensorCameraEnabled &&
                    settings.sensorGpsEnabled &&
                    settings.sensorMotionEnabled &&
                    settings.sensorMicEnabled &&
                    settings.sensorObdEnabled
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { viewModel.setAllSensorsEnabled(!allEnabled) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KoPilotClusterTokens.PrimaryAccent,
                        contentColor = KoPilotClusterTokens.Background,
                    ),
                ) {
                    Text(if (allEnabled) "Turn All Sensors OFF" else "Turn All Sensors ON")
                }
                Spacer(Modifier.height(6.dp))
                SettingSwitch("Camera (Road Signs, Lights & Plates)", settings.sensorCameraEnabled, viewModel::setSensorCameraEnabled)
                SettingSwitch("GPS Location & Speed", settings.sensorGpsEnabled, viewModel::setSensorGpsEnabled)
                SettingSwitch("Motion / IMU (Road Quality & Behaviour)", settings.sensorMotionEnabled, viewModel::setSensorMotionEnabled)
                SettingSwitch("Microphone (Voice Co-Pilot)", settings.sensorMicEnabled, viewModel::setSensorMicEnabled)
                SettingSwitch("OBD-II Vehicle Bluetooth", settings.sensorObdEnabled, viewModel::setSensorObdEnabled)
            }

            // 5. Camera & Offline Road Sign Vision
            SettingsCard {
                SectionTitle("Camera & Offline AI Vision", "Kenya left-hand carriageway aware; filters billboards.")
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetectionQuality.entries.forEach { q ->
                        FilterChip(
                            selected = settings.detectionQuality == q,
                            onClick = { viewModel.setDetectionQuality(q) },
                            label = { Text(q.name) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Camera Zoom (${"%.1f".format(settings.cameraZoomRatio)}x)", color = KoPilotClusterTokens.PrimaryText)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1.0f, 1.3f, 1.6f, 2.0f).forEach { z ->
                        FilterChip(
                            selected = kotlin.math.abs(settings.cameraZoomRatio - z) < 0.05f,
                            onClick = { viewModel.setCameraZoomRatio(z) },
                            label = { Text("${z}x") },
                        )
                    }
                }
                SettingSwitch("Announce All Road Signs Out Loud", settings.announceAllSigns, viewModel::setAnnounceAllSigns)
                SettingSwitch("Experimental Hazard Advisories", settings.hazardAdvisoriesEnabled, viewModel::setHazardAdvisories)
                Spacer(Modifier.height(6.dp))
                var showSignsDbDialog by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showSignsDbDialog = true }) {
                    Text("View Road Signs Database (${viewModel.roadSignDatabase.size} Signs)")
                }
                if (showSignsDbDialog) {
                    var signQuery by remember { mutableStateOf("") }
                    val filteredSigns = remember(signQuery) {
                        val q = signQuery.trim().lowercase()
                        viewModel.roadSignDatabase.filter {
                            q.isEmpty() || it.title.lowercase().contains(q) || it.spokenAdvice.lowercase().contains(q) || it.shape.lowercase().contains(q)
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { showSignsDbDialog = false },
                        title = { Text("Kenyan Road Signs Database (${viewModel.roadSignDatabase.size})") },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (viewModel.isCustomSignModelLoaded) {
                                        "Engine: Offline LiteRT YOLO11n + OCR + Shape CV"
                                    } else {
                                        "Engine: Hybrid Offline OCR + Shape/Symbol CV"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KoPilotClusterTokens.PrimaryAccent,
                                )
                                OutlinedTextField(
                                    value = signQuery,
                                    onValueChange = { signQuery = it },
                                    label = { Text("Search road sign...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                filteredSigns.forEach { entry ->
                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text("${entry.title} (${entry.code})", style = MaterialTheme.typography.titleSmall)
                                        Text("Shape: ${entry.shape}", style = MaterialTheme.typography.bodySmall, color = KoPilotClusterTokens.PrimaryAccent)
                                        Text("Meaning & Action: ${entry.spokenAdvice}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSignsDbDialog = false }) { Text("Close") }
                        },
                    )
                }
            }

            // 6. Vehicle & OBD-II
            SettingsCard {
                SectionTitle("Vehicle & OBD-II Diagnostics")
                Spacer(Modifier.height(6.dp))
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { viewModel.saveVehicleProfile(vehicle.copy(manufacturer = manufacturer, model = model)) },
                    ) {
                        Text("Save Vehicle Profile")
                    }
                    OutlinedButton(onClick = onOpenObd) {
                        Text("Open OBD-II & ECU Gauges")
                    }
                }
            }

            // 7. Navigation, Voice & Emergency Contact
            SettingsCard {
                SectionTitle("Navigation & Voice")
                Text("Co-Driver Voice Mode", color = KoPilotClusterTokens.PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoiceMode.entries.forEach { vm ->
                        val label = when (vm) {
                            VoiceMode.QUIET -> "Quiet (Warnings Only)"
                            VoiceMode.SMART_CO_DRIVER -> "Smart Co-Driver"
                            VoiceMode.LEARNING -> "Learning (Explain Once)"
                        }
                        FilterChip(
                            selected = settings.voiceMode == vm,
                            onClick = { viewModel.setVoiceMode(vm) },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                SettingSwitch("Avoid Tolls (e.g. Nairobi Expressway)", settings.avoidTolls, viewModel::setAvoidTolls)
                SettingSwitch("Traffic Reroute Alerts", settings.trafficAlertsEnabled, viewModel::setTrafficAlerts)
                SettingSwitch("Navigation Voice Prompts", settings.navVoiceGuidanceEnabled, viewModel::setNavVoice)
                Spacer(Modifier.height(6.dp))
                Text("AI Voice: ${settings.aiVoiceName}", color = KoPilotClusterTokens.PrimaryText)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("marin", "alloy", "ash", "coral", "echo", "sage", "shimmer", "verse").forEach { v ->
                        FilterChip(
                            selected = settings.aiVoiceName == v,
                            onClick = { viewModel.setAiVoiceName(v) },
                            label = { Text(v) },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MicMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.micMode == mode,
                            onClick = { viewModel.setMicMode(mode) },
                            label = { Text(mode.name.replace('_', ' ')) },
                        )
                    }
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

            SettingsCard {
                SectionTitle("Emergency Contact")
                Spacer(Modifier.height(6.dp))
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

            // 8. Optional Cloud Integrations & Data
            SettingsCard {
                SectionTitle(
                    "Optional Cloud APIs & Data",
                    "KoPilot offline AI, road signs, plate watchlist, and OBD-II work without internet.",
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = backendUrlInput,
                    onValueChange = { backendUrlInput = it },
                    label = { Text("Backend Server URL (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = openAiKeyInput,
                    onValueChange = { openAiKeyInput = it },
                    label = { Text("OpenAI API Key (Optional)") },
                    singleLine = true,
                    visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKeys = !showKeys }) {
                            Icon(if (showKeys) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle key visibility")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = googleKeyInput,
                    onValueChange = { googleKeyInput = it },
                    label = { Text("Google Maps / Places Key (Optional)") },
                    singleLine = true,
                    visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        viewModel.saveApiConfiguration(backendUrlInput, openAiKeyInput, googleKeyInput)
                        viewModel.testEnteredApiKeys(openAiKeyInput, googleKeyInput)
                    }) {
                        Text("Save & Connect")
                    }
                    OutlinedButton(onClick = { viewModel.testBackendConnection(backendUrlInput) }) {
                        Text("Test Server")
                    }
                    OutlinedButton(onClick = viewModel::clearApiCredentials) {
                        Text("Clear Keys")
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingSwitch("Save Local Trip History", settings.tripHistoryEnabled, viewModel::setTripHistory)
                SettingSwitch("Diagnostic Telemetry Opt-In", settings.telemetryOptIn, viewModel::setTelemetryOptIn)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = KoPilotClusterTokens.GlassCardSurface,
        border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Text(
        text = title,
        color = KoPilotClusterTokens.PrimaryText,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
    )
    if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = KoPilotClusterTokens.SecondaryText,
            fontSize = 12.sp,
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = KoPilotClusterTokens.PrimaryText,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
