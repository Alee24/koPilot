package ke.co.kopilot.feature.dashboard

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.common.model.GpsSignalState
import ke.co.kopilot.core.common.model.SpeedAlertDecision
import ke.co.kopilot.core.designsystem.component.CockpitPanel
import ke.co.kopilot.core.designsystem.component.DrivingActionButton
import ke.co.kopilot.core.designsystem.theme.KoPilotColors
import ke.co.kopilot.feature.camera.RoadCameraPreview
import ke.co.kopilot.feature.navigation.KoPilotNavigationMap
import ke.co.kopilot.feature.voice.VoiceConnectionState

@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onCallEmergency: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showDestinationDialog by remember { mutableStateOf(false) }

    if (!state.settings.safetyDisclosureAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("KoPilot Safety Notice") },
            text = {
                Text(
                    "KoPilot is an advisory driving companion. Camera-based traffic sign and hazard " +
                        "observations are experimental and not yet field-validated for Kenyan roads. " +
                        "Always obey posted road signs, traffic officers, and safe driving rules.",
                )
            },
            confirmButton = {
                Button(onClick = viewModel::acceptSafetyDisclosure) {
                    Text("I Understand")
                }
            },
        )
    }

    if (showDestinationDialog) {
        DestinationPickerDialog(
            state = state,
            presets = viewModel.kenyanCityPresets,
            onSearch = viewModel::searchDestinations,
            onSelect = { name, lat, lng ->
                viewModel.navigateToCoordinates(name, lat, lng)
                showDestinationDialog = false
            },
            onDismiss = { showDestinationDialog = false },
        )
    }

    Scaffold { padding ->
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                    PrimaryViewport(state = state, viewModel = viewModel)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TelemetryAndControlsColumn(
                        state = state,
                        viewModel = viewModel,
                        onOpenNavigate = { showDestinationDialog = true },
                        onOpenSettings = onOpenSettings,
                        onCallEmergency = onCallEmergency,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                ) {
                    PrimaryViewport(state = state, viewModel = viewModel)
                }
                TelemetryAndControlsColumn(
                    state = state,
                    viewModel = viewModel,
                    onOpenNavigate = { showDestinationDialog = true },
                    onOpenSettings = onOpenSettings,
                    onCallEmergency = onCallEmergency,
                )
            }
        }
    }
}

@Composable
private fun PrimaryViewport(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.settings.cameraPrimaryLayout) {
            RoadCameraPreview(
                permissionGranted = state.cameraStatus.permissionGranted,
                isDrivingSessionActive = state.isDrivingSessionActive,
                analysisIntervalMs = viewModel.analysisIntervalFor(state.settings.detectionQuality),
                performanceController = viewModel.cameraController,
                onFrameListener = { imageProxy, rotation, ts ->
                    viewModel.visionPipeline.analyzeFrame(
                        imageProxy = imageProxy,
                        rotationDegrees = rotation,
                        timestampMillis = ts,
                        onConfirmedSign = viewModel::onConfirmedSign,
                    )
                },
            )
        } else {
            val fix = state.locationState.currentFix
            KoPilotNavigationMap(
                currentLat = fix?.latitude,
                currentLng = fix?.longitude,
                routePoints = state.navigationState.decodedPoints,
                destinationLatLng = state.navigationState.destinationLatLng,
                destinationName = state.navigationState.destinationName,
                hasLocationPermission = state.locationState.permissionGranted,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(
                onClick = viewModel::toggleCameraPrimaryLayout,
                label = { Text(if (state.settings.cameraPrimaryLayout) "Camera View" else "Map View") },
                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
            )
            AssistChip(
                onClick = {},
                label = { Text(if (state.isOnline) "Online" else "Offline Mode") },
            )
        }
    }
}

