package ke.co.kopilot.feature.dashboard

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.common.model.GpsSignalState
import ke.co.kopilot.core.common.model.SpeedAlertDecision
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.core.designsystem.theme.KoPilotColors
import ke.co.kopilot.feature.camera.RoadCameraPreview
import ke.co.kopilot.feature.location.SpeedZone
import ke.co.kopilot.feature.navigation.KoPilotNavigationMap
import ke.co.kopilot.feature.voice.VoiceConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CockpitDarkBg = Color(0xFF0A1016)
private val CockpitCardBg = Color(0xFF131C26)
private val CockpitCardBorder = Color(0xFF233242)
private val MintTeal = Color(0xFF18E2B8)
private val NavBlue = Color(0xFF2B7FFF)

@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenSensors: () -> Unit = {},
    onCallEmergency: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showTripsDialog by remember { mutableStateOf(false) }

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

    if (showTripsDialog) {
        TripsHistoryDialog(
            trips = state.savedTrips,
            onDismiss = { showTripsDialog = false },
        )
    }

    val screenBgColor by animateColorAsState(
        targetValue = when (state.speedZone) {
            SpeedZone.OPTIMAL_CRUISE -> Color(0xFF0A244D)
            SpeedZone.REDUCE_SPEED -> Color(0xFF3E0B14)
            SpeedZone.DANGEROUS_DRIVING -> Color(0xFF5A0812)
            else -> CockpitDarkBg
        },
        label = "speedZoneBg",
    )

    Scaffold(containerColor = screenBgColor) { padding ->
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1.1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TopCockpitHeader(isOnline = state.isOnline, onOpenSensors = onOpenSensors)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ViewportCard(state = state, viewModel = viewModel)
                    }
                    VoiceAssistantBar(
                        state = state,
                        onToggleVoice = viewModel::toggleVoiceConnection,
                        onToggleMute = viewModel::toggleVoiceMute,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SpeedCockpitRow(state = state, onOpenSensors = onOpenSensors)
                SpeedZoneBanner(state.speedZone)
                state.alertState.activeAlert?.let { alert ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF102A30),
                        border = BorderStroke(1.dp, MintTeal.copy(alpha = 0.6f)),
                    ) {
                        Text(
                            text = "Alert: ${alert.displayText}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                    QuickInfoCardsRow(
                        state = state,
                        onOpenDestination = { showDestinationDialog = true },
                        onOpenSettings = onOpenSettings,
                    )
                    PrimaryActionButtonsRow(
                        state = state,
                        onToggleDrive = {
                            if (state.isDrivingSessionActive) viewModel.endTrip() else viewModel.startDriving()
                        },
                        onOpenNavigate = { showDestinationDialog = true },
                    )
                    BottomQuickActionsBar(
                        alertsMuted = state.settings.alertsMuted,
                        onToggleMuteAlerts = viewModel::toggleAlertsMute,
                        onCallEmergency = { onCallEmergency(state.settings.emergencyContactPhone) },
                        onOpenTrips = { showTripsDialog = true },
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopCockpitHeader(isOnline = state.isOnline, onOpenSensors = onOpenSensors)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(215.dp),
                ) {
                    ViewportCard(state = state, viewModel = viewModel)
                }
                SpeedCockpitRow(state = state, onOpenSensors = onOpenSensors)
                SpeedZoneBanner(state.speedZone)
                state.alertState.activeAlert?.let { alert ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF102A30),
                        border = BorderStroke(1.dp, MintTeal.copy(alpha = 0.6f)),
                    ) {
                        Text(
                            text = "Alert: ${alert.displayText}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                VoiceAssistantBar(
                    state = state,
                    onToggleVoice = viewModel::toggleVoiceConnection,
                    onToggleMute = viewModel::toggleVoiceMute,
                )
                QuickInfoCardsRow(
                    state = state,
                    onOpenDestination = { showDestinationDialog = true },
                    onOpenSettings = onOpenSettings,
                )
                PrimaryActionButtonsRow(
                    state = state,
                    onToggleDrive = {
                        if (state.isDrivingSessionActive) viewModel.endTrip() else viewModel.startDriving()
                    },
                    onOpenNavigate = { showDestinationDialog = true },
                )
                BottomQuickActionsBar(
                    alertsMuted = state.settings.alertsMuted,
                    onToggleMuteAlerts = viewModel::toggleAlertsMute,
                    onCallEmergency = { onCallEmergency(state.settings.emergencyContactPhone) },
                    onOpenTrips = { showTripsDialog = true },
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun TopCockpitHeader(
    isOnline: Boolean,
    onOpenSensors: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenSensors, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Menu, contentDescription = "Sensors & Motion", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brush.linearGradient(listOf(MintTeal, NavBlue))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "K",
                    color = CockpitDarkBg,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "KoPilot",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    lineHeight = 20.sp,
                )
                Text(
                    text = "Your Intelligent Driving Assistant",
                    color = Color(0xFF8C9EB2),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = CockpitCardBg,
            border = BorderStroke(1.dp, if (isOnline) Color(0xFF174B44) else Color(0xFF4D3B1A)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) MintTeal else KoPilotColors.WarningAmber),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isOnline) "Online" else "Offline",
                    color = if (isOnline) MintTeal else KoPilotColors.WarningAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ViewportCard(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(18.dp),
        color = CockpitCardBg,
        border = BorderStroke(1.dp, CockpitCardBorder),
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
                // Real-time on-device tracked object bounding boxes + labels
                Box(modifier = Modifier.fillMaxSize()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (obj in state.visionState.trackedObjects) {
                            val left = obj.normalizedLeft * size.width
                            val top = obj.normalizedTop * size.height
                            val right = obj.normalizedRight * size.width
                            val bottom = obj.normalizedBottom * size.height
                            val boxColor = when (obj.label) {
                                "Pedestrian" -> KoPilotColors.WarningAmber
                                "Vehicle" -> MintTeal
                                else -> NavBlue
                            }
                            drawRect(
                                color = boxColor,
                                topLeft = Offset(left, top),
                                size = Size((right - left).coerceAtLeast(4f), (bottom - top).coerceAtLeast(4f)),
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                        }
                    }
                    // Visual road sign badge in bottom-right of camera viewport once a sign is recognized
                    state.visionState.lastObservation?.let { obs ->
                        val limit = obs.category.speedLimitKmh
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp),
                            shape = if (limit != null) CircleShape else RoundedCornerShape(10.dp),
                            color = Color.White,
                            border = BorderStroke(4.dp, Color(0xFFD32F2F)),
                        ) {
                            Text(
                                text = limit?.toString() ?: obs.category.displayName.take(12),
                                color = Color(0xFF101820),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (limit != null) 16.sp else 11.sp,
                                modifier = Modifier.padding(
                                    horizontal = if (limit != null) 10.dp else 8.dp,
                                    vertical = if (limit != null) 8.dp else 6.dp,
                                ),
                            )
                        }
                    }
                }
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

            // Top-left View + LIVE badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                shape = RoundedCornerShape(50),
                color = Color(0xCC0D141C),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (state.settings.cameraPrimaryLayout) Icons.Default.Videocam else Icons.Default.Navigation,
                        contentDescription = null,
                        tint = MintTeal,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (state.settings.cameraPrimaryLayout) "Camera View" else "Map View",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (state.cameraStatus.isPreviewActive) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF198754))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("● LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Top-right expand/swap button
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(34.dp)
                    .clickable(onClick = viewModel::toggleCameraPrimaryLayout),
                shape = CircleShape,
                color = Color(0xCC0D141C),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Switch View",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Bottom-left monitoring status pill
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                shape = RoundedCornerShape(50),
                color = Color(0xCC0D141C),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = if (state.isDrivingSessionActive) MintTeal else Color(0xFF8C9EB2),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            if (state.cameraStatus.isPreviewActive) {
                                val summary = state.visionState.trackedObjects
                                    .take(2)
                                    .joinToString(", ") { it.label }
                                append(
                                    if (summary.isNotBlank()) {
                                        "${state.visionState.trackedObjects.size} obj ($summary)"
                                    } else {
                                        "Scanning road"
                                    },
                                )
                                state.visionState.lastDetectedPlate?.let { plate ->
                                    append(" • $plate")
                                }
                                if (state.visionState.redBrakeLightsAhead) {
                                    append(" • BRAKE LIGHTS")
                                } else if (state.visionState.isDarkScene) {
                                    append(" • LOW LIGHT")
                                }
                            } else {
                                append(state.cameraStatus.statusMessage)
                            }
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedZoneBanner(zone: SpeedZone) {
    val bannerText = zone.displayBanner ?: return
    val (bgColor, borderColor, textColor) = when (zone) {
        SpeedZone.OPTIMAL_CRUISE -> Triple(Color(0xFF10346B), NavBlue, Color.White)
        SpeedZone.REDUCE_SPEED, SpeedZone.DANGEROUS_DRIVING -> Triple(Color(0xFF61101C), KoPilotColors.DangerRed, Color.White)
        SpeedZone.ENCOURAGE_PACE -> Triple(Color(0xFF2B220E), KoPilotColors.WarningAmber, KoPilotColors.WarningAmber)
        SpeedZone.NORMAL -> return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = bannerText,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SpeedCockpitRow(
    state: DashboardUiState,
    onOpenSensors: () -> Unit,
) {
    val speedKmh = state.speedEvaluation.currentSpeedKmh
    val speedText = speedKmh?.let { "%.0f".format(it) } ?: "--"
    val isOverspeed = state.speedEvaluation.decision == SpeedAlertDecision.POSSIBLE_OVERSPEED_WARNING ||
        state.speedZone == SpeedZone.REDUCE_SPEED ||
        state.speedZone == SpeedZone.DANGEROUS_DRIVING
    val arcColor = when {
        isOverspeed -> KoPilotColors.DangerRed
        state.speedZone == SpeedZone.OPTIMAL_CRUISE -> NavBlue
        state.speedZone == SpeedZone.ENCOURAGE_PACE -> KoPilotColors.WarningAmber
        else -> MintTeal
    }
    val progress = ((speedKmh ?: 0f) / 140f).coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left: GPS card
        Surface(
            modifier = Modifier.width(86.dp).clickable(onClick = onOpenSensors),
            shape = RoundedCornerShape(14.dp),
            color = CockpitCardBg,
            border = BorderStroke(1.dp, CockpitCardBorder),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.SatelliteAlt,
                    contentDescription = null,
                    tint = when (state.speedEvaluation.signalState) {
                        GpsSignalState.GOOD -> MintTeal
                        GpsSignalState.UNCERTAIN -> KoPilotColors.WarningAmber
                        GpsSignalState.UNAVAILABLE -> Color(0xFF6B7C90)
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.locationState.satellitesInView > 0) {
                        "${state.locationState.satellitesUsedInFix}/${state.locationState.satellitesInView} sats"
                    } else {
                        "GPS"
                    },
                    color = Color(0xFF8C9EB2),
                    fontSize = 10.sp,
                )
                Text(
                    text = when (state.speedEvaluation.signalState) {
                        GpsSignalState.GOOD -> "Strong"
                        GpsSignalState.UNCERTAIN -> "Weak"
                        GpsSignalState.UNAVAILABLE -> "Off"
                    },
                    color = when (state.speedEvaluation.signalState) {
                        GpsSignalState.GOOD -> MintTeal
                        GpsSignalState.UNCERTAIN -> KoPilotColors.WarningAmber
                        GpsSignalState.UNAVAILABLE -> Color(0xFF8C9EB2)
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                val activeBars = when (state.speedEvaluation.signalState) {
                    GpsSignalState.GOOD -> 4
                    GpsSignalState.UNCERTAIN -> 2
                    GpsSignalState.UNAVAILABLE -> 0
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    repeat(4) { idx ->
                        Box(
                            modifier = Modifier
                                .width(5.dp)
                                .height((6 + idx * 3).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (idx < activeBars) MintTeal else Color(0xFF283747)),
                        )
                    }
                }
            }
        }

        // Center: Speedometer Arc Gauge
        Box(
            modifier = Modifier.size(164.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val strokeWidth = 10.dp.toPx()
                val arcSize = Size(size.width, size.height)
                drawArc(
                    color = Color(0xFF1D2A36),
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = Offset.Zero,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                if (progress > 0f) {
                    drawArc(
                        color = arcColor,
                        startAngle = 140f,
                        sweepAngle = 260f * progress,
                        useCenter = false,
                        topLeft = Offset.Zero,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = Color(0xFF9FB0C3),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = speedText,
                    color = if (isOverspeed) KoPilotColors.DangerRed else Color.White,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 46.sp,
                )
                Text(
                    text = "km/h",
                    color = MintTeal,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Right: Speed Limit Sign card (populates from last recognized speed limit sign)
        Surface(
            modifier = Modifier.width(86.dp),
            shape = RoundedCornerShape(14.dp),
            color = CockpitCardBg,
            border = BorderStroke(1.dp, CockpitCardBorder),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val limit = state.visionState.lastSpeedLimitKmh
                    ?: state.speedEvaluation.applicableSpeedLimitKmh
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(5.dp, Color(0xFFD32F2F)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = limit?.toString() ?: "--",
                            color = Color(0xFF101820),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.visionState.lastObservation?.category?.displayName ?: "Speed Limit",
                    color = Color(0xFF8C9EB2),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VoiceAssistantBar(
    state: DashboardUiState,
    onToggleVoice: () -> Unit,
    onToggleMute: () -> Unit,
) {
    val active = state.voiceState.isListening || state.voiceState.isSpeaking ||
        state.voiceState.connectionState == VoiceConnectionState.CONNECTED

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CockpitCardBg,
        border = BorderStroke(1.dp, if (active) MintTeal.copy(alpha = 0.6f) else CockpitCardBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onToggleMute),
                shape = CircleShape,
                color = Color(0xFF0E292E),
                border = BorderStroke(1.5.dp, MintTeal.copy(alpha = 0.7f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Voice Waveform",
                        tint = MintTeal,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "KoPilot Voice",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(
                    text = state.voiceState.lastTranscript.ifBlank {
                        if (active) "Listening for your command..." else state.voiceState.statusMessage
                    },
                    color = Color(0xFF9FB0C3),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                modifier = Modifier
                    .size(46.dp)
                    .clickable(onClick = onToggleVoice),
                shape = CircleShape,
                color = if (active) MintTeal else Color(0xFF102A30),
                border = BorderStroke(2.dp, MintTeal),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.voiceState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Talk",
                        tint = if (active) CockpitDarkBg else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickInfoCardsRow(
    state: DashboardUiState,
    onOpenDestination: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val route = state.navigationState.activeRoute
    val destText = state.navigationState.destinationName ?: "Not set"
    val destSub = if (route != null) "${route.durationSeconds / 60} min" else null

    val w = state.weatherState
    val tempText = w.temperatureC?.let { "${"%.0f".format(it)}°C" } ?: "--"
    val condText = if (w.available) w.conditionSummary else "Unavailable"

    val obd = state.diagnosticsState
    val obdStatus = if (obd.obdConnected) {
        "${obd.engineRpm ?: "--"} RPM"
    } else {
        "Not connected"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickInfoCard(
            modifier = Modifier.weight(1f).clickable(onClick = onOpenDestination),
            icon = Icons.Default.Navigation,
            iconTint = MintTeal,
            title = "Destination",
            primaryValue = destText,
            secondaryValue = destSub,
            showChevron = true,
        )
        QuickInfoCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.WbCloudy,
            iconTint = KoPilotColors.WarningAmber,
            title = "Weather",
            primaryValue = tempText,
            secondaryValue = condText,
            showChevron = false,
        )
        QuickInfoCard(
            modifier = Modifier.weight(1f).clickable(onClick = onOpenSettings),
            icon = Icons.Default.DirectionsCar,
            iconTint = MintTeal,
            title = "Vehicle (OBD-II)",
            primaryValue = obdStatus,
            secondaryValue = obd.coolantTempC?.let { "$it°C" },
            showChevron = true,
        )
    }
}

@Composable
private fun QuickInfoCard(
    modifier: Modifier,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    primaryValue: String,
    secondaryValue: String?,
    showChevron: Boolean,
) {
    Surface(
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(14.dp),
        color = CockpitCardBg,
        border = BorderStroke(1.dp, CockpitCardBorder),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = primaryValue,
                        color = Color(0xFF9FB0C3),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (showChevron) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color(0xFF6B7C90),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (!secondaryValue.isNullOrBlank()) {
                    Text(
                        text = secondaryValue,
                        color = Color(0xFF7D90A5),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryActionButtonsRow(
    state: DashboardUiState,
    onToggleDrive: () -> Unit,
    onOpenNavigate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onToggleDrive,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isDrivingSessionActive) KoPilotColors.DangerRed else MintTeal,
                contentColor = if (state.isDrivingSessionActive) Color.White else CockpitDarkBg,
            ),
        ) {
            Icon(
                imageVector = if (state.isDrivingSessionActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (state.isDrivingSessionActive) "End Trip" else "Start Driving",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }

        Button(
            onClick = onOpenNavigate,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NavBlue,
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Navigate", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun BottomQuickActionsBar(
    alertsMuted: Boolean,
    onToggleMuteAlerts: () -> Unit,
    onCallEmergency: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BottomCircleAction(
            icon = if (alertsMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            label = if (alertsMuted) "Unmute" else "Mute Alerts",
            iconTint = Color.White,
            onClick = onToggleMuteAlerts,
        )
        BottomCircleAction(
            icon = Icons.Default.Call,
            label = "Emergency",
            iconTint = KoPilotColors.DangerRed,
            onClick = onCallEmergency,
        )
        BottomCircleAction(
            icon = Icons.Default.Route,
            label = "My Trips",
            iconTint = Color.White,
            onClick = onOpenTrips,
        )
        BottomCircleAction(
            icon = Icons.Default.Settings,
            label = "Settings",
            iconTint = Color.White,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun BottomCircleAction(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp),
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = CockpitCardBg,
            border = BorderStroke(1.dp, CockpitCardBorder),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFFB5C4D4), fontSize = 11.sp)
    }
}

@Composable
private fun TripsHistoryDialog(
    trips: List<TripEntity>,
    onDismiss: () -> Unit,
) {
    val formatter = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My Trips (${trips.size})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (trips.isEmpty()) {
                    Text("No saved trips yet. Tap Start Driving to record a trip.")
                } else {
                    trips.forEach { trip ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = trip.destinationLabel ?: "Local Drive",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${formatter.format(Date(trip.startedAtMillis))} • ${"%.1f".format(trip.distanceKm)} km • ${trip.durationSeconds / 60} min",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
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
