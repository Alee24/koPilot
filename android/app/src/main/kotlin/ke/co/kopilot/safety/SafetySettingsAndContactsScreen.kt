package ke.co.kopilot.safety

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ke.co.kopilot.core.database.preferences.PreferencesRepository
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.dashboard.DashboardUiState
import ke.co.kopilot.feature.sensors.AccidentAssessment
import ke.co.kopilot.feature.sensors.AccidentEventClass
import ke.co.kopilot.feature.sensors.MountCalibrationStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SafetyAndEmergencyScreen(
    state: DashboardUiState,
    prefsRepo: PreferencesRepository,
    onRecalibrateMount: () -> Unit,
    onTestEmergencyWorkflow: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenAccidentDiagnostics: () -> Unit = {},
    onMarkFalseAlarm: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = state.settings
    val contacts = remember(s.emergencyContactsJson) {
        EmergencySmsManager.parseContacts(s.emergencyContactsJson)
    }
    val history = remember(s.emergencyHistoryJson) {
        EmergencySmsManager.parseHistory(s.emergencyHistoryJson)
    }
    var showSmsOptInDialog by remember { mutableStateOf(false) }

    val smsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scope.launch {
            prefsRepo.setEmergencySmsEnabled(true)
        }
    }

    if (showSmsOptInDialog) {
        AlertDialog(
            onDismissRequest = { showSmsOptInDialog = false },
            title = { Text("Enable Automatic Emergency SMS?") },
            text = {
                Text(
                    "If a high-confidence possible accident is detected and not cancelled within ${s.emergencyCountdownSeconds} seconds, " +
                        "KoPilot will attempt to send an SMS with your GPS location and speed to your configured contacts. " +
                        "SMS requires cellular signal and a functioning SIM; delivery is not guaranteed in all situations.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showSmsOptInDialog = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(onClick = {
                    showSmsOptInDialog = false
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        scope.launch { prefsRepo.setEmergencySmsEnabled(true) }
                    } else {
                        smsPermLauncher.launch(Manifest.permission.SEND_SMS)
                    }
                }) {
                    Text("I Understand, Enable")
                }
            },
        )
    }

    Scaffold(
        containerColor = KoPilotClusterTokens.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KoPilotClusterTokens.Background,
                    titleContentColor = KoPilotClusterTokens.PrimaryText,
                    navigationIconContentColor = KoPilotClusterTokens.PrimaryText,
                ),
                title = { Text("Safety & Emergency", fontWeight = FontWeight.Bold) },
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
            // Sensor Health & Mount Calibration Card
            SafetyCard(title = "Sensor Health & Mount Calibration") {
                val mountColor = when (state.mountCalibrationStatus) {
                    MountCalibrationStatus.CALIBRATED -> KoPilotClusterTokens.SmoothRoad
                    MountCalibrationStatus.CALIBRATION_REQUIRED -> KoPilotClusterTokens.ModerateRoad
                    MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED -> KoPilotClusterTokens.RoughRoad
                    MountCalibrationStatus.SENSOR_UNAVAILABLE -> Color(0xFFEF4444)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Phone Mount", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold)
                        Text(state.mountCalibrationStatus.label, color = mountColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = onRecalibrateMount) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Recalibrate")
                    }
                }
                Spacer(Modifier.height(6.dp))
                val fix = state.locationState.currentFix
                Text(
                    text = "GPS: ${fix?.let { "±${it.horizontalAccuracyMeters?.toInt() ?: "--"}m (${it.speedKmh?.toInt() ?: 0} km/h)" } ?: "Acquiring"} • " +
                        "OBD-II: ${if (state.diagnosticsState.obdConnected) "Connected" else "Offline"} • " +
                        "Service: ${if (DrivingSafetyMonitoringService.isRunning.value) "Active" else "Standby"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            }

            // Emergency & Accident Alerts Card
            SafetyCard(title = "Emergency & Accident Alerts") {
                SafetySwitchRow("Accident Monitoring", s.accidentMonitoringEnabled) { v ->
                    scope.launch { prefsRepo.setAccidentMonitoringEnabled(v) }
                }
                SafetySwitchRow("Emergency SMS Alerts", s.emergencySmsEnabled) { v ->
                    if (v) showSmsOptInDialog = true else scope.launch { prefsRepo.setEmergencySmsEnabled(false) }
                }
                SafetySwitchRow("Voice Warnings", s.accidentVoiceWarningsEnabled) { v ->
                    scope.launch { prefsRepo.setAccidentVoiceWarningsEnabled(v) }
                }
                SafetySwitchRow("Vibration Feedback", s.accidentVibrationEnabled) { v ->
                    scope.launch { prefsRepo.setAccidentVibrationEnabled(v) }
                }

                Text("Countdown Duration", color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20).forEach { sec ->
                        FilterChip(
                            selected = s.emergencyCountdownSeconds == sec,
                            onClick = { scope.launch { prefsRepo.setEmergencyCountdownSeconds(sec) } },
                            label = { Text("${sec}s") },
                        )
                    }
                }

                Text("Detection Sensitivity", color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("LOW" to "Conservative", "STANDARD" to "Standard", "HIGH" to "Sensitive").forEach { (key, label) ->
                        FilterChip(
                            selected = s.accidentSensitivity == key,
                            onClick = { scope.launch { prefsRepo.setAccidentSensitivity(key) } },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onOpenContacts, modifier = Modifier.weight(1f)) {
                        Text("Contacts (${contacts.size})")
                    }
                    Button(
                        onClick = onTestEmergencyWorkflow,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = KoPilotClusterTokens.PrimaryAccent, contentColor = KoPilotClusterTokens.Background),
                    ) {
                        Text("Test Countdown", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onOpenAccidentDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Accident Detection Diagnostics & Safe Replay Lab")
                }
            }

            // Trip & Detection Toggles
            SafetyCard(title = "Trip & Detection") {
                SafetySwitchRow("Rollover Detection", s.rolloverDetectionEnabled) { v ->
                    scope.launch { prefsRepo.setRolloverDetectionEnabled(v) }
                }
                SafetySwitchRow("Impact & Collision Detection", s.impactDetectionEnabled) { v ->
                    scope.launch { prefsRepo.setImpactDetectionEnabled(v) }
                }
                SafetySwitchRow("Swerve & Loss-of-Control", s.swerveDetectionEnabled) { v ->
                    scope.launch { prefsRepo.setSwerveDetectionEnabled(v) }
                }
                SafetySwitchRow("Unexpected Engine Stop", s.engineStopDetectionEnabled) { v ->
                    scope.launch { prefsRepo.setEngineStopDetectionEnabled(v) }
                }
            }

            // HUD Extras
            SafetyCard(title = "HUD & Offline Maps") {
                SafetySwitchRow("Weather Updates (HUD)", s.weatherHudEnabled) { v ->
                    scope.launch { prefsRepo.setWeatherHudEnabled(v) }
                }
                SafetySwitchRow("Compass Heading (HUD)", s.compassHudEnabled) { v ->
                    scope.launch { prefsRepo.setCompassHudEnabled(v) }
                }
            }

            // Internal Safety & Road Event Log (with False-Alarm marking)
            SafetyCard(title = "Safety & Road Event Log (${state.safetyEventHistory.size})") {
                if (state.safetyEventHistory.isEmpty()) {
                    Text(
                        "No safety or rough-road events logged in this session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                } else {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    state.safetyEventHistory.take(10).forEach { ev ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${fmt.format(Date(ev.timestampMillis))} • ${ev.category} (${"%.1f".format(ev.peakLinearG)}g)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${ev.confirmationOutcome}${if (ev.fiveSecondMotionObservation.isNotBlank()) " | ${ev.fiveSecondMotionObservation}" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.SecondaryText,
                                )
                            }
                            if (!ev.markedFalseAlarm) {
                                TextButton(onClick = { onMarkFalseAlarm(ev.incidentId) }) {
                                    Text("False Alarm", fontSize = 11.sp, color = KoPilotClusterTokens.ModerateRoad)
                                }
                            } else {
                                Text("Marked False", fontSize = 11.sp, color = KoPilotClusterTokens.SecondaryText)
                            }
                        }
                    }
                }
            }

            // Emergency Notification History
            SafetyCard(title = "Emergency Event History (${history.size})") {
                if (history.isEmpty()) {
                    Text(
                        "No emergency alerts or cancellations recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                } else {
                    val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                    history.take(8).forEach { rec ->
                        Text(
                            text = "${fmt.format(Date(rec.timestampMillis))} • ${rec.eventLabel} (${rec.confidencePercent}%) — ${rec.status}",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoPilotClusterTokens.PrimaryText,
                        )
                        if (rec.detail.isNotBlank()) {
                            Text(
                                text = rec.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.SecondaryText,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyContactsScreen(
    state: DashboardUiState,
    prefsRepo: PreferencesRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = remember(state.settings.emergencyContactsJson) {
        EmergencySmsManager.parseContacts(state.settings.emergencyContactsJson)
    }
    var editingContact by remember { mutableStateOf<EmergencyContact?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var testConfirmTarget by remember { mutableStateOf<EmergencyContact?>(null) }
    var testResultBanner by remember { mutableStateOf<String?>(null) }

    if (showAddDialog || editingContact != null) {
        var name by remember(editingContact) { mutableStateOf(editingContact?.name.orEmpty()) }
        var phone by remember(editingContact) { mutableStateOf(editingContact?.phone.orEmpty()) }
        var isPrimary by remember(editingContact) { mutableStateOf(editingContact?.isPrimary ?: contacts.isEmpty()) }
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingContact = null
            },
            title = { Text(if (editingContact == null) "Add Emergency Contact" else "Edit Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone (e.g. 0712345678 or +254...)") },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = isPrimary, onCheckedChange = { isPrimary = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Primary Contact")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    editingContact = null
                }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() && phone.isNotBlank(),
                    onClick = {
                        val formatted = EmergencySmsManager.formatPhoneNumber(phone)
                        val updated = contacts.map {
                            if (isPrimary) it.copy(isPrimary = false) else it
                        }.toMutableList()
                        val target = editingContact
                        if (target == null) {
                            updated.add(EmergencyContact(UUID.randomUUID().toString(), name.trim(), formatted, isPrimary))
                        } else {
                            val idx = updated.indexOfFirst { it.id == target.id }
                            if (idx >= 0) updated[idx] = target.copy(name = name.trim(), phone = formatted, isPrimary = isPrimary)
                        }
                        scope.launch {
                            prefsRepo.setEmergencyContactsJson(EmergencySmsManager.encodeContacts(updated))
                        }
                        showAddDialog = false
                        editingContact = null
                    },
                ) { Text("Save") }
            },
        )
    }

    testConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { testConfirmTarget = null },
            title = { Text("Send Test SMS?") },
            text = { Text("This will send a clearly labeled TEST safety SMS to ${target.name} (${target.phone}).") },
            dismissButton = { TextButton(onClick = { testConfirmTarget = null }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    val fix = state.locationState.currentFix
                    val testAssessment = AccidentAssessment(
                        eventClass = AccidentEventClass.SUSPECTED_COLLISION,
                        confidencePercent = 80,
                        timestampMillis = System.currentTimeMillis(),
                        preEventSpeedKmh = fix?.speedKmh ?: 0f,
                        postEventSpeedKmh = 0f,
                        impactIntensityMps2 = 0f,
                        orientationChangeDeg = 0f,
                        gpsAccuracyMeters = fix?.horizontalAccuracyMeters,
                        sensorReliability = "Test",
                        triggeringMeasurements = listOf("Manual Test SMS"),
                        missingEvidence = emptyList(),
                    )
                    val msg = EmergencySmsManager.buildEmergencyMessage(testAssessment, fix?.latitude, fix?.longitude, isTest = true)
                    val res = EmergencySmsManager.sendEmergencySms(
                        context = context,
                        incidentId = "test_${System.currentTimeMillis()}",
                        contacts = listOf(target),
                        message = msg,
                        autoSmsOptedIn = true,
                    )
                    testResultBanner = res.statusLabel
                    testConfirmTarget = null
                }) { Text("Confirm & Send Test") }
            },
        )
    }

    Scaffold(
        containerColor = KoPilotClusterTokens.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KoPilotClusterTokens.Background,
                    titleContentColor = KoPilotClusterTokens.PrimaryText,
                    navigationIconContentColor = KoPilotClusterTokens.PrimaryText,
                ),
                title = { Text("Emergency Contacts", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Contact", tint = KoPilotClusterTokens.PrimaryAccent)
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
            testResultBanner?.let { banner ->
                Surface(
                    color = KoPilotClusterTokens.CardSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.PrimaryAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(banner, color = KoPilotClusterTokens.PrimaryAccent, modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }

            if (contacts.isEmpty()) {
                Surface(
                    color = KoPilotClusterTokens.CardSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No emergency contacts added", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold)
                        Text(
                            "Add a primary contact and optional backup contacts (Kenyan 07xx/01xx or international +... format).",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                        Button(onClick = { showAddDialog = true }) { Text("Add Contact") }
                    }
                }
            } else {
                contacts.forEach { contact ->
                    Surface(
                        color = KoPilotClusterTokens.CardSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (contact.isPrimary) KoPilotClusterTokens.PrimaryAccent else KoPilotClusterTokens.Border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(contact.name, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold)
                                    if (contact.isPrimary) {
                                        Icon(Icons.Default.Star, contentDescription = "Primary", tint = KoPilotClusterTokens.PrimaryAccent, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text(contact.phone, color = KoPilotClusterTokens.SecondaryText, fontSize = 13.sp)
                            }
                            IconButton(onClick = { testConfirmTarget = contact }) {
                                Icon(Icons.Default.Send, contentDescription = "Send Test SMS", tint = KoPilotClusterTokens.PrimaryAccent)
                            }
                            IconButton(onClick = { editingContact = contact }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = KoPilotClusterTokens.PrimaryText)
                            }
                            IconButton(onClick = {
                                val updated = contacts.filterNot { it.id == contact.id }
                                scope.launch { prefsRepo.setEmergencyContactsJson(EmergencySmsManager.encodeContacts(updated)) }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = KoPilotClusterTokens.CardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = KoPilotClusterTokens.PrimaryAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            content()
        }
    }
}

@Composable
private fun SafetySwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = KoPilotClusterTokens.PrimaryText, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