@Composable
private fun TelemetryAndControlsColumn(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    onOpenNavigate: () -> Unit,
    onOpenSettings: () -> Unit,
    onCallEmergency: (String) -> Unit,
) {
    // B. Speed Display
    val speedColor = when (state.speedEvaluation.decision) {
        SpeedAlertDecision.POSSIBLE_OVERSPEED_WARNING -> KoPilotColors.DangerRed
        SpeedAlertDecision.SPEED_DATA_UNAVAILABLE -> KoPilotColors.WarningAmber
        else -> MaterialTheme.colorScheme.onSurface
    }
    CockpitPanel(
        borderColor = if (state.speedEvaluation.decision == SpeedAlertDecision.POSSIBLE_OVERSPEED_WARNING) {
            KoPilotColors.DangerRed
        } else {
            MaterialTheme.colorScheme.outline
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                val speedText = state.speedEvaluation.currentSpeedKmh?.let { "%.0f".format(it) } ?: "--"
                Text(
                    text = "$speedText km/h",
                    style = MaterialTheme.typography.displayLarge,
                    color = speedColor,
                )
                Text(
                    text = when (state.speedEvaluation.signalState) {
                        GpsSignalState.GOOD -> "GPS Active"
                        GpsSignalState.UNCERTAIN -> "GPS Speed Uncertain"
                        GpsSignalState.UNAVAILABLE -> "GPS Unavailable"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val limit = state.speedEvaluation.applicableSpeedLimitKmh
                Text(
                    text = if (limit != null) "$limit km/h" else "--",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Observed Limit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.visionState.lastObservation?.let { obs ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sign: ${obs.category.displayName} (${"%.0f".format(obs.confidence * 100)}%)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // C. AI Assistant
    CockpitPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "KoPilot Voice (${state.voiceState.connectionState.name})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = state.voiceState.lastTranscript.ifBlank { state.voiceState.statusMessage },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Row {
                IconButton(onClick = viewModel::toggleVoiceMute) {
                    Icon(
                        if (state.voiceState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute Mic",
                    )
                }
                OutlinedButton(onClick = viewModel::toggleVoiceConnection) {
                    Text(
                        if (state.voiceState.connectionState == VoiceConnectionState.CONNECTED) "Stop" else "Talk",
                    )
                }
            }
        }
    }

    // D. Route & Weather Summary
    CockpitPanel {
        val route = state.navigationState.activeRoute
        Text(
            text = state.navigationState.destinationName?.let { "Route: $it" } ?: "No active destination",
            style = MaterialTheme.typography.titleMedium,
        )
        if (route != null) {
            val etaMin = route.durationSeconds / 60
            val distKm = route.distanceMeters / 1000.0
            val nextStep = route.steps.firstOrNull()?.instruction ?: "Follow route"
            Text(
                text = "$etaMin min • ${"%.1f".format(distKm)} km • ${state.trafficEvaluation.statusLabel}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Next: $nextStep",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        val w = state.weatherState
        val tempStr = w.temperatureC?.let { "${"%.1f".format(it)}°C" } ?: "--"
        Text(
            text = "Weather: ${w.conditionSummary} ($tempStr)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // E. Vehicle Status
    CockpitPanel {
        val obd = state.diagnosticsState
        Text(
            text = if (obd.obdConnected) "OBD-II Connected (${obd.adapterName})" else obd.statusMessage,
            style = MaterialTheme.typography.titleMedium,
        )
        if (obd.obdConnected) {
            Text(
                text = "RPM: ${obd.engineRpm ?: "--"} • Coolant: ${obd.coolantTempC?.let { "$it°C" } ?: "--"} • DTCs: ${obd.dtcCodes.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = obd.tpmsStatusMessage,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // F. Trip Controls
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DrivingActionButton(
            text = if (state.isDrivingSessionActive) "End Trip" else "Start Driving",
            onClick = {
                if (state.isDrivingSessionActive) viewModel.endTrip() else viewModel.startDriving()
            },
            containerColor = if (state.isDrivingSessionActive) KoPilotColors.DangerRed else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        DrivingActionButton(
            text = "Navigate",
            onClick = onOpenNavigate,
            containerColor = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = viewModel::toggleAlertsMute) {
            Icon(
                if (state.settings.alertsMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = null,
            )
            Spacer(Modifier.width(4.dp))
            Text(if (state.settings.alertsMuted) "Unmute Alerts" else "Mute Alerts")
        }
        OutlinedButton(onClick = { onCallEmergency(state.settings.emergencyContactPhone) }) {
            Icon(Icons.Default.Call, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Emergency")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun DestinationPickerDialog(
    state: DashboardUiState,
    presets: List<Pair<String, com.google.android.gms.maps.model.LatLng>>,
    onSearch: (String) -> Unit,
    onSelect: (String, Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Navigate in Kenya") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search town or landmark") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { if (query.length >= 2) onSearch(query) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Search Places")
                }
                state.navigationState.searchResults.forEach { item ->
                    TextButton(
                        onClick = { onSelect(item.name, item.latitude, item.longitude) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${item.name} — ${item.formattedAddress}")
                    }
                }
                Text("Major Kenyan Towns", style = MaterialTheme.typography.labelMedium)
                presets.forEach { (name, latLng) ->
                    TextButton(
                        onClick = { onSelect(name, latLng.latitude, latLng.longitude) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
