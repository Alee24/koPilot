package ke.co.kopilot.feature.dashboard.pretrip

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.diagnostics.ObdConnectionState
import ke.co.kopilot.feature.diagnostics.ObdFailureCategory
import ke.co.kopilot.feature.sensors.MountCalibrationStatus
import ke.co.kopilot.feature.sensors.wearable.SmartwatchConnectionState

/**
 * Full-Screen Animated Pre-Trip Startup, Device Connection & Driver Readiness Wizard.
 *
 * Stages:
 * 1. Welcome & Real Initialization
 * 2. Camera & Phone Sensors
 * 3. OBD-II Vehicle Connection & Health Check
 * 4. Smartwatch & Sleep Integration
 * 5. Sensor Calibration
 * 6. Final Pre-Trip Readiness Report (Sections A-F) with fixed bottom action bar
 *
 * Zero emojis, zero fabricated readings, zero horizontal scrolling, dark/light theme support.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreTripStartupScreen(
    cameraPermissionGranted: Boolean,
    locationPermissionGranted: Boolean,
    bluetoothPermissionGranted: Boolean,
    micPermissionGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onStartTrip: () -> Unit,
    onSkipToDashboard: () -> Unit,
    onOpenFullDiagnostics: () -> Unit,
    onOpenSmartwatchHealth: () -> Unit,
    onPreTripChecksCompleted: () -> Unit = {},
    onStopVoiceBriefing: () -> Unit = {},
    viewModel: PreTripStartupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var briefingTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(state.currentStage) {
        if (state.currentStage == PreTripWizardStage.READINESS_REPORT && !briefingTriggered) {
            briefingTriggered = true
            onPreTripChecksCompleted()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startRealInitialization(
            cameraGranted = cameraPermissionGranted,
            locationGranted = locationPermissionGranted,
            bluetoothGranted = bluetoothPermissionGranted,
            micGranted = micPermissionGranted,
        )
    }

    LaunchedEffect(cameraPermissionGranted, locationPermissionGranted, bluetoothPermissionGranted, micPermissionGranted) {
        viewModel.updatePermissions(
            cameraGranted = cameraPermissionGranted,
            locationGranted = locationPermissionGranted,
            bluetoothGranted = bluetoothPermissionGranted,
            micGranted = micPermissionGranted,
        )
    }

    val stageFraction by animateFloatAsState(
        targetValue = state.currentStage.stepNumber.toFloat() / PreTripWizardStage.entries.size.toFloat(),
        animationSpec = tween(350),
        label = "wizardStageProgress",
    )

    Scaffold(
        containerColor = KoPilotClusterTokens.Background,
        topBar = {
            Surface(
                color = KoPilotClusterTokens.CardSurface,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsCar,
                                    contentDescription = "KoPilot",
                                    tint = KoPilotClusterTokens.PrimaryAccent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = "KoPilot Pre-Trip Check",
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                )
                                Text(
                                    text = "Step ${state.currentStage.stepNumber} of ${PreTripWizardStage.entries.size} — ${state.currentStage.title}",
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        TextButton(onClick = onSkipToDashboard) {
                            Text(
                                text = "Dashboard",
                                color = KoPilotClusterTokens.PrimaryAccent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { stageFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50)),
                        color = KoPilotClusterTokens.PrimaryAccent,
                        trackColor = KoPilotClusterTokens.ElevatedSurface,
                    )

                    // Compact stage selector pills for direct step navigation
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PreTripWizardStage.entries.forEach { st ->
                            val selected = st == state.currentStage
                            Surface(
                                onClick = { viewModel.goToStage(st) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) {
                                    KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.2f)
                                } else {
                                    KoPilotClusterTokens.ElevatedSurface
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) KoPilotClusterTokens.PrimaryAccent else Color.Transparent,
                                ),
                            ) {
                                Text(
                                    text = "${st.stepNumber}. ${st.title.substringBefore(" ")}",
                                    color = if (selected) KoPilotClusterTokens.PrimaryAccent else KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = KoPilotClusterTokens.CardSurface,
                border = BorderStroke(1.dp, KoPilotClusterTokens.ElevatedSurface),
            ) {
                if (state.currentStage == PreTripWizardStage.READINESS_REPORT) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                viewModel.logPreTripReportToActiveTrip()
                                onStartTrip()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KoPilotClusterTokens.PrimaryAccent,
                                contentColor = KoPilotClusterTokens.Background,
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Start Trip", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.retryFailedChecks() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Text("Retry Failed Checks", fontSize = 12.sp, color = KoPilotClusterTokens.PrimaryText)
                            }
                            OutlinedButton(
                                onClick = onOpenFullDiagnostics,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Text("View Diagnostics", fontSize = 12.sp, color = KoPilotClusterTokens.PrimaryAccent)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.currentStage.stepNumber > 1) {
                            OutlinedButton(
                                onClick = { viewModel.previousStage() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text("Back", color = KoPilotClusterTokens.PrimaryText)
                            }
                        }
                        Button(
                            onClick = { viewModel.nextStage() },
                            modifier = Modifier
                                .weight(1.4f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KoPilotClusterTokens.PrimaryAccent,
                                contentColor = KoPilotClusterTokens.Background,
                            ),
                        ) {
                            Text(
                                text = if (state.currentStage == PreTripWizardStage.SENSOR_CALIBRATION) {
                                    "View Readiness Report"
                                } else {
                                    "Continue"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = state.currentStage,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "wizardStageContent",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { stage ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (stage) {
                    PreTripWizardStage.WELCOME_INIT -> WelcomeInitStage(
                        state = state,
                        onRequestPermissions = onRequestPermissions,
                    )
                    PreTripWizardStage.CAMERA_AND_SENSORS -> CameraAndSensorsStage(
                        state = state,
                        onRequestPermissions = onRequestPermissions,
                    )
                    PreTripWizardStage.OBD_CONNECTION -> ObdConnectionWizardStage(
                        state = state,
                        onRequestPermissions = onRequestPermissions,
                        onScanObd = viewModel::scanObdAdapters,
                        onSelectObd = viewModel::connectObdAdapter,
                        onRetryObd = viewModel::retryObdConnection,
                    )
                    PreTripWizardStage.SMARTWATCH_CONNECTION -> SmartwatchWizardStage(
                        state = state,
                        onRequestPermissions = onRequestPermissions,
                        onScanWatches = viewModel::scanSmartwatches,
                        onSelectWatch = viewModel::connectSmartwatch,
                        onSyncSleep = viewModel::syncSleepNow,
                        onOpenFullWatchScreen = onOpenSmartwatchHealth,
                    )
                    PreTripWizardStage.SENSOR_CALIBRATION -> CalibrationStage(
                        state = state,
                        onCalibrateMount = viewModel::calibratePhoneMount,
                    )
                    PreTripWizardStage.READINESS_REPORT -> FinalReadinessReportStage(
                        state = state,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeInitStage(
    state: PreTripWizardUiState,
    onRequestPermissions: () -> Unit,
) {
    val animatedInitProgress by animateFloatAsState(
        targetValue = state.initProgressFraction,
        animationSpec = tween(400),
        label = "initProgress",
    )

    WizardCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = "KoPilot Logo",
                    tint = KoPilotClusterTokens.PrimaryAccent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column {
                Text(
                    text = "Preparing your driving assistant",
                    style = MaterialTheme.typography.titleMedium,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "Verifying real phone sensors, offline AI vision, OBD-II, and smartwatch readiness.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            }
        }

        LinearProgressIndicator(
            progress = { animatedInitProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50)),
            color = KoPilotClusterTokens.PrimaryAccent,
            trackColor = KoPilotClusterTokens.ElevatedSurface,
        )

        Text(
            text = "Current activity: ${state.currentActivityText}",
            style = MaterialTheme.typography.bodySmall,
            color = KoPilotClusterTokens.PrimaryAccent,
            fontWeight = FontWeight.SemiBold,
        )
    }

    WizardCard {
        Text(
            text = "DEVICE PERMISSIONS STATUS",
            style = MaterialTheme.typography.labelSmall,
            color = KoPilotClusterTokens.SecondaryText,
            fontWeight = FontWeight.Bold,
        )
        PermissionStatusRow("Camera (Road Signs & Traffic Lights)", state.cameraPermissionGranted)
        PermissionStatusRow("Location / GPS (Speed & Trip Tracking)", state.locationPermissionGranted)
        PermissionStatusRow("Bluetooth (OBD-II & BLE Smartwatch)", state.bluetoothPermissionGranted)
        PermissionStatusRow("Microphone (Offline Hands-Free Voice AI)", state.micPermissionGranted)

        if (!state.cameraPermissionGranted || !state.locationPermissionGranted || !state.bluetoothPermissionGranted || !state.micPermissionGranted) {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoPilotClusterTokens.PrimaryAccent,
                    contentColor = KoPilotClusterTokens.Background,
                ),
            ) {
                Text("Grant Required Permissions", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CameraAndSensorsStage(
    state: PreTripWizardUiState,
    onRequestPermissions: () -> Unit,
) {
    var showDiagnostics by remember { mutableStateOf(false) }

    WizardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Sensors, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent)
                Text(
                    text = "CAMERA & PHONE SENSORS",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )
            }
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                Text(if (showDiagnostics) "Hide Details" else "Live Readings", color = KoPilotClusterTokens.PrimaryAccent)
                Icon(
                    imageVector = if (showDiagnostics) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = KoPilotClusterTokens.PrimaryAccent,
                )
            }
        }

        state.sensorChecks.forEach { item ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = KoPilotClusterTokens.ElevatedSurface,
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.name,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(status = item.status)
                    }
                    Text(
                        text = item.readingText,
                        color = KoPilotClusterTokens.SecondaryText,
                        fontSize = 12.sp,
                    )
                    if (showDiagnostics) {
                        Text(
                            text = item.detailText,
                            color = KoPilotClusterTokens.SecondaryText,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        if (!state.cameraPermissionGranted || !state.locationPermissionGranted) {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoPilotClusterTokens.PrimaryAccent,
                    contentColor = KoPilotClusterTokens.Background,
                ),
            ) {
                Text("Grant Missing Camera / GPS Permissions", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObdConnectionWizardStage(
    state: PreTripWizardUiState,
    onRequestPermissions: () -> Unit,
    onScanObd: () -> Unit,
    onSelectObd: (String, String) -> Unit,
    onRetryObd: () -> Unit,
) {
    var showTroubleshoot by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    val isBusy = state.obdConnectionState in setOf(
        ObdConnectionState.SCANNING,
        ObdConnectionState.PAIRING,
        ObdConnectionState.CONNECTING,
        ObdConnectionState.ADAPTER_INITIALIZING,
        ObdConnectionState.ECU_CONNECTING,
        ObdConnectionState.RECONNECTING,
    )

    WizardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent)
                Column {
                    Text(
                        text = "OBD-II VEHICLE ADAPTER",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = state.obdAdapterName ?: "No OBD-II Adapter Connected",
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
            StatusPill(
                status = when {
                    state.obdConnected -> PreTripCheckStatus.READY
                    !state.bluetoothPermissionGranted -> PreTripCheckStatus.NEEDS_PERMISSION
                    isBusy -> PreTripCheckStatus.CHECKING
                    state.obdConnectionState == ObdConnectionState.ERROR || state.obdConnectionState == ObdConnectionState.UNSUPPORTED -> PreTripCheckStatus.FAILED
                    else -> PreTripCheckStatus.UNAVAILABLE
                },
            )
        }

        if (isBusy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50)),
                color = KoPilotClusterTokens.PrimaryAccent,
                trackColor = KoPilotClusterTokens.ElevatedSurface,
            )
        }

        Text(
            text = state.obdStatusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.obdConnected) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
            fontWeight = FontWeight.Medium,
        )

        // Contextual troubleshooting card when OBD fails
        if (state.obdFailureCategory != ObdFailureCategory.NONE || showTroubleshoot) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = KoPilotClusterTokens.ModerateRoad.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, KoPilotClusterTokens.ModerateRoad),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (state.obdFailureCategory != ObdFailureCategory.NONE) {
                            state.obdFailureCategory.title
                        } else {
                            "OBD-II Connection Troubleshooting"
                        },
                        color = KoPilotClusterTokens.ModerateRoad,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = if (state.obdFailureCategory != ObdFailureCategory.NONE) {
                            state.obdFailureCategory.guidance
                        } else {
                            "1. Plug adapter firmly into the OBD-II port and turn ignition ON.\n2. Close other OBD apps (Torque, Car Scanner).\n3. Confirm adapter is paired in Android Bluetooth settings (PIN 1234 or 0000).\n4. Select your ELM327 / HHOBD / Vgate adapter below."
                        },
                        color = KoPilotClusterTokens.PrimaryText,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.bluetoothPermissionGranted) {
                Button(
                    onClick = onRequestPermissions,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KoPilotClusterTokens.PrimaryAccent,
                        contentColor = KoPilotClusterTokens.Background,
                    ),
                ) {
                    Text("Grant Bluetooth Permission", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onScanObd,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KoPilotClusterTokens.PrimaryAccent,
                        contentColor = KoPilotClusterTokens.Background,
                    ),
                ) {
                    Text("Scan OBD Adapters", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onRetryObd) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry Connection", color = KoPilotClusterTokens.PrimaryText)
                }
            }
            OutlinedButton(onClick = { showTroubleshoot = !showTroubleshoot }) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Troubleshoot", color = KoPilotClusterTokens.SecondaryAccent)
            }
            OutlinedButton(onClick = { showLog = !showLog }) {
                Text(if (showLog) "Hide Log" else "Protocol Log", color = KoPilotClusterTokens.SecondaryText)
            }
        }

        // Discovered / Paired Adapters list
        if (state.obdDiscoveredDevices.isNotEmpty()) {
            HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)
            Text(
                text = "Available Bluetooth Adapters (${state.obdDiscoveredDevices.size}) — Tap to Pair & Connect:",
                style = MaterialTheme.typography.labelMedium,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.obdDiscoveredDevices.take(8).forEach { dev ->
                    Surface(
                        onClick = { onSelectObd(dev.address, dev.name) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = KoPilotClusterTokens.ElevatedSurface,
                        border = BorderStroke(
                            1.dp,
                            if (dev.isHhobdHint) KoPilotClusterTokens.PrimaryAccent else Color.Transparent,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dev.name,
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    text = "${dev.address} | ${dev.transportHint.name.replace('_', ' ')} | ${if (dev.isPaired) "Paired" else "Unpaired"}",
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                )
                            }
                            Text(
                                text = "CONNECT",
                                color = KoPilotClusterTokens.PrimaryAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        // Live Pre-Trip Vehicle Health Check (Phase 4)
        if (state.obdConnected) {
            HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)
            Text(
                text = "VEHICLE PRE-TRIP HEALTH CHECK (READ-ONLY ECU)",
                style = MaterialTheme.typography.labelSmall,
                color = KoPilotClusterTokens.PrimaryAccent,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Protocol: ${state.obdLiveTelemetry.protocolName ?: "Detected"} | ECU Latency: ${state.obdLiveTelemetry.ecuLatencyMs?.let { "${it} ms" } ?: "--"} | Supported PIDs: ${state.obdLiveTelemetry.supportedPidHexes.size}",
                color = KoPilotClusterTokens.SecondaryText,
                fontSize = 11.sp,
            )
            state.vehicleHealthParams.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = p.name,
                        color = KoPilotClusterTokens.SecondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = p.valueText,
                        color = when {
                            p.isAbnormal -> KoPilotClusterTokens.VeryRough
                            p.isAvailable -> KoPilotClusterTokens.PrimaryText
                            else -> KoPilotClusterTokens.SecondaryText
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
                p.note?.let { n ->
                    Text(text = n, color = if (p.isAbnormal) KoPilotClusterTokens.ModerateRoad else KoPilotClusterTokens.SecondaryText, fontSize = 11.sp)
                }
            }
        }

        if (showLog && state.obdConnectionLog.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = KoPilotClusterTokens.ElevatedSurface,
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.obdConnectionLog.takeLast(12).forEach { line ->
                        Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = KoPilotClusterTokens.SecondaryText)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartwatchWizardStage(
    state: PreTripWizardUiState,
    onRequestPermissions: () -> Unit,
    onScanWatches: () -> Unit,
    onSelectWatch: (String, String) -> Unit,
    onSyncSleep: () -> Unit,
    onOpenFullWatchScreen: () -> Unit,
) {
    val diag = state.watchDiagnostics
    val hr = state.heartRateState
    val isLive = diag.connectionState == SmartwatchConnectionState.SUBSCRIBED_LIVE

    WizardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Watch, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent)
                Column {
                    Text(
                        text = "SMARTWATCH & BLE HEART RATE (0x180D)",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = diag.connectedName ?: "No Smartwatch Connected",
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
            StatusPill(
                status = when (diag.connectionState) {
                    SmartwatchConnectionState.SUBSCRIBED_LIVE -> PreTripCheckStatus.READY
                    SmartwatchConnectionState.PERMISSION_REQUIRED -> PreTripCheckStatus.NEEDS_PERMISSION
                    SmartwatchConnectionState.SCANNING,
                    SmartwatchConnectionState.CONNECTING_GATT,
                    SmartwatchConnectionState.DISCOVERING_SERVICES,
                    SmartwatchConnectionState.RECONNECTING -> PreTripCheckStatus.CHECKING
                    SmartwatchConnectionState.CONNECTION_ERROR,
                    SmartwatchConnectionState.BLUETOOTH_DISABLED -> PreTripCheckStatus.FAILED
                    else -> PreTripCheckStatus.UNAVAILABLE
                },
            )
        }

        Text(
            text = diag.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = KoPilotClusterTokens.SecondaryText,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WizardKpiTile(
                label = "Live Heart Rate (0x2A37)",
                value = if (hr.isLiveReading && !hr.isStale) "${hr.displayBpmText} BPM" else "-- (No reading)",
                accent = if (hr.isLiveReading && !hr.isStale) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                modifier = Modifier.weight(1f),
            )
            WizardKpiTile(
                label = "Watch Battery (0x180F)",
                value = diag.batteryPercent?.let { "$it%" } ?: if (isLive) "Not exposed" else "--",
                accent = KoPilotClusterTokens.PrimaryAccent,
                modifier = Modifier.weight(1f),
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.bluetoothPermissionGranted) {
                Button(onClick = onRequestPermissions) {
                    Text("Grant Bluetooth Permission")
                }
            } else {
                Button(
                    onClick = onScanWatches,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KoPilotClusterTokens.PrimaryAccent,
                        contentColor = KoPilotClusterTokens.Background,
                    ),
                ) {
                    Text("Scan BLE Smartwatches", fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(onClick = onSyncSleep) {
                Text("Sync Sleep (Health Connect)", color = KoPilotClusterTokens.SecondaryAccent)
            }
            OutlinedButton(onClick = onOpenFullWatchScreen) {
                Text("Full Health Screen", color = KoPilotClusterTokens.PrimaryText)
            }
        }

        if (state.discoveredWatches.isNotEmpty()) {
            HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)
            Text(
                text = "Nearby BLE Smartwatches (${state.discoveredWatches.size}):",
                style = MaterialTheme.typography.labelMedium,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.discoveredWatches.take(6).forEach { w ->
                    Surface(
                        onClick = { onSelectWatch(w.address, w.name) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = KoPilotClusterTokens.ElevatedSurface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(w.name, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    text = "${w.address} | ${w.rssiDbm} dBm${if (w.advertisesHeartRateService) " | HR 0x180D" else ""}",
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                )
                            }
                            Text("CONNECT", color = KoPilotClusterTokens.PrimaryAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)
        Text(
            text = "Sleep Integration: ${state.driverWellness.sleepDurationText} (${state.sleepState.healthConnectAvailability.displayName})",
            style = MaterialTheme.typography.bodySmall,
            color = KoPilotClusterTokens.PrimaryText,
        )
        Text(
            text = state.sleepState.directBleSleepProtocolStatus,
            style = MaterialTheme.typography.labelSmall,
            color = KoPilotClusterTokens.SecondaryText,
        )
    }
}

@Composable
private fun CalibrationStage(
    state: PreTripWizardUiState,
    onCalibrateMount: () -> Unit,
) {
    WizardCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Explore, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent)
            Text(
                text = "SENSOR CALIBRATION & VERIFICATION",
                style = MaterialTheme.typography.labelSmall,
                color = KoPilotClusterTokens.SecondaryText,
                fontWeight = FontWeight.Bold,
            )
        }

        // 1. Phone Mounting Calibration
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = KoPilotClusterTokens.ElevatedSurface,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Phone Mounting Calibration", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    StatusPill(
                        status = if (state.mountCalibrationStatus == MountCalibrationStatus.CALIBRATED) {
                            PreTripCheckStatus.READY
                        } else {
                            PreTripCheckStatus.CALIBRATION_REQUIRED
                        },
                    )
                }
                Text(
                    text = "Mount your phone securely in its dashboard holder while parked, then tap Calibrate to lock the stationary accelerometer/gyroscope baseline.",
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 12.sp,
                )
                Text(
                    text = "Current Tilt: Pitch ${"%.1f".format(state.pitchDegrees)} deg | Roll ${"%.1f".format(state.rollDegrees)} deg",
                    color = KoPilotClusterTokens.PrimaryAccent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Button(
                    onClick = onCalibrateMount,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KoPilotClusterTokens.PrimaryAccent,
                        contentColor = KoPilotClusterTokens.Background,
                    ),
                ) {
                    Text("Calibrate Stationary Mount Baseline", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 2. Compass & GPS Verification
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = KoPilotClusterTokens.ElevatedSurface,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Compass & GPS Verification", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    text = "Compass: ${state.compassCardinal} (${state.compassDegrees} deg) — ${if (state.compassReliable) "Active" else "Move phone in a figure-8 away from magnets"}",
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 12.sp,
                )
                Text(
                    text = "GPS Lock: ${state.gpsSatellitesUsed} satellites | Accuracy: ${state.gpsAccuracyMeters?.let { "±${it.toInt()} m" } ?: "Acquiring"} | Altitude: ${state.gpsAltitudeMeters?.let { "${it.toInt()} m" } ?: "Unavailable"}",
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 12.sp,
                )
                Text(
                    text = if (state.gpsHeadingReliable) {
                        "Vehicle GPS Heading: Reliable (moving)"
                    } else {
                        "Vehicle GPS Heading: Stationary — relying on magnetometer compass until moving >= 8 km/h."
                    },
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 11.sp,
                )
            }
        }

        // 3. OBD & Smartwatch Verification
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = KoPilotClusterTokens.ElevatedSurface,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("OBD-II & Smartwatch Verification", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    text = "OBD ECU: ${if (state.obdConnected) "Verified (${state.obdLiveTelemetry.supportedPidHexes.size} PIDs, ${state.obdLiveTelemetry.ecuLatencyMs ?: "--"} ms)" else "Not connected (optional)"}",
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 12.sp,
                )
                Text(
                    text = "Smartwatch HR: ${state.driverWellness.currentHeartRateText} | ${state.driverWellness.watchContactText}",
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun FinalReadinessReportStage(
    state: PreTripWizardUiState,
) {
    val readiness = state.readinessSummary
    val levelColor = when (readiness.overallLevel) {
        OverallReadinessLevel.READY -> KoPilotClusterTokens.SmoothRoad
        OverallReadinessLevel.READY_WITH_LIMITATIONS -> KoPilotClusterTokens.ModerateRoad
        OverallReadinessLevel.ATTENTION_REQUIRED -> KoPilotClusterTokens.VeryRough
    }

    // Section F: Overall Readiness Banner at top for immediate driver visibility
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = levelColor.copy(alpha = 0.14f),
        border = BorderStroke(1.5.dp, levelColor),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "F. OVERALL READINESS",
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                    fontWeight = FontWeight.Bold,
                )
                Surface(shape = RoundedCornerShape(6.dp), color = levelColor) {
                    Text(
                        text = readiness.overallLevel.label.uppercase(),
                        color = KoPilotClusterTokens.Background,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                text = readiness.headline,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            readiness.reasons.forEach { reason ->
                Text(text = "- $reason", color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
            }
        }
    }

    // Section A: System Status
    WizardCard {
        Text("A. SYSTEM STATUS", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryText, fontWeight = FontWeight.Bold)
        state.sensorChecks.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.name, color = KoPilotClusterTokens.PrimaryText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                StatusPill(status = item.status)
            }
        }
    }

    // Section B: Vehicle Connection
    WizardCard {
        Text("B. VEHICLE CONNECTION (OBD-II)", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryText, fontWeight = FontWeight.Bold)
        ReportKeyValue("Adapter", state.obdAdapterName ?: "Not connected")
        ReportKeyValue("Status", state.obdConnectionState.name.replace('_', ' '))
        ReportKeyValue("Vehicle Protocol", state.obdLiveTelemetry.protocolName ?: "Unavailable")
        ReportKeyValue("Supported Parameters", "${state.obdLiveTelemetry.supportedPidHexes.size} Mode 01 PIDs")
        ReportKeyValue("Diagnostic Codes", if (state.obdDtcCodes.isEmpty()) "None detected" else state.obdDtcCodes.joinToString(", "))
    }

    // Section C: Smartwatch
    WizardCard {
        Text("C. SMARTWATCH", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryText, fontWeight = FontWeight.Bold)
        ReportKeyValue("Device Name", state.watchDiagnostics.connectedName ?: "Not connected")
        ReportKeyValue("Heart-Rate Status", state.watchDiagnostics.connectionState.displayName)
        ReportKeyValue("Latest Valid BPM", state.driverWellness.currentHeartRateText)
        ReportKeyValue("Watch Battery", state.watchDiagnostics.batteryPercent?.let { "$it%" } ?: "Unavailable")
        ReportKeyValue("Sleep Data", state.driverWellness.sleepDurationText)
    }

    // Section D: Sensor Calibration
    WizardCard {
        Text("D. SENSOR CALIBRATION", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryText, fontWeight = FontWeight.Bold)
        ReportKeyValue("Mounting Calibration", state.mountCalibrationStatus.name.replace('_', ' '))
        ReportKeyValue("Motion Baseline", "Pitch ${"%.1f".format(state.pitchDegrees)} deg | Roll ${"%.1f".format(state.rollDegrees)} deg")
        ReportKeyValue("Compass Heading", "${state.compassCardinal} (${state.compassDegrees} deg)")
        ReportKeyValue("GPS Accuracy", state.gpsAccuracyMeters?.let { "±${it.toInt()} m (${state.gpsSatellitesUsed} sat)" } ?: "Acquiring")
    }

    // Section E: Driver Readiness & Wellness
    WizardCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent, modifier = Modifier.size(16.dp))
            Text("E. DRIVER READINESS & WELLNESS", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryText, fontWeight = FontWeight.Bold)
        }
        val w = state.driverWellness
        ReportKeyValue("Last Recorded Sleep", "${w.sleepDurationText} (${w.sleepWindowText})")
        ReportKeyValue("Sleep Stages", w.sleepStagesSummary)
        ReportKeyValue("Heart Rate", "${w.currentHeartRateText} | ${w.restingOrAvgHeartRateText}")
        ReportKeyValue("Data Quality", "${w.dataFreshnessText} | ${w.watchContactText}")
        Text(
            text = w.summaryStatement,
            color = if (w.cautionMessages.isNotEmpty()) KoPilotClusterTokens.ModerateRoad else KoPilotClusterTokens.PrimaryText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        Text(
            text = "Non-diagnostic notice: Heart rate and sleep duration alone do not establish medical fitness to drive.",
            color = KoPilotClusterTokens.SecondaryText,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun WizardCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = KoPilotClusterTokens.CardSurface,
        border = BorderStroke(1.dp, KoPilotClusterTokens.ElevatedSurface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun WizardKpiTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = KoPilotClusterTokens.ElevatedSurface,
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 10.sp, color = KoPilotClusterTokens.SecondaryText)
            Text(value, fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = KoPilotClusterTokens.PrimaryText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        StatusPill(status = if (granted) PreTripCheckStatus.READY else PreTripCheckStatus.NEEDS_PERMISSION)
    }
}

@Composable
private fun ReportKeyValue(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(value, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun StatusPill(status: PreTripCheckStatus) {
    val (color, icon: ImageVector) = when (status) {
        PreTripCheckStatus.READY -> KoPilotClusterTokens.SmoothRoad to Icons.Default.CheckCircle
        PreTripCheckStatus.CHECKING -> KoPilotClusterTokens.PrimaryAccent to Icons.Default.Refresh
        PreTripCheckStatus.NEEDS_PERMISSION,
        PreTripCheckStatus.CALIBRATION_REQUIRED -> KoPilotClusterTokens.ModerateRoad to Icons.Default.Warning
        PreTripCheckStatus.UNAVAILABLE -> KoPilotClusterTokens.SecondaryText to Icons.Default.Warning
        PreTripCheckStatus.FAILED -> KoPilotClusterTokens.VeryRough to Icons.Default.Error
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Text(
                text = status.label,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
