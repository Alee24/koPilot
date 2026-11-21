package ke.co.kopilot.feature.dashboard

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import ke.co.kopilot.core.database.preferences.VoiceMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import ke.co.kopilot.feature.diagnostics.ObdLiveTelemetry
import ke.co.kopilot.feature.diagnostics.ObdPidCapability
import ke.co.kopilot.feature.diagnostics.PidSupportStatus
import ke.co.kopilot.core.designsystem.component.KoPilotStatusPill
import ke.co.kopilot.core.designsystem.theme.KoPilotSpacing
import ke.co.kopilot.feature.sensors.LateralMotionState
import ke.co.kopilot.feature.sensors.RoadVibrationLevel
import ke.co.kopilot.feature.vision.LaneShiftEvent
import ke.co.kopilot.feature.vision.LocalRoadSignEntry
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.common.model.GpsSignalState
import ke.co.kopilot.core.common.model.SpeedAlertDecision
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.core.designsystem.theme.KoPilotColors
import ke.co.kopilot.feature.camera.RoadCameraPreview
import ke.co.kopilot.feature.location.SpeedZone
import ke.co.kopilot.feature.navigation.KoPilotNavigationMap
import ke.co.kopilot.feature.vision.TrafficLightSignal
import ke.co.kopilot.feature.voice.VoiceConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CockpitDarkBg = KoPilotClusterTokens.Background
private val CockpitCardBg = KoPilotClusterTokens.CardSurface
private val CockpitCardBorder = KoPilotClusterTokens.Border
private val MintTeal = KoPilotClusterTokens.PrimaryAccent
private val NavBlue = KoPilotClusterTokens.SecondaryAccent

@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenSensors: () -> Unit = {},
    onOpenRoadQuality: () -> Unit = {},
    onOpenDrivingBehaviour: () -> Unit = {},
    onOpenDrivingIntelligence: () -> Unit = {},
    onOpenVehicleIntelligence: () -> Unit = {},
    onOpenObd: () -> Unit = {},
    onOpenTrips: () -> Unit = {},
    onOpenNavigation: () -> Unit = {},
    onOpenSoundStudio: () -> Unit = {},
    onOpenSafetySettings: () -> Unit = {},
    onOpenLiveMap: () -> Unit = {},
    onOpenPerformanceThermal: () -> Unit = {},
    onOpenSmartwatchHealth: () -> Unit = {},
    onOpenSystemStatus: () -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
    onOpenFuelEfficiency: () -> Unit = {},
    onCallEmergency: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showTripsDialog by remember { mutableStateOf(false) }
    var showVisionDiagDialog by remember { mutableStateOf(false) }
    var showObdLiveDialog by remember { mutableStateOf(false) }
    var showSignsDbDialog by remember { mutableStateOf(false) }
    var showToolsDialog by remember { mutableStateOf(false) }
    var isCameraFullscreen by remember { mutableStateOf(false) }
    val isParked = (state.speedEvaluation.currentSpeedKmh ?: 0f) < 5f

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
                onOpenExternalGoogleMaps = { name, lat, lng ->
                    val navUri = Uri.parse("google.navigation:q=$lat,$lng&mode=d")
                    val intent = Intent(Intent.ACTION_VIEW, navUri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    runCatching { ctx.startActivity(intent) }.onFailure {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")))
                    }
                },
                onDismiss = { showDestinationDialog = false },
            )
        }

    var viewingLogFile by remember { mutableStateOf<File?>(null) }
    val shareTxtFile: (File) -> Unit = { logFile ->
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", logFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "KoPilot Trip Activity Log (${logFile.name})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(shareIntent, "Share Trip Activity Log (.txt)"))
        }
    }

    if (showTripsDialog) {
        TripsHistoryDialog(
            trips = state.savedTrips,
            activeLogFile = if (state.isDrivingSessionActive) viewModel.tripActivityLogger.getActiveLogFile() else null,
            findLogFile = { startedAt -> viewModel.tripActivityLogger.findLogFileForTrip(startedAt) },
            onViewLog = { file -> viewingLogFile = file },
            onShareLog = shareTxtFile,
            onDismiss = { showTripsDialog = false },
        )
    }

    viewingLogFile?.let { file ->
        TripLogViewerDialog(
            file = file,
            onShare = { shareTxtFile(file) },
            onDismiss = { viewingLogFile = null },
        )
    }

    if (showVisionDiagDialog) {
        VisionDiagnosticsDialog(
            state = state,
            isParked = isParked,
            onSavePlateEvidence = {
                state.visionState.lastPlateObservation?.let { obs ->
                    viewModel.visionPipeline.platePipeline.saveEncryptedEvidence(obs)
                }
            },
            onDeletePlateEvidence = {
                viewModel.visionPipeline.platePipeline.deleteAllEvidence()
            },
            evidenceCount = viewModel.visionPipeline.platePipeline.listEvidenceFiles().size,
            onOpenPerformanceThermal = {
                showVisionDiagDialog = false
                onOpenPerformanceThermal()
            },
            onDismiss = { showVisionDiagDialog = false },
        )
    }

    if (showObdLiveDialog) {
        val obdTel by viewModel.obdManager.liveTelemetry.collectAsStateWithLifecycle()
        val obdCaps by viewModel.obdManager.capabilities.collectAsStateWithLifecycle()
        val obdConn by viewModel.obdManager.connectionState.collectAsStateWithLifecycle()
        ObdLiveDashboardDialog(
            connectionStateName = obdConn.name,
            gpsSpeedKmh = state.speedEvaluation.currentSpeedKmh,
            telemetry = obdTel,
            capabilities = obdCaps,
            onDismiss = { showObdLiveDialog = false },
        )
    }

    if (showSignsDbDialog) {
        RoadSignsDatabaseDialog(
            signs = viewModel.visionPipeline.signCatalog.allEntries(),
            onDismiss = { showSignsDbDialog = false },
        )
    }

    if (showToolsDialog) {
        DashboardToolsDialog(
            state = state,
            isParked = isParked,
            onDismiss = { showToolsDialog = false },
            onOpenDrivingBehaviour = {
                showToolsDialog = false
                onOpenDrivingBehaviour()
            },
            onOpenDrivingIntelligence = {
                showToolsDialog = false
                onOpenDrivingIntelligence()
            },
            onOpenVehicleIntelligence = {
                showToolsDialog = false
                onOpenVehicleIntelligence()
            },
            onOpenRoadQuality = {
                showToolsDialog = false
                onOpenRoadQuality()
            },
            onOpenSensors = {
                showToolsDialog = false
                onOpenSensors()
            },
            onOpenObd = {
                showToolsDialog = false
                onOpenObd()
            },
            onOpenSoundStudio = {
                showToolsDialog = false
                onOpenSoundStudio()
            },
            onOpenTrips = {
                showToolsDialog = false
                onOpenTrips()
            },
            onOpenSignsDb = {
                showToolsDialog = false
                showSignsDbDialog = true
            },
            onOpenVisionDiag = {
                showToolsDialog = false
                showVisionDiagDialog = true
            },
            onOpenPerformanceThermal = {
                showToolsDialog = false
                onOpenPerformanceThermal()
            },
            onOpenSmartwatchHealth = {
                showToolsDialog = false
                onOpenSmartwatchHealth()
            },
            onOpenSystemStatus = {
                showToolsDialog = false
                onOpenSystemStatus()
            },
            onToggleMuteAlerts = viewModel::toggleAlertsMute,
            onCallEmergency = {
                showToolsDialog = false
                onCallEmergency(state.settings.emergencyContactPhone)
            },
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

    Scaffold(
        containerColor = screenBgColor,
        contentWindowInsets = WindowInsets.statusBars,
    ) { padding ->
        if (isCameraFullscreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(KoPilotSpacing.Sm),
            ) {
                ViewportCard(
                    state = state,
                    viewModel = viewModel,
                    isFullscreen = true,
                    onToggleFullscreen = { isCameraFullscreen = false },
                )
            }
        } else if (state.isDrivingSessionActive) {
            FullBleedArHudLanding(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onOpenMenu = { showToolsDialog = true },
                onOpenObdLive = { showObdLiveDialog = true },
                onOpenRoadQuality = onOpenRoadQuality,
                onOpenDrivingIntelligence = onOpenDrivingIntelligence,
                onOpenNavigation = onOpenNavigation,
                onOpenSmartwatchHealth = onOpenSmartwatchHealth,
                onOpenMaintenance = onOpenMaintenance,
                onOpenFuelEfficiency = onOpenFuelEfficiency,
                onToggleFullscreenCamera = { isCameraFullscreen = true },
            )
        } else {
            KoPilotCockpitLanding(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onOpenMenu = { showToolsDialog = true },
                onOpenSettings = onOpenSettings,
                onOpenObd = onOpenObd,
                onOpenVisionDiag = { showVisionDiagDialog = true },
                onOpenSignsDb = { showSignsDbDialog = true },
                onOpenRoadQuality = onOpenRoadQuality,
                onOpenDrivingBehaviour = onOpenDrivingBehaviour,
                onOpenDrivingIntelligence = onOpenDrivingIntelligence,
                onOpenVehicleIntelligence = onOpenVehicleIntelligence,
                onOpenTrips = onOpenTrips,
                onOpenSoundStudio = onOpenSoundStudio,
                onOpenNavigation = onOpenNavigation,
                onOpenSafetySettings = onOpenSafetySettings,
                onOpenLiveMap = onOpenLiveMap,
                onOpenSmartwatchHealth = onOpenSmartwatchHealth,
                onOpenMaintenance = onOpenMaintenance,
                onOpenFuelEfficiency = onOpenFuelEfficiency,
                onToggleFullscreenCamera = { isCameraFullscreen = true },
            )
        }
    }
}

@Composable
private fun ActiveAlertBanner(alertText: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF102A30),
        border = BorderStroke(1.dp, MintTeal.copy(alpha = 0.6f)),
    ) {
        Text(
            text = "Alert: $alertText",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun TopCockpitHeader(
    isOnline: Boolean,
    obdConnected: Boolean,
    hasVehicleWarning: Boolean = false,
    isDriving: Boolean,
    onOpenMenu: () -> Unit,
    onOpenObdLive: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            IconButton(onClick = onOpenMenu, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Menu, contentDescription = "Tools & Menu", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brush.linearGradient(listOf(MintTeal, NavBlue))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "K",
                    color = CockpitDarkBg,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "KoPilot",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
            )
            if (isDriving) {
                Spacer(Modifier.width(8.dp))
                KoPilotStatusPill(
                    label = "DRIVE",
                    accentColor = MintTeal,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (hasVehicleWarning) {
                KoPilotStatusPill(
                    label = "WARN",
                    accentColor = KoPilotColors.DangerRed,
                    modifier = Modifier.clickable(onClick = onOpenObdLive),
                )
            }
            if (obdConnected) {
                KoPilotStatusPill(
                    label = "OBD",
                    accentColor = MintTeal,
                    modifier = Modifier.clickable(onClick = onOpenObdLive),
                )
            }
            KoPilotStatusPill(
                label = if (isOnline) "Online" else "Offline",
                accentColor = if (isOnline) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.ModerateRoad,
            )
        }
    }
}

@Composable
private fun ViewportCard(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    showOverlayControls: Boolean = true,
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
                    cameraSensorEnabled = state.settings.sensorCameraEnabled,
                    analysisIntervalMs = viewModel.analysisIntervalFor(state.settings.detectionQuality),
                    zoomRatio = state.settings.cameraZoomRatio,
                    performanceController = viewModel.cameraController,
                    onStartDrivingClick = viewModel::startDriving,
                    onFrameListener = { imageProxy, rotation, ts ->
                        viewModel.visionPipeline.analyzeFrame(
                            imageProxy = imageProxy,
                            rotationDegrees = rotation,
                            timestampMillis = ts,
                            thermalModeName = state.cameraStatus.thermalState.name,
                            onConfirmedSign = viewModel::onConfirmedSign,
                        )
                    },
                )
                // Real-time on-device tracked object bounding boxes + labels
                Box(modifier = Modifier.fillMaxSize()) {
                    val labelPaint = remember {
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 30f
                            isFakeBoldText = true
                            isAntiAlias = true
                        }
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (obj in state.visionState.trackedObjects) {
                            val left = obj.normalizedLeft * size.width
                            val top = obj.normalizedTop * size.height
                            val right = obj.normalizedRight * size.width
                            val bottom = obj.normalizedBottom * size.height
                            val boxColor = when (obj.label) {
                                "Car (Braking)" -> Color(0xFFFF1744)
                                "Pedestrian", "Bicycle" -> KoPilotColors.WarningAmber
                                "Motorbike", "Tractor", "Lorry / Bus" -> Color(0xFFFF7043)
                                "Car", "Vehicle" -> MintTeal
                                else -> NavBlue
                            }
                            drawRect(
                                color = boxColor,
                                topLeft = Offset(left, top),
                                size = Size((right - left).coerceAtLeast(4f), (bottom - top).coerceAtLeast(4f)),
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                            val tagText = if (obj.trackingId > 0) "#${obj.trackingId} ${obj.label}" else obj.label
                            val tagTop = (top - 28f).coerceAtLeast(4f)
                            drawRect(
                                color = boxColor.copy(alpha = 0.85f),
                                topLeft = Offset(left, tagTop),
                                size = Size((tagText.length * 16f + 12f).coerceAtMost(size.width - left), 28f),
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                tagText,
                                left + 6f,
                                tagTop + 22f,
                                labelPaint,
                            )
                        }
                        // Highlight focused Sign ROI & LiteRT YOLO11n Road Sign detections
                        for (roi in state.visionState.signRoiBoxes) {
                            val left = roi.normalizedLeft * size.width
                            val top = roi.normalizedTop * size.height
                            val right = roi.normalizedRight * size.width
                            val bottom = roi.normalizedBottom * size.height
                            val isYoloSign = roi.label != "Sign ROI"
                            val roiColor = if (isYoloSign) MintTeal else Color.Yellow.copy(alpha = 0.7f)
                            drawRect(
                                color = roiColor,
                                topLeft = Offset(left, top),
                                size = Size((right - left).coerceAtLeast(4f), (bottom - top).coerceAtLeast(4f)),
                                style = Stroke(width = if (isYoloSign) 2.5.dp.toPx() else 1.5.dp.toPx()),
                            )
                            if (isYoloSign) {
                                val tagTop = (top - 26f).coerceAtLeast(4f)
                                drawRect(
                                    color = MintTeal.copy(alpha = 0.9f),
                                    topLeft = Offset(left, tagTop),
                                    size = Size((roi.label.length * 15f + 12f).coerceAtMost(size.width - left), 26f),
                                )
                                drawContext.canvas.nativeCanvas.drawText(
                                    roi.label,
                                    left + 6f,
                                    tagTop + 20f,
                                    labelPaint,
                                )
                            }
                        }
                        // Highlight detected bright red brake lights ahead
                        for (spot in state.visionState.brakeLightBoxes) {
                            val left = spot.normLeft * size.width
                            val top = spot.normTop * size.height
                            val right = spot.normRight * size.width
                            val bottom = spot.normBottom * size.height
                            drawRect(
                                color = Color(0xFFFF1744),
                                topLeft = Offset(left, top),
                                size = Size((right - left).coerceAtLeast(8f), (bottom - top).coerceAtLeast(8f)),
                                style = Stroke(width = 3.dp.toPx()),
                            )
                        }
                        // Drivable road surface coloring + blue lane lines when in standalone ViewportCard
                        if (showOverlayControls && state.isDrivingSessionActive && state.settings.sensorCameraEnabled) {
                            val lane = state.visionState.laneOverlay
                            val blueLane = Color(0xFF00B0FF)
                            val alertRed = Color(0xFFFF5252)
                            val isShifting = lane.shiftEvent != LaneShiftEvent.NONE

                            // Color the segmented drivable road polygon (clamped to lower half of screen)
                            if (lane.roadLeftPoints.size >= 2 && lane.roadRightPoints.size >= 2) {
                                val roadPath = Path().apply {
                                    val firstL = lane.roadLeftPoints.first()
                                    moveTo(firstL.first * size.width, firstL.second.coerceAtLeast(0.50f) * size.height)
                                    for (pt in lane.roadLeftPoints.drop(1)) {
                                        lineTo(pt.first * size.width, pt.second.coerceAtLeast(0.50f) * size.height)
                                    }
                                    for (pt in lane.roadRightPoints.reversed()) {
                                        lineTo(pt.first * size.width, pt.second.coerceAtLeast(0.50f) * size.height)
                                    }
                                    close()
                                }
                                drawPath(
                                    path = roadPath,
                                    color = if (isShifting) alertRed.copy(alpha = 0.22f) else blueLane.copy(alpha = 0.18f),
                                )
                            }

                            val lTop = Offset(lane.leftTopX * size.width, lane.leftTopY.coerceAtLeast(0.50f) * size.height)
                            val lBot = Offset(lane.leftBottomX * size.width, lane.leftBottomY * size.height)
                            val rTop = Offset(lane.rightTopX * size.width, lane.rightTopY.coerceAtLeast(0.50f) * size.height)
                            val rBot = Offset(lane.rightBottomX * size.width, lane.rightBottomY * size.height)
                            val leftColor = if (lane.shiftEvent == LaneShiftEvent.LANE_DEPARTURE_LEFT || lane.shiftEvent == LaneShiftEvent.SHIFTING_LEFT) alertRed else blueLane
                            val rightColor = if (lane.shiftEvent == LaneShiftEvent.LANE_DEPARTURE_RIGHT || lane.shiftEvent == LaneShiftEvent.SHIFTING_RIGHT) alertRed else blueLane
                            val dashEffect = if (lane.lanesDetected) null else PathEffect.dashPathEffect(floatArrayOf(18f, 14f), 0f)
                            val laneStroke = if (lane.lanesDetected) 4.dp.toPx() else 2.5.dp.toPx()
                            val laneAlpha = if (lane.lanesDetected) 0.95f else 0.70f

                            drawLine(
                                color = leftColor.copy(alpha = laneAlpha),
                                start = lTop,
                                end = lBot,
                                strokeWidth = laneStroke,
                                pathEffect = dashEffect,
                            )
                            drawLine(
                                color = rightColor.copy(alpha = laneAlpha),
                                start = rTop,
                                end = rBot,
                                strokeWidth = laneStroke,
                                pathEffect = dashEffect,
                            )
                        }
                    }
                    // Lane / Turn status pill at bottom-center of camera viewport (only when standalone overlay controls shown)
                    if (showOverlayControls && state.isDrivingSessionActive) {
                        val lat = state.motionAnalysis.lateralState
                        val shift = state.visionState.laneOverlay.shiftEvent
                        val laneBadgeText = when {
                            lat == LateralMotionState.SHARP_CORNERING -> "SHARP TURN"
                            lat == LateralMotionState.LEFT_TURN -> "TURNING LEFT"
                            lat == LateralMotionState.RIGHT_TURN -> "TURNING RIGHT"
                            lat == LateralMotionState.LANE_SHIFT_LEFT || shift == LaneShiftEvent.SHIFTING_LEFT || shift == LaneShiftEvent.LANE_DEPARTURE_LEFT -> "SHIFTING LEFT"
                            lat == LateralMotionState.LANE_SHIFT_RIGHT || shift == LaneShiftEvent.SHIFTING_RIGHT || shift == LaneShiftEvent.LANE_DEPARTURE_RIGHT -> "SHIFTING RIGHT"
                            state.visionState.laneOverlay.lanesDetected -> "LANES LOCKED"
                            else -> null
                        }
                        if (laneBadgeText != null) {
                            val isAlert = laneBadgeText != "LANES LOCKED"
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 10.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAlert) Color(0xCCFF6F00) else Color(0xB3101820),
                                border = BorderStroke(1.dp, Color(0xFFFFD600)),
                            ) {
                                Text(
                                    text = laneBadgeText,
                                    color = if (isAlert) Color.White else Color(0xFFFFD600),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
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
                            border = BorderStroke(
                                4.dp,
                                if (obs.isUncertain) KoPilotColors.WarningAmber else Color(0xFFD32F2F),
                            ),
                        ) {
                            Text(
                                text = when {
                                    obs.isUncertain -> "? ${limit ?: obs.category.displayName.take(8)}"
                                    limit != null -> limit.toString()
                                    else -> (obs.customLabel ?: obs.category.displayName).take(12)
                                },
                                color = Color(0xFF101820),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (limit != null && !obs.isUncertain) 16.sp else 11.sp,
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

            if (showOverlayControls) {
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

                // Top-right controls (Zoom pill when in Camera view + Expand/Swap button)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.settings.cameraPrimaryLayout) {
                        Surface(
                            modifier = Modifier.clickable(onClick = viewModel::cycleCameraZoom),
                            shape = RoundedCornerShape(50),
                            color = Color(0xCC0D141C),
                        ) {
                            Text(
                                text = "Zoom ${"%.1f".format(state.settings.cameraZoomRatio)}x",
                                color = MintTeal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        val dest = state.navigationState.destinationLatLng
                        val destName = state.navigationState.destinationName ?: "Destination"
                        if (dest != null) {
                            val ctx = LocalContext.current
                            Surface(
                                modifier = Modifier.clickable {
                                    val navUri = Uri.parse("google.navigation:q=${dest.latitude},${dest.longitude}&mode=d")
                                    val intent = Intent(Intent.ACTION_VIEW, navUri).apply {
                                        setPackage("com.google.android.apps.maps")
                                    }
                                    runCatching { ctx.startActivity(intent) }.onFailure {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${dest.latitude},${dest.longitude}?q=${dest.latitude},${dest.longitude}(${Uri.encode(destName)})")))
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                color = NavBlue,
                            ) {
                                Text(
                                    text = "Google Maps",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.clickable(onClick = viewModel::toggleCameraPrimaryLayout),
                        shape = RoundedCornerShape(50),
                        color = Color(0xCC0D141C),
                    ) {
                        Text(
                            text = if (state.settings.cameraPrimaryLayout) "Map View" else "Camera View",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Surface(
                        modifier = Modifier.clickable(onClick = onToggleFullscreen),
                        shape = RoundedCornerShape(50),
                        color = Color(0xCC0D141C),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                                contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen Camera",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = if (isFullscreen) "Exit Full" else "Fullscreen",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
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
    val fix = state.locationState.currentFix
    val isGpsAvailable = state.speedEvaluation.signalState != GpsSignalState.UNAVAILABLE && fix != null
    val isStale = fix != null && (System.currentTimeMillis() - fix.timestampMillis) > 6_000L
    val gpsSpeed = if (isGpsAvailable && !isStale) state.speedEvaluation.currentSpeedKmh else null
    val speedKmh = when {
        gpsSpeed != null && gpsSpeed >= 1f -> gpsSpeed
        state.diagnosticsState.obdSpeedKmh != null -> state.diagnosticsState.obdSpeedKmh!!.toFloat()
        else -> gpsSpeed
    }
    val speedText = speedKmh?.let { "%.0f".format(it) } ?: "--"
    val isOverspeed = state.speedEvaluation.decision == SpeedAlertDecision.POSSIBLE_OVERSPEED_WARNING ||
        state.speedZone == SpeedZone.REDUCE_SPEED ||
        state.speedZone == SpeedZone.DANGEROUS_DRIVING
    val accentColor = when {
        isOverspeed -> KoPilotColors.DangerRed
        state.speedZone == SpeedZone.OPTIMAL_CRUISE -> NavBlue
        state.speedZone == SpeedZone.ENCOURAGE_PACE -> KoPilotColors.WarningAmber
        else -> MintTeal
    }
    val progress = ((speedKmh ?: 0f) / 140f).coerceIn(0f, 1f)
    val speedAccText = fix?.speedAccuracyKmh?.let { "±${"%.0f".format(it)} km/h" }
        ?: fix?.horizontalAccuracyMeters?.let { "±${"%.0f".format(it)}m" }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CockpitCardBg,
        border = BorderStroke(1.dp, CockpitCardBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Left: GPS + Accuracy + Traffic Light compact column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenSensors),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SatelliteAlt,
                        contentDescription = null,
                        tint = when (state.speedEvaluation.signalState) {
                            GpsSignalState.GOOD -> MintTeal
                            GpsSignalState.UNCERTAIN -> KoPilotColors.WarningAmber
                            GpsSignalState.UNAVAILABLE -> Color(0xFF6B7C90)
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = when (state.speedEvaluation.signalState) {
                            GpsSignalState.GOOD -> "GPS Strong"
                            GpsSignalState.UNCERTAIN -> "GPS Weak"
                            GpsSignalState.UNAVAILABLE -> "GPS Off"
                        },
                        color = when (state.speedEvaluation.signalState) {
                            GpsSignalState.GOOD -> MintTeal
                            GpsSignalState.UNCERTAIN -> KoPilotColors.WarningAmber
                            GpsSignalState.UNAVAILABLE -> Color(0xFF8C9EB2)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Text(
                    text = buildString {
                        if (state.locationState.satellitesInView > 0) {
                            append("${state.locationState.satellitesUsedInFix}/${state.locationState.satellitesInView} sats")
                        } else {
                            append("No fix")
                        }
                        if (speedAccText != null && isGpsAvailable) {
                            append(" · $speedAccText")
                        }
                    },
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val tl = state.visionState.trafficLightSignal
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(
                                if (tl == TrafficLightSignal.RED) KoPilotColors.DangerRed else Color(0xFF3A1A1D),
                            ),
                        )
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(
                                if (tl == TrafficLightSignal.AMBER) KoPilotColors.WarningAmber else Color(0xFF3A2F15),
                            ),
                        )
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(
                                if (tl == TrafficLightSignal.GREEN) MintTeal else Color(0xFF15382F),
                            ),
                        )
                    }
                    Text(
                        text = when (tl) {
                            TrafficLightSignal.RED -> "RED"
                            TrafficLightSignal.AMBER -> "AMBER"
                            TrafficLightSignal.GREEN -> "GREEN"
                            TrafficLightSignal.UNCERTAIN -> "UNCERTAIN"
                            TrafficLightSignal.NONE -> "Lights"
                        },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Center: Compact Digital Speed Readout + Horizontal Bar
            Column(
                modifier = Modifier.weight(1.1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = speedText,
                        color = if (isOverspeed) KoPilotColors.DangerRed else Color.White,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 40.sp,
                    )
                    Text(
                        text = "km/h",
                        color = MintTeal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = accentColor,
                    trackColor = Color(0xFF1D2A36),
                )
            }

            // Right: Speed Limit Sign Badge
            val limit = state.visionState.lastSpeedLimitKmh
                ?: state.navigationState.roadIntelligence.mappedSpeedLimitKmh
                ?: state.speedEvaluation.applicableSpeedLimitKmh
            Column(
                modifier = Modifier.weight(0.75f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(4.dp, Color(0xFFD32F2F)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = limit?.toString() ?: "--",
                            color = Color(0xFF101820),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                        )
                    }
                }
                Text(
                    text = state.visionState.lastObservation?.category?.displayName ?: "Limit",
                    color = KoPilotClusterTokens.SecondaryText,
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
    onSelectVoiceMode: (VoiceMode) -> Unit = {},
) {
    val active = state.voiceState.isListening || state.voiceState.isSpeaking ||
        state.voiceState.connectionState == VoiceConnectionState.CONNECTED

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = CockpitCardBg,
        border = BorderStroke(1.dp, if (active) MintTeal.copy(alpha = 0.6f) else CockpitCardBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active || state.voiceState.isMuted) {
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(onClick = onToggleMute),
                        shape = CircleShape,
                        color = Color(0xFF0E292E),
                        border = BorderStroke(1.dp, MintTeal.copy(alpha = 0.7f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Voice Waveform",
                                tint = MintTeal,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "KoPilot Voice",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = state.voiceState.lastTranscript.ifBlank {
                            if (active) "Listening..." else state.voiceState.statusMessage
                        },
                        color = KoPilotClusterTokens.SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onToggleVoice),
                    shape = CircleShape,
                    color = if (active) MintTeal else Color(0xFF102A30),
                    border = BorderStroke(1.5.dp, MintTeal),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (state.voiceState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Talk",
                            tint = if (active) CockpitDarkBg else Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Three Voice Modes quick selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    VoiceMode.SMART_CO_DRIVER to "Smart Co-Driver",
                    VoiceMode.QUIET to "Quiet",
                    VoiceMode.LEARNING to "Learning",
                ).forEach { (mode, label) ->
                    val selected = state.settings.voiceMode == mode
                    Surface(
                        onClick = { onSelectVoiceMode(mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MintTeal.copy(alpha = 0.2f) else CockpitDarkBg,
                        border = BorderStroke(1.dp, if (selected) MintTeal else CockpitCardBorder),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = label,
                            color = if (selected) MintTeal else KoPilotClusterTokens.SecondaryText,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickInfoCardsRow(
    state: DashboardUiState,
    onOpenDestination: () -> Unit,
    onOpenObd: () -> Unit,
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
            modifier = Modifier.weight(1f).clickable(onClick = onOpenObd),
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
    onCallEmergency: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onToggleDrive,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isDrivingSessionActive) KoPilotColors.DangerRed else MintTeal,
                contentColor = if (state.isDrivingSessionActive) Color.White else CockpitDarkBg,
            ),
        ) {
            Icon(
                imageVector = if (state.isDrivingSessionActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (state.isDrivingSessionActive) "End Trip" else "Start Driving",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Button(
            onClick = onOpenNavigate,
            modifier = Modifier.weight(1.05f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NavBlue,
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Default.FactCheck, contentDescription = "Check All Systems", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (state.isSystemCheckRunning) "Checking..." else "Check All Systems",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Surface(
            onClick = onCallEmergency,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF2B1116),
            border = BorderStroke(1.dp, KoPilotColors.DangerRed.copy(alpha = 0.6f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Emergency Call",
                    tint = KoPilotColors.DangerRed,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun KoPilotCockpitLanding(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenObd: () -> Unit,
    onOpenVisionDiag: () -> Unit,
    onOpenSignsDb: () -> Unit,
    onOpenRoadQuality: () -> Unit,
    onOpenDrivingBehaviour: () -> Unit,
    onOpenDrivingIntelligence: () -> Unit,
    onOpenVehicleIntelligence: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenSoundStudio: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenSafetySettings: () -> Unit,
    onOpenLiveMap: () -> Unit,
    onOpenSmartwatchHealth: () -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
    onOpenFuelEfficiency: () -> Unit = {},
    onToggleFullscreenCamera: () -> Unit,
) {
    val intel = state.intelligenceState
    val motion = state.motionAnalysis
    val stats = motion.behaviourStats
    val gpsKmh = state.speedEvaluation.currentSpeedKmh?.toInt()
    val obdKmh = state.diagnosticsState.obdSpeedKmh
    val speedKmh = when {
        gpsKmh != null && gpsKmh > 0 -> gpsKmh
        obdKmh != null -> obdKmh
        else -> gpsKmh ?: 0
    }
    val obdConnected = state.diagnosticsState.obdConnected
    val hasDtc = state.diagnosticsState.dtcCodes.isNotEmpty()
    val healthLabel = when {
        hasDtc -> "Check DTC (${state.diagnosticsState.dtcCodes.size})"
        (state.diagnosticsState.coolantTempC ?: 0) >= 102 -> "Coolant High"
        obdConnected -> "Good"
        else -> "OBD Offline"
    }
    val healthColor = when {
        hasDtc || (state.diagnosticsState.coolantTempC ?: 0) >= 102 -> KoPilotClusterTokens.VeryRough
        obdConnected -> KoPilotClusterTokens.SmoothRoad
        else -> KoPilotClusterTokens.SecondaryText
    }
    val roadLabel = when (motion.roadVibrationLevel) {
        RoadVibrationLevel.SMOOTH -> "Smooth"
        RoadVibrationLevel.MODERATE_VIBRATION -> "Moderate"
        RoadVibrationLevel.ROUGH_ROAD_EXPERIMENTAL -> "Rough"
        RoadVibrationLevel.VERY_ROUGH -> "Very Rough"
        RoadVibrationLevel.UNKNOWN -> "Standby"
    }
    val roadColor = when (motion.roadVibrationLevel) {
        RoadVibrationLevel.SMOOTH -> KoPilotClusterTokens.SmoothRoad
        RoadVibrationLevel.MODERATE_VIBRATION -> KoPilotClusterTokens.ModerateRoad
        RoadVibrationLevel.ROUGH_ROAD_EXPERIMENTAL -> KoPilotClusterTokens.RoughRoad
        RoadVibrationLevel.VERY_ROUGH -> KoPilotClusterTokens.VeryRough
        RoadVibrationLevel.UNKNOWN -> KoPilotClusterTokens.SecondaryText
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. Header: Animated Menu Pill + KoPilot Brand + Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = onOpenMenu,
                    color = KoPilotClusterTokens.GlassCardSurface,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Menu",
                            tint = KoPilotClusterTokens.PrimaryAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Menu",
                            style = MaterialTheme.typography.labelMedium,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Column {
                    Text(
                        text = "KoPilot",
                        style = MaterialTheme.typography.titleLarge,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "AI Driving Copilot",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onOpenLiveMap, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "Live Map",
                        tint = KoPilotClusterTokens.SecondaryAccent,
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = KoPilotClusterTokens.PrimaryText,
                    )
                }
            }
        }

        // 2. Status Pills Row: OBD Connected | AI Offline ✓
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = onOpenObd,
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                color = KoPilotClusterTokens.GlassCardSurface,
                border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (obdConnected) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (obdConnected) "OBD Connected" else "OBD Disconnected",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                onClick = onOpenVisionDiag,
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                color = KoPilotClusterTokens.GlassCardSurface,
                border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (state.visionState.modelLoaded) "AI Offline Ready" else "AI Vision Standby",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 2b. Compact Smartwatch Heart Rate & Optional Pre-Drive Sleep Context Row
        val hrState = state.heartRateState
        val sleepContext = state.sleepAnalyticsState.trendAnalysis.preDriveContextText
        Surface(
            onClick = onOpenSmartwatchHealth,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = KoPilotClusterTokens.GlassCardSurface,
            border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hrState.isLiveReading && !hrState.isStale && hrState.currentBpm != null) {
                            "Smartwatch Live: ${hrState.currentBpm} BPM"
                        } else {
                            "Smartwatch & Health: ${state.smartwatchDiagnostics.connectionState.displayName}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hrState.isLiveReading && !hrState.isStale) {
                            KoPilotClusterTokens.SmoothRoad
                        } else {
                            KoPilotClusterTokens.PrimaryText
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sleepContext ?: "Tap to connect BLE smartwatch or sync Health Connect sleep records",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (hrState.isLiveReading && !hrState.isStale) "${hrState.displayBpmText} BPM" else "Open >",
                    style = MaterialTheme.typography.labelMedium,
                    color = KoPilotClusterTokens.PrimaryAccent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 3. Road Preview Card (flexible height so entire landing page fits without scrolling)
        Surface(
            onClick = { viewModel.startDriving() },
            shape = RoundedCornerShape(14.dp),
            color = KoPilotClusterTokens.CardSurface,
            border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 96.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.kopilot_landing_road),
                    contentDescription = "KoPilot Road Preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color(0x66071019),
                                0.5f to Color(0x22071019),
                                1.0f to Color(0xB3071019),
                            ),
                        ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = Color(0xBF071019),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(KoPilotClusterTokens.PrimaryAccent, CircleShape),
                            )
                            Text(
                                text = "Front Camera • Auto Ready",
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.PrimaryText,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Surface(
                        color = KoPilotClusterTokens.PrimaryAccent,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = "Tap Start",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.Background,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        // 4. Speedometer + Vehicle Health / Fuel Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left Circular Speedometer
            Surface(
                onClick = {
                    if (state.isDrivingSessionActive) viewModel.endTrip() else viewModel.startDriving()
                },
                shape = RoundedCornerShape(14.dp),
                color = KoPilotClusterTokens.GlassCardSurface,
                border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
                modifier = Modifier.size(104.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                        val stroke = 8.dp.toPx()
                        drawArc(
                            color = KoPilotClusterTokens.ElevatedSurface,
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                        val sweep = ((speedKmh / 140f).coerceIn(0.05f, 1f)) * 270f
                        drawArc(
                            color = KoPilotClusterTokens.PrimaryAccent,
                            startAngle = 135f,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$speedKmh",
                            style = MaterialTheme.typography.headlineLarge,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            text = "km/h",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                        Text(
                            text = if (state.isDrivingSessionActive) "DRIVING" else "TAP START",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isDrivingSessionActive) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.PrimaryAccent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Right Stacked Vehicle Health + Fuel Cards
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    onClick = onOpenVehicleIntelligence,
                    shape = RoundedCornerShape(12.dp),
                    color = KoPilotClusterTokens.GlassCardSurface,
                    border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = healthColor,
                                modifier = Modifier.size(24.dp),
                            )
                            Column {
                                Text(
                                    text = "Vehicle Health",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.SecondaryText,
                                )
                                Text(
                                    text = healthLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = healthColor,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(">", color = KoPilotClusterTokens.SecondaryText, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(
                    onClick = onOpenFuelEfficiency,
                    shape = RoundedCornerShape(12.dp),
                    color = KoPilotClusterTokens.GlassCardSurface,
                    border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalGasStation,
                                contentDescription = null,
                                tint = KoPilotClusterTokens.ModerateRoad,
                                modifier = Modifier.size(24.dp),
                            )
                            Column {
                                Text(
                                    text = "Fuel Efficiency",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.SecondaryText,
                                )
                                Text(
                                    text = "${intel.fuelEfficiencyScore}/100",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(">", color = KoPilotClusterTokens.SecondaryText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 5. 3-Column Metrics Strip: Safety Score | Road Condition | Last Event
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LandingStatTile(
                title = "Safety Score",
                value = "${intel.safetyScore}/100",
                valueColor = KoPilotClusterTokens.SmoothRoad,
                onClick = onOpenDrivingIntelligence,
                modifier = Modifier.weight(1f),
            )
            LandingStatTile(
                title = "Road Condition",
                value = roadLabel,
                valueColor = roadColor,
                onClick = onOpenRoadQuality,
                modifier = Modifier.weight(1f),
            )
            LandingStatTile(
                title = "Last Event",
                value = intel.latestEvent?.title ?: "Clean",
                valueColor = if (intel.latestEvent != null) KoPilotClusterTokens.ModerateRoad else KoPilotClusterTokens.PrimaryText,
                onClick = onOpenDrivingIntelligence,
                modifier = Modifier.weight(1f),
            )
        }

        // 5b. Accident Monitoring & Mount Calibration Status Banner (tap to open Safety & Emergency)
        Surface(
            onClick = onOpenSafetySettings,
            shape = RoundedCornerShape(12.dp),
            color = KoPilotClusterTokens.GlassCardSurface,
            border = BorderStroke(
                1.dp,
                if (state.settings.accidentMonitoringEnabled) KoPilotClusterTokens.SmoothRoad.copy(alpha = 0.5f) else KoPilotClusterTokens.Border,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (state.settings.accidentMonitoringEnabled) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                            CircleShape,
                        ),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.settings.accidentMonitoringEnabled) "Accident Monitoring Ready" else "Accident Monitoring Off",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Impact • Rollover • Swerve • Mount: ${state.mountCalibrationStatus.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "Safety ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.PrimaryAccent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 6. 2x3 Feature Action Grid (with Live Map & Safety)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LandingActionTile("OBD Diagnostics", Icons.Default.Build, KoPilotClusterTokens.SecondaryAccent, onOpenObd, Modifier.weight(1f))
            LandingActionTile("Live Map", Icons.Default.Map, KoPilotClusterTokens.PrimaryAccent, onOpenLiveMap, Modifier.weight(1f))
            LandingActionTile("Safety & SOS", Icons.Default.Shield, KoPilotClusterTokens.SmoothRoad, onOpenSafetySettings, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LandingActionTile("Trips & Maps", Icons.Default.Route, KoPilotClusterTokens.SecondaryAccent, onOpenTrips, Modifier.weight(1f))
            LandingActionTile("AI Assistant", Icons.Default.SmartToy, KoPilotClusterTokens.PrimaryAccent, onOpenVehicleIntelligence, Modifier.weight(1f))
            LandingActionTile("Sound Studio", Icons.Default.GraphicEq, KoPilotClusterTokens.PrimaryAccent, onOpenSoundStudio, Modifier.weight(1f))
        }

        // 6b. Vehicle Mileage & Maintenance Summary Card
        Surface(
            onClick = onOpenMaintenance,
            shape = RoundedCornerShape(12.dp),
            color = KoPilotClusterTokens.GlassCardSurface,
            border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val s = state.settings
            val odoStr = s.officialOdometerKm?.let { "%,d km".format(java.util.Locale.US, it.toInt()) } ?: "Not Set"
            val odoSource = when (s.officialOdometerSource) {
                "ECU" -> "ECU"
                "MANUAL" -> "Manual"
                else -> "Unset"
            }
            val currentTripKm = (state.locationState.distanceTraveledMeters / 1000.0).takeIf { state.isDrivingSessionActive }
                ?: s.lastTripDistanceKm
            val nextSvc = state.evaluatedServices.firstOrNull()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Odometer: $odoStr ($odoSource) • ${if (state.isDrivingSessionActive) "Trip" else "Last"}: ${"%.1f".format(java.util.Locale.US, currentTripKm)} km",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = nextSvc?.let {
                            val remStr = it.kmRemaining?.let { km -> "${km.toInt()} km" } ?: "${it.daysRemaining}d"
                            "Next: ${it.schedule.title} ($remStr left • ${it.urgency.label})"
                        } ?: "Tap to configure service schedule",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "Service ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.PrimaryAccent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Quick Drive / Navigate / Voice Control Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (state.isDrivingSessionActive) viewModel.endTrip() else viewModel.startDriving()
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isDrivingSessionActive) KoPilotClusterTokens.VeryRough else KoPilotClusterTokens.PrimaryAccent,
                    contentColor = if (state.isDrivingSessionActive) Color.White else KoPilotClusterTokens.Background,
                ),
            ) {
                Text(if (state.isDrivingSessionActive) "Stop Driving" else "Start Driving", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onOpenNavigation,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, KoPilotClusterTokens.SecondaryAccent),
            ) {
                Text("Navigate", color = KoPilotClusterTokens.SecondaryAccent, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = viewModel::toggleVoiceConnection,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Voice", tint = KoPilotClusterTokens.PrimaryAccent)
            }
        }

        // 7. Compact Recent Driving Event Strip
        Surface(
            onClick = onOpenDrivingIntelligence,
            shape = RoundedCornerShape(12.dp),
            color = KoPilotClusterTokens.GlassCardSurface,
            border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val latest = intel.recentEvents.firstOrNull()
                Text(
                    text = latest?.let { "${it.title}: ${it.detail}" } ?: "No recent driving warnings",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (latest != null) KoPilotClusterTokens.ModerateRoad else KoPilotClusterTokens.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text("View All >", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryAccent, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LandingStatTile(
    title: String,
    value: String,
    valueColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = KoPilotClusterTokens.GlassCardSurface,
        border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
        modifier = modifier.height(54.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = KoPilotClusterTokens.SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LandingActionTile(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = KoPilotClusterTokens.GlassCardSurface,
        border = BorderStroke(1.dp, KoPilotClusterTokens.SubtleGlassBorder),
        modifier = modifier.height(56.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FullBleedArHudLanding(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onOpenMenu: () -> Unit,
    onOpenObdLive: () -> Unit,
    onOpenRoadQuality: () -> Unit,
    onOpenDrivingIntelligence: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenSmartwatchHealth: () -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
    onOpenFuelEfficiency: () -> Unit = {},
    onToggleFullscreenCamera: () -> Unit,
) {
    val hudGlassBg = Color(0xCC071019)
    val hudGlassBorder = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.40f)
    val intel = state.intelligenceState
    val motion = state.motionAnalysis
    val stats = motion.behaviourStats
    val gpsKmh = state.speedEvaluation.currentSpeedKmh?.toInt()
    val obdKmh = state.diagnosticsState.obdSpeedKmh
    val speedKmh = when {
        gpsKmh != null && gpsKmh > 0 -> gpsKmh
        obdKmh != null -> obdKmh
        else -> gpsKmh ?: 0
    }
    val limitKmh = state.speedEvaluation.applicableSpeedLimitKmh ?: state.visionState.lastSpeedLimitKmh
    val satCount = state.locationState.satellitesUsedInFix
    val obdConnected = state.diagnosticsState.obdConnected
    val rpm = state.diagnosticsState.engineRpm
    val lateralG = motion.lateralAccelMps2 / 9.81f

    val safetyAnim by androidx.compose.animation.core.animateIntAsState(
        targetValue = intel.safetyScore,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "hudSafety",
    )
    val fuelAnim by androidx.compose.animation.core.animateIntAsState(
        targetValue = intel.fuelEfficiencyScore,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "hudFuel",
    )

    // Smooth sensor-driven horizontal bend for blue AR road lane lines:
    // Positive = turning RIGHT (compass heading increasing / clockwise yaw / right turn) -> lines curve RIGHT (+X).
    // Negative = turning LEFT (compass heading decreasing / counter-clockwise yaw / left turn) -> lines curve LEFT (-X).
    val turnStateBiasDp = when (motion.lateralState) {
        LateralMotionState.RIGHT_TURN -> 36f
        LateralMotionState.LEFT_TURN -> -36f
        LateralMotionState.LANE_SHIFT_RIGHT -> 20f
        LateralMotionState.LANE_SHIFT_LEFT -> -20f
        LateralMotionState.SHARP_CORNERING ->
            if (state.compassReading.headingDeltaDegrees >= 0f || motion.yawRateRadPerSec < 0f) 52f else -52f
        LateralMotionState.STRAIGHT -> 0f
    }
    val rawTurnRightDp = (
        state.compassReading.headingDeltaDegrees * 1.8f +
            (-motion.yawRateRadPerSec) * 58f +
            (-motion.lateralAccelMps2) * 5.5f +
            turnStateBiasDp
        ).coerceIn(-120f, 120f)
    val animatedTurnOffsetDp by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rawTurnRightDp,
        animationSpec = androidx.compose.animation.core.tween(140),
        label = "hudLaneTurnOffset",
    )
    val animatedHorizonShiftDp by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (motion.pitchDegrees * 0.8f).coerceIn(-18f, 18f),
        animationSpec = androidx.compose.animation.core.tween(140),
        label = "hudHorizonPitch",
    )

    Box(modifier = modifier.background(KoPilotClusterTokens.Background)) {
        // 1. Full-Bleed CameraX Feed Backdrop (suppress duplicate ViewportCard overlay pills so HUD buttons never overlap)
        ViewportCard(
            state = state,
            viewModel = viewModel,
            isFullscreen = true,
            onToggleFullscreen = onToggleFullscreenCamera,
            showOverlayControls = false,
        )

        // 2. Road-Aligned Blue AR Lane Lines Moving with Sensors & Road Detection
        val lane = state.visionState.laneOverlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color(0x99071019),
                    0.26f to Color(0x14071019),
                    0.58f to Color(0x26071019),
                    1.0f to Color(0xEB071019),
                ),
            )

            // Align the blue HUD lines with the road corridor in the open camera viewport (above the speedometer card)
            val horizonY = (size.height * 0.42f + animatedHorizonShiftDp.dp.toPx())
                .coerceIn(size.height * 0.37f, size.height * 0.48f)
            val bottomY = size.height * 0.62f

            val useDetectedRoad = lane.lanesDetected || lane.roadSurfaceDetected
            val baseLeftBotX = (if (useDetectedRoad) lane.leftBottomX.coerceIn(0.10f, 0.38f) else 0.16f) * size.width
            val baseRightBotX = (if (useDetectedRoad) lane.rightBottomX.coerceIn(0.62f, 0.90f) else 0.84f) * size.width
            val baseLeftTopX = (if (useDetectedRoad) lane.leftTopX.coerceIn(0.40f, 0.48f) else 0.46f) * size.width
            val baseRightTopX = (if (useDetectedRoad) lane.rightTopX.coerceIn(0.52f, 0.60f) else 0.54f) * size.width

            val turnShiftPx = animatedTurnOffsetDp.dp.toPx() * 1.4f
            val topLeftX = (baseLeftTopX + turnShiftPx).coerceIn(size.width * 0.08f, size.width * 0.78f)
            val topRightX = (baseRightTopX + turnShiftPx).coerceIn(topLeftX + 26.dp.toPx(), size.width * 0.92f)
            val midY = (bottomY + horizonY) * 0.50f
            val ctrlLeftX = (baseLeftBotX + topLeftX) * 0.5f + turnShiftPx * 0.45f
            val ctrlRightX = (baseRightBotX + topRightX) * 0.5f + turnShiftPx * 0.45f

            val hudTeal = Color(0xFF18E2B8)
            val isDeparting = lane.shiftEvent == LaneShiftEvent.LANE_DEPARTURE_LEFT ||
                lane.shiftEvent == LaneShiftEvent.LANE_DEPARTURE_RIGHT
            val primaryLineColor = if (isDeparting) Color(0xFFFF5252) else hudTeal

            // Subtle dashed horizon line matching WhatsApp Image 2026-11-19 at 19.07.33
            drawLine(
                color = hudTeal.copy(alpha = 0.30f),
                start = Offset(24.dp.toPx(), horizonY),
                end = Offset(size.width - 24.dp.toPx(), horizonY),
                strokeWidth = 1.4.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
            )

            // Crisp Dashed Blue/Teal Perspective Lane Lines (bend right when turning right, left when turning left)
            val leftPath = Path().apply {
                moveTo(baseLeftBotX, bottomY)
                quadraticTo(ctrlLeftX, midY, topLeftX, horizonY)
            }
            val rightPath = Path().apply {
                moveTo(baseRightBotX, bottomY)
                quadraticTo(ctrlRightX, midY, topRightX, horizonY)
            }
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 14f))

            drawPath(
                path = leftPath,
                color = primaryLineColor.copy(alpha = 0.92f),
                style = Stroke(width = 3.2.dp.toPx(), pathEffect = dashEffect, cap = StrokeCap.Round),
            )
            drawPath(
                path = rightPath,
                color = primaryLineColor.copy(alpha = 0.92f),
                style = Stroke(width = 3.2.dp.toPx(), pathEffect = dashEffect, cap = StrokeCap.Round),
            )

            // Dynamic Directional Turn Arrow pointing toward the turn direction when turning
            val turnMagnitude = kotlin.math.abs(animatedTurnOffsetDp)
            if (turnMagnitude >= 10f) {
                val turningRight = animatedTurnOffsetDp > 0f
                val arrowCenterX = ((topLeftX + topRightX) * 0.5f)
                val arrowCenterY = horizonY + (bottomY - horizonY) * 0.24f
                val arrowDir = if (turningRight) 1f else -1f
                val arrowLen = 36.dp.toPx()
                val headSize = 14.dp.toPx()

                val startPt = Offset(arrowCenterX - arrowDir * (arrowLen * 0.5f), arrowCenterY)
                val tipPt = Offset(arrowCenterX + arrowDir * (arrowLen * 0.5f), arrowCenterY)
                val wingTop = Offset(tipPt.x - arrowDir * headSize, tipPt.y - headSize * 0.72f)
                val wingBot = Offset(tipPt.x - arrowDir * headSize, tipPt.y + headSize * 0.72f)

                val arrowColor = Color(0xFF00B0FF)
                drawLine(
                    color = arrowColor,
                    start = startPt,
                    end = tipPt,
                    strokeWidth = 4.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = arrowColor,
                    start = tipPt,
                    end = wingTop,
                    strokeWidth = 4.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = arrowColor,
                    start = tipPt,
                    end = wingBot,
                    strokeWidth = 4.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // 3. Floating HUD Foreground Layers
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // ROW 1: Well-Labeled Non-Overlapping Top Camera Control Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onOpenMenu,
                    color = hudGlassBg,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, hudGlassBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu & Tools",
                            tint = KoPilotClusterTokens.PrimaryAccent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Menu",
                            style = MaterialTheme.typography.labelMedium,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (state.settings.cameraPrimaryLayout) {
                    Surface(
                        onClick = viewModel::cycleCameraZoom,
                        color = hudGlassBg,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, hudGlassBorder),
                    ) {
                        Text(
                            text = "Zoom ${"%.1f".format(state.settings.cameraZoomRatio)}x",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.PrimaryAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }

                Surface(
                    onClick = viewModel::toggleCameraPrimaryLayout,
                    color = hudGlassBg,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, hudGlassBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (state.settings.cameraPrimaryLayout) Icons.Default.Map else Icons.Default.Videocam,
                            contentDescription = null,
                            tint = KoPilotClusterTokens.SecondaryAccent,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = if (state.settings.cameraPrimaryLayout) "Map View" else "Cam View",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Compact live Heart-Rate pill on AR HUD when smartwatch is connected
                val hudHr = state.heartRateState
                if (hudHr.isLiveReading && !hudHr.isStale && hudHr.currentBpm != null) {
                    Surface(
                        onClick = onOpenSmartwatchHealth,
                        color = hudGlassBg,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.SmoothRoad.copy(alpha = 0.7f)),
                    ) {
                        Text(
                            text = "HR ${hudHr.currentBpm}",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.SmoothRoad,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                Surface(
                    onClick = onOpenObdLive,
                    color = hudGlassBg,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, hudGlassBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (satCount > 0) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                                    CircleShape,
                                ),
                        )
                        Text(
                            text = if (satCount > 0) "$satCount SAT" else "GPS",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (satCount > 0) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("•", color = KoPilotClusterTokens.SecondaryText, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = if (obdConnected) "OBD Live" else "OBD/IMU",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.PrimaryAccent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ROW 2: 4x Bigger Weather & Compass HUD Cards with Large High-Visibility Icons
            var showLargeCompass by remember { mutableStateOf(false) }
            if (showLargeCompass) {
                AlertDialog(
                    onDismissRequest = { showLargeCompass = false },
                    title = { Text("Compass Heading") },
                    text = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = state.compassReading.formatted,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = KoPilotClusterTokens.PrimaryAccent,
                            )
                            Text(
                                text = "Source: ${state.compassReading.source}" +
                                    if (state.compassReading.interferenceSuspected) " • Magnetic interference suspected" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = KoPilotClusterTokens.SecondaryText,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLargeCompass = false }) {
                            Text("Close")
                        }
                    },
                )
            }

            if (state.settings.weatherHudEnabled || state.settings.compassHudEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.settings.weatherHudEnabled) {
                        val w = state.weatherState
                        val tempHeadline = if (w.available && w.temperatureC != null) {
                            "${w.temperatureC!!.toInt()}°C"
                        } else {
                            "--°C"
                        }
                        val condSubtitle = when {
                            w.available -> w.conditionSummary.ifBlank { "Clear Sky" }
                            else -> "Weather Standby"
                        }
                        val (weatherIcon, weatherTint) = remember(w.conditionSummary, w.available) {
                            val cond = w.conditionSummary.lowercase()
                            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                            val isNight = hour < 6 || hour >= 19
                            when {
                                !w.available -> Icons.Default.WbCloudy to Color(0xFF94A3B8)
                                cond.contains("rain") || cond.contains("drizzle") || cond.contains("storm") || cond.contains("thunder") ->
                                    Icons.Default.Grain to Color(0xFF38BDF8)
                                cond.contains("cloud") || cond.contains("overcast") || cond.contains("fog") ->
                                    Icons.Default.WbCloudy to Color(0xFFE2E8F0)
                                isNight -> Icons.Default.NightsStay to Color(0xFFFDE68A)
                                else -> Icons.Default.WbSunny to Color(0xFFFACC15)
                            }
                        }
                        Surface(
                            color = hudGlassBg,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.5.dp, weatherTint.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 84.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(weatherTint.copy(alpha = 0.18f), CircleShape)
                                        .border(1.5.dp, weatherTint.copy(alpha = 0.7f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = weatherIcon,
                                        contentDescription = condSubtitle,
                                        tint = weatherTint,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "WEATHER",
                                        fontSize = 10.sp,
                                        color = weatherTint,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Text(
                                        text = tempHeadline,
                                        fontSize = 24.sp,
                                        lineHeight = 26.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Black,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = condSubtitle,
                                        fontSize = 12.sp,
                                        color = KoPilotClusterTokens.PrimaryText,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    if (state.settings.compassHudEnabled) {
                        val compass = state.compassReading
                        val turnStatusText = when {
                            compass.headingDeltaDegrees <= -20f -> "Turning Left"
                            compass.headingDeltaDegrees >= 20f -> "Turning Right"
                            else -> "Heading ${compass.cardinal} • ${compass.source}"
                        }
                        Surface(
                            onClick = { showLargeCompass = true },
                            color = hudGlassBg,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.5.dp, KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 84.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.18f), CircleShape)
                                        .border(2.dp, KoPilotClusterTokens.PrimaryAccent, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Explore,
                                        contentDescription = "Compass",
                                        tint = KoPilotClusterTokens.PrimaryAccent,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "COMPASS",
                                        fontSize = 10.sp,
                                        color = KoPilotClusterTokens.PrimaryAccent,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Text(
                                        text = "${compass.cardinal} · ${compass.degrees}°",
                                        fontSize = 22.sp,
                                        lineHeight = 25.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Black,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = turnStatusText,
                                        fontSize = 12.sp,
                                        color = if (kotlin.math.abs(compass.headingDeltaDegrees) >= 20f) {
                                            KoPilotClusterTokens.ModerateRoad
                                        } else {
                                            KoPilotClusterTokens.PrimaryText
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ROW 3: Live Data Strip Replacing AI Speed Zone (Road Sign + Car Plate Informs + Muted Alerts + Road Surface)
            val latestSignObs = state.visionState.lastObservation
            val signTitle = latestSignObs?.let { it.customLabel ?: it.category.displayName }
                ?: limitKmh?.let { "Speed Limit $it km/h" }
                ?: "Scanning Signs"
            val spottedPlate = state.visionState.lastDetectedPlate
            val watchedPlates = state.settings.watchedPlatesList()
            val isWatchedMatch = spottedPlate != null && watchedPlates.any {
                it.replace(" ", "").equals(spottedPlate.replace(" ", ""), ignoreCase = true)
            }
            val alertsMuted = state.settings.alertsMuted

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Live Road Sign + Car Plate Informs + Muted Alerts Card
                Surface(
                    color = hudGlassBg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, hudGlassBorder),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 1. Live Road Sign Badge
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color.White, CircleShape)
                                .border(3.dp, Color(0xFFDC2626), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = limitKmh?.toString()
                                    ?: latestSignObs?.category?.displayName?.take(3)?.uppercase()
                                    ?: "--",
                                style = MaterialTheme.typography.labelLarge,
                                color = KoPilotClusterTokens.Background,
                                fontWeight = FontWeight.Black,
                            )
                        }

                        // 2. Road Sign & Car Plate Live Information
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sign: $signTitle",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    spottedPlate != null && isWatchedMatch -> "Plate: $spottedPlate (WATCHLIST!)"
                                    spottedPlate != null -> "Plate: $spottedPlate"
                                    watchedPlates.isNotEmpty() -> "Plate: Scanning (${watchedPlates.size} watched)"
                                    else -> "Plate: Scanning Road Ahead"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isWatchedMatch) KoPilotColors.DangerRed else KoPilotClusterTokens.PrimaryAccent,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // 3. Muted Alerts Live Status & Toggle Button
                        Surface(
                            onClick = viewModel::toggleAlertsMute,
                            shape = RoundedCornerShape(10.dp),
                            color = if (alertsMuted) Color(0xFF3E1018) else Color(0xFF0E2E28),
                            border = BorderStroke(
                                1.dp,
                                if (alertsMuted) KoPilotColors.DangerRed else KoPilotClusterTokens.SmoothRoad,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (alertsMuted) {
                                        Icons.AutoMirrored.Filled.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = if (alertsMuted) "Alerts Muted" else "Alerts Active",
                                    tint = if (alertsMuted) KoPilotColors.DangerRed else KoPilotClusterTokens.SmoothRoad,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (alertsMuted) "Muted" else "Alerts On",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                // Road Surface Score Tile
                Surface(
                    onClick = onOpenRoadQuality,
                    color = hudGlassBg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, hudGlassBorder),
                ) {
                    val roadColor = when (motion.roadVibrationLevel) {
                        RoadVibrationLevel.SMOOTH -> KoPilotClusterTokens.SmoothRoad
                        RoadVibrationLevel.MODERATE_VIBRATION -> KoPilotClusterTokens.ModerateRoad
                        RoadVibrationLevel.ROUGH_ROAD_EXPERIMENTAL -> KoPilotClusterTokens.RoughRoad
                        RoadVibrationLevel.VERY_ROUGH -> KoPilotClusterTokens.VeryRough
                        RoadVibrationLevel.UNKNOWN -> KoPilotClusterTokens.SecondaryText
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = "ROAD",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                        Text(
                            text = "${stats.roadSmoothnessScore}/100",
                            style = MaterialTheme.typography.labelLarge,
                            color = roadColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Open unobstructed camera viewport above the lowered speedometer
            Spacer(modifier = Modifier.weight(1f))

            // Lowered Floating Holographic Reticle Speedometer (positioned directly above Safety Index)
            Surface(
                color = Color(0x78071019),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("-1.2G", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryText)
                        Text("-0.6G", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.PrimaryAccent)
                        Surface(
                            color = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                text = "${"%.1f".format(lateralG)}G LAT",
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.PrimaryText,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Text("+0.6G", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.PrimaryAccent)
                        Text("+1.2G", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryText)
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "$speedKmh",
                            fontSize = 68.sp,
                            lineHeight = 72.sp,
                            fontWeight = FontWeight.Black,
                            color = KoPilotClusterTokens.PrimaryText,
                        )
                        Column {
                            Text(
                                text = "KM/H",
                                style = MaterialTheme.typography.headlineMedium,
                                color = KoPilotClusterTokens.PrimaryText,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                text = if (rpm != null) "$rpm RPM" else intel.drivingState.label.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = KoPilotClusterTokens.PrimaryAccent,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = hudGlassBg,
                        shape = CircleShape,
                        border = BorderStroke(
                            1.dp,
                            if (state.alertState.activeAlert != null) KoPilotClusterTokens.ModerateRoad else hudGlassBorder,
                        ),
                    ) {
                        Text(
                            text = state.alertState.activeAlert?.displayText
                                ?: if (state.isDrivingSessionActive) "Active Journey • ${intel.drivingState.label}" else "Standby • Tap Start Driving",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.alertState.activeAlert != null) KoPilotClusterTokens.ModerateRoad else KoPilotClusterTokens.PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bottom Floating Telemetry Island
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = onOpenDrivingIntelligence,
                    color = hudGlassBg,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, hudGlassBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenFuelEfficiency() },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.2f), CircleShape)
                                    .border(1.5.dp, KoPilotClusterTokens.PrimaryAccent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$safetyAnim",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = KoPilotClusterTokens.PrimaryAccent,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Column {
                                Text(
                                    text = "Safety Index",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = intel.latestEvent?.title ?: "Smooth Driving",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.SmoothRoad,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(Color.White.copy(alpha = 0.2f)),
                        )
                        Spacer(Modifier.width(10.dp))

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenFuelEfficiency() },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(KoPilotClusterTokens.Background.copy(alpha = 0.8f), CircleShape)
                                    .border(1.5.dp, KoPilotClusterTokens.SecondaryAccent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$fuelAnim",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Column {
                                Text(
                                    text = "Fuel Efficiency",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Idle ${intel.idlingRatioPct}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.SecondaryAccent,
                                )
                            }
                        }
                    }
                }

                // Live Offline Voice AI Transcript & Status Strip (when Voice AI is active or answered)
                val vState = state.voiceState
                val voiceIsActive = vState.connectionState == VoiceConnectionState.LOCAL_FALLBACK ||
                    vState.connectionState == VoiceConnectionState.CONNECTED ||
                    vState.connectionState == VoiceConnectionState.CONNECTING
                if (voiceIsActive || vState.lastTranscript.isNotBlank()) {
                    Surface(
                        color = hudGlassBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (vState.isListening) KoPilotClusterTokens.SmoothRoad.copy(alpha = 0.75f) else hudGlassBorder,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (vState.isListening) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.PrimaryAccent,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = vState.lastTranscript.ifBlank { vState.statusMessage },
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.PrimaryText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Bottom Action Buttons — All Clearly Labeled (Stop Drive, Voice, Navigate)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (state.isDrivingSessionActive) viewModel.endTrip() else viewModel.startDriving()
                        },
                        modifier = Modifier
                            .weight(1.1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isDrivingSessionActive) KoPilotClusterTokens.VeryRough else KoPilotClusterTokens.PrimaryAccent,
                            contentColor = if (state.isDrivingSessionActive) Color.White else KoPilotClusterTokens.Background,
                        ),
                    ) {
                        Icon(
                            imageVector = if (state.isDrivingSessionActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (state.isDrivingSessionActive) "Stop Drive" else "Start Drive",
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                        )
                    }

                    OutlinedButton(
                        onClick = viewModel::toggleVoiceConnection,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (voiceIsActive) KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.2f) else hudGlassBg,
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (voiceIsActive) KoPilotClusterTokens.PrimaryAccent else hudGlassBorder,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice",
                            tint = if (vState.isListening) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.PrimaryAccent,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when {
                                vState.isSpeaking -> "Speaking"
                                vState.isListening -> "Listening"
                                voiceIsActive -> "Voice On"
                                else -> "Voice AI"
                            },
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.runCheckAllSystems(speakReport = true) },
                        enabled = !state.isSystemCheckRunning,
                        modifier = Modifier
                            .weight(1.1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = hudGlassBg),
                        border = BorderStroke(1.dp, hudGlassBorder),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FactCheck,
                            contentDescription = "Check All Systems",
                            tint = KoPilotClusterTokens.SecondaryAccent,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (state.isSystemCheckRunning) "Checking..." else "Check All",
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveIntelligenceCard(
    state: DashboardUiState,
    isMoving: Boolean,
    onClick: () -> Unit,
) {
    val intel = state.intelligenceState
    val safetyAnim by androidx.compose.animation.core.animateIntAsState(
        targetValue = intel.safetyScore,
        animationSpec = androidx.compose.animation.core.tween(350),
        label = "dashSafety",
    )
    val fuelAnim by androidx.compose.animation.core.animateIntAsState(
        targetValue = intel.fuelEfficiencyScore,
        animationSpec = androidx.compose.animation.core.tween(350),
        label = "dashFuel",
    )
    val safetyColor = when {
        safetyAnim >= 80 -> KoPilotClusterTokens.SmoothRoad
        safetyAnim >= 55 -> KoPilotClusterTokens.ModerateRoad
        else -> KoPilotClusterTokens.VeryRough
    }
    val fuelColor = when {
        fuelAnim >= 80 -> KoPilotClusterTokens.PrimaryAccent
        fuelAnim >= 55 -> KoPilotClusterTokens.ModerateRoad
        else -> KoPilotClusterTokens.RoughRoad
    }
    val gpsReady = state.locationState.currentFix != null
    val obdReady = state.diagnosticsState.obdConnected

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CockpitCardBg,
        border = BorderStroke(1.dp, CockpitCardBorder),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "LIVE INTELLIGENCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = buildString {
                            append(if (gpsReady) "GPS" else "No GPS")
                            append(" • IMU")
                            if (obdReady) append(" • OBD")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                    Surface(
                        color = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = intel.drivingState.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.PrimaryAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Safety Score",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                    Text(
                        text = "$safetyAnim / 100",
                        style = MaterialTheme.typography.titleLarge,
                        color = safetyColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Fuel Efficiency",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                    Text(
                        text = "$fuelAnim / 100",
                        style = MaterialTheme.typography.titleLarge,
                        color = fuelColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val latestEvt = intel.latestEvent
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = latestEvt?.let { "${it.title}: ${it.detail}" }
                        ?: if (state.isDrivingSessionActive) "Smooth driving — no harsh events" else "Ready for active journey",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (latestEvt != null) KoPilotClusterTokens.ModerateRoad else KoPilotClusterTokens.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Details",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (!isMoving && intel.coachingAdvice.isNotEmpty()) {
                Text(
                    text = intel.coachingAdvice.first().message,
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RoadQualityLiveCard(
    state: DashboardUiState,
    onClick: () -> Unit,
) {
    val motion = state.motionAnalysis
    val stats = motion.behaviourStats
    val rms = motion.verticalVibrationRmsMps2
    val now = System.currentTimeMillis()
    val isStale = motion.updatedAtMillis > 0L && (now - motion.updatedAtMillis) > 8_000L
    val isInvalid = !state.isDrivingSessionActive ||
        motion.phoneMovementDetected ||
        motion.roadVibrationLevel == RoadVibrationLevel.UNKNOWN ||
        isStale

    val scoreText: String
    val label: String
    val badgeColor: Color
    when {
        !state.isDrivingSessionActive -> {
            scoreText = "--"
            label = "Paused"
            badgeColor = KoPilotClusterTokens.SecondaryText
        }
        motion.phoneMovementDetected -> {
            scoreText = "--"
            label = "Mount Moving"
            badgeColor = KoPilotClusterTokens.ModerateRoad
        }
        isStale -> {
            scoreText = "--"
            label = "Stale"
            badgeColor = KoPilotClusterTokens.ModerateRoad
        }
        motion.roadVibrationLevel == RoadVibrationLevel.UNKNOWN -> {
            scoreText = "--"
            label = "Unavailable"
            badgeColor = KoPilotClusterTokens.SecondaryText
        }
        else -> {
            val score = stats.roadSmoothnessScore
            scoreText = "$score/100"
            val pair = when {
                score >= 80 -> "Smooth" to KoPilotClusterTokens.SmoothRoad
                score >= 55 -> "Moderate" to KoPilotClusterTokens.ModerateRoad
                score >= 30 -> "Rough" to KoPilotClusterTokens.RoughRoad
                else -> "Very Rough" to KoPilotClusterTokens.VeryRough
            }
            label = pair.first
            badgeColor = pair.second
        }
    }

    val samples = remember(stats.recentSamples) { stats.recentSamples.takeLast(50) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = CockpitCardBg,
        border = BorderStroke(1.dp, CockpitCardBorder),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Road Quality",
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = KoPilotClusterTokens.ElevatedSurface,
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.65f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = scoreText,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = label,
                            color = badgeColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CockpitDarkBg,
                modifier = Modifier.fillMaxWidth().height(26.dp),
            ) {
                if (samples.size < 2 || isInvalid) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = when {
                                !state.isDrivingSessionActive -> "Paused — start driving to score road"
                                motion.phoneMovementDetected -> "Phone movement detected — stabilizing"
                                isStale -> "Sensor data stale"
                                else -> "Sampling road vibration..."
                            },
                            color = KoPilotClusterTokens.SecondaryText,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp)) {
                        val w = size.width
                        val h = size.height
                        val maxRms = 3.5f
                        val stepX = w / (samples.size - 1).coerceAtLeast(1)
                        for (i in 1 until samples.size) {
                            val prev = samples[i - 1]
                            val curr = samples[i]
                            val x1 = (i - 1) * stepX
                            val y1 = h * (1f - (prev.rmsMps2 / maxRms).coerceIn(0f, 1f))
                            val x2 = i * stepX
                            val y2 = h * (1f - (curr.rmsMps2 / maxRms).coerceIn(0f, 1f))
                            val segColor = when {
                                curr.rmsMps2 < 0.7f -> KoPilotClusterTokens.SmoothRoad
                                curr.rmsMps2 < 1.4f -> KoPilotClusterTokens.ModerateRoad
                                curr.rmsMps2 < 2.4f -> KoPilotClusterTokens.RoughRoad
                                else -> KoPilotClusterTokens.VeryRough
                            }
                            drawLine(
                                color = segColor,
                                start = Offset(x1, y1),
                                end = Offset(x2, y2),
                                strokeWidth = 1.8.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Bumps: ${stats.potholeBumpCount}",
                    fontSize = 11.sp,
                    color = KoPilotClusterTokens.SecondaryText,
                )
                Text(
                    text = "RMS: ${"%.2f".format(rms)} m/s²",
                    fontSize = 11.sp,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Avg: ${stats.averageRoadScore}/100",
                    fontSize = 11.sp,
                    color = KoPilotClusterTokens.PrimaryAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun BottomQuickActionsBar(
    alertsMuted: Boolean,
    onToggleMuteAlerts: () -> Unit,
    onCallEmergency: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenObd: () -> Unit = {},
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
            icon = Icons.Default.DirectionsCar,
            label = "OBD-II",
            iconTint = MintTeal,
            onClick = onOpenObd,
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
    activeLogFile: File?,
    findLogFile: (Long) -> File?,
    onViewLog: (File) -> Unit,
    onShareLog: (File) -> Unit,
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
                if (activeLogFile != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Active Trip Log (.txt)",
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = activeLogFile.name,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onViewLog(activeLogFile) }) {
                                    Text("View Log")
                                }
                                Button(onClick = { onShareLog(activeLogFile) }) {
                                    Text("Share .txt")
                                }
                            }
                        }
                    }
                }
                if (trips.isEmpty() && activeLogFile == null) {
                    Text("No saved trips yet. Tap Start Driving to record a trip.")
                } else {
                    trips.forEach { trip ->
                        val logFile = remember(trip.id, trip.startedAtMillis) { findLogFile(trip.startedAtMillis) }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = trip.destinationLabel ?: "Local Drive",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${formatter.format(Date(trip.startedAtMillis))} • ${"%.1f".format(trip.distanceKm)} km • ${trip.durationSeconds / 60} min",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (logFile != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { onViewLog(logFile) }) {
                                            Text("View .txt")
                                        }
                                        Button(onClick = { onShareLog(logFile) }) {
                                            Text("Share .txt")
                                        }
                                    }
                                }
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
private fun TripLogViewerDialog(
    file: File,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val content = remember(file.absolutePath, file.length()) {
        runCatching { file.readText() }.getOrElse { "Unable to read log file: ${it.localizedMessage}" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 1) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Saved at: ${file.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        confirmButton = {
            Button(onClick = onShare) { Text("Share .txt") }
        },
    )
}

@Composable
private fun DestinationPickerDialog(
    state: DashboardUiState,
    presets: List<Pair<String, com.google.android.gms.maps.model.LatLng>>,
    onSearch: (String) -> Unit,
    onSelect: (String, Double, Double) -> Unit,
    onOpenExternalGoogleMaps: (String, Double, Double) -> Unit,
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onSelect(item.name, item.latitude, item.longitude) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("${item.name} — ${item.formattedAddress}")
                        }
                        TextButton(onClick = { onOpenExternalGoogleMaps(item.name, item.latitude, item.longitude) }) {
                            Text("GMaps")
                        }
                    }
                }
                Text("Major Kenyan Towns", style = MaterialTheme.typography.labelMedium)
                presets.forEach { (name, latLng) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onSelect(name, latLng.latitude, latLng.longitude) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(name)
                        }
                        TextButton(onClick = { onOpenExternalGoogleMaps(name, latLng.latitude, latLng.longitude) }) {
                            Text("GMaps")
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
private fun VisionDiagnosticsDialog(
    state: DashboardUiState,
    isParked: Boolean,
    onSavePlateEvidence: () -> Unit,
    onDeletePlateEvidence: () -> Unit,
    evidenceCount: Int,
    onOpenPerformanceThermal: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val cam = state.cameraStatus
    val vis = state.visionState
    val perf = vis.performanceMetrics
    val allErrors = (cam.recentErrors + vis.recentErrors).takeLast(10)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline AI Diagnostics") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenPerformanceThermal,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MintTeal,
                        contentColor = CockpitDarkBg,
                    ),
                ) {
                    Text(
                        text = "Open Performance & Thermal Screen",
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "Actual measured on-device LiteRT & CameraX metrics (never simulated):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Model: ${vis.modelVersion}", fontWeight = FontWeight.Medium)
                        Text("Accelerator: ${vis.activeAccelerator}")
                        Text("Mode: ${vis.validationStatus}", style = MaterialTheme.typography.bodySmall)
                        Text("Latency (Last / Avg / P95): ${vis.lastInferenceLatencyMs} ms / ${"%.1f".format(perf.avgTotalLatencyMs)} ms / ${"%.0f".format(perf.p95TotalLatencyMs)} ms")
                        Text("Camera FPS: ${"%.1f".format(cam.cameraFps)} | Inference FPS: ${"%.1f".format(cam.inferenceFps)}")
                        Text("Camera Resolution: ${cam.actualResolution}")
                        Text("Frames Analyzed: ${cam.analyzedFrames} | Paced: ${cam.droppedFrames} (${"%.1f".format(cam.droppedFramePercent)}%)")
                        Text("Thermal: ${cam.thermalState.name} (${cam.osThermalStatusLabel}) | Battery: ${if (cam.batteryPercent >= 0) "${cam.batteryPercent}%" else "Unknown"} ${cam.batteryTemperatureC?.let { "(${"%.1f".format(it)} C)" } ?: ""}")
                        Text("Memory: JVM ${cam.jvmHeapUsedMb}/${cam.jvmHeapMaxMb} MB | Native ${cam.nativeHeapAllocatedMb} MB")
                        Text("Scene Luminance: ${vis.averageLuminance.toInt()} | Zoom: ${"%.1f".format(vis.recommendedZoomRatio)}x")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Detected Sign Classes (${vis.detectedClassCounts.size})", fontWeight = FontWeight.Medium)
                        if (vis.detectedClassCounts.isEmpty()) {
                            Text("No sign classes detected yet in this session.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            vis.detectedClassCounts.entries.sortedByDescending { it.value }.forEach { (cls, count) ->
                                Text("• $cls: $count detections", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (vis.supportedSignClasses.isNotEmpty()) {
                            Text(
                                text = "Trained Classes (${vis.supportedSignClasses.size}): ${vis.supportedSignClasses.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (vis.recentSignHistory.isNotEmpty()) {
                            val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                            Spacer(Modifier.height(4.dp))
                            Text("Recent Road-Sign History (${vis.recentSignHistory.size})", fontWeight = FontWeight.Medium)
                            vis.recentSignHistory.take(8).forEach { obs ->
                                val pct = (obs.confidence * 100).toInt()
                                Text(
                                    text = "• ${timeFmt.format(Date(obs.timestampMillis))} — ${obs.customLabel ?: obs.category.displayName} ($pct%)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Detected Objects (${vis.trackedObjects.size})", fontWeight = FontWeight.SemiBold)
                        if (vis.trackedObjects.isEmpty()) {
                            Text("None in current frame", style = MaterialTheme.typography.bodySmall)
                        } else {
                            vis.trackedObjects.take(5).forEach { obj ->
                                Text(
                                    text = "#${obj.trackingId} ${obj.label} (${(obj.confidence * 100).toInt()}%)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        val signObs = vis.lastObservation
                        Text(
                            text = if (signObs != null) {
                                "Last Sign: ${signObs.category.displayName} (${(signObs.confidence * 100).toInt()}%, ${signObs.consecutiveFrames}f)"
                            } else {
                                "Last Sign: None"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val plateObs = vis.lastPlateObservation
                        Text(
                            text = if (plateObs != null) {
                                "Last Plate (Masked): ${plateObs.maskedPlate} (${plateObs.formatCategory}, OCR ${(plateObs.ocrConfidence * 100).toInt()}%)"
                            } else {
                                "Last Plate: None"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (isParked) {
                    Text("Encrypted Evidence ($evidenceCount saved)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onSavePlateEvidence,
                            enabled = vis.lastPlateObservation != null,
                        ) {
                            Text("Save Plate")
                        }
                        OutlinedButton(
                            onClick = onDeletePlateEvidence,
                            enabled = evidenceCount > 0,
                        ) {
                            Text("Delete All")
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Error Logs (${allErrors.size})", fontWeight = FontWeight.SemiBold)
                        if (allErrors.isEmpty()) {
                            Text("No vision/camera errors logged.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            allErrors.forEach { err ->
                                Text(err, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
private fun ObdLiveDashboardDialog(
    connectionStateName: String,
    gpsSpeedKmh: Float?,
    telemetry: ObdLiveTelemetry,
    capabilities: List<ObdPidCapability>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Engine & CVT Live OBD-II") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("State: $connectionStateName • ${telemetry.protocolName ?: "Not connected"}", fontWeight = FontWeight.Medium)
                telemetry.adapterInfo?.let { Text("Adapter: $it (${telemetry.transportType.name})", style = MaterialTheme.typography.bodySmall) }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val rpm = telemetry.engineRpm ?: 0
                        Text("Engine RPM (010C): ${telemetry.engineRpm ?: "--"} rpm", fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(
                            progress = { (rpm / 7000f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val coolant = telemetry.coolantTempC
                        Text("Coolant Temp (0105): ${coolant?.let { "$it°C" } ?: "--"}")
                        if (telemetry.coolantHistoryC.isNotEmpty()) {
                            Text(
                                text = "Coolant Trend: ${telemetry.coolantHistoryC.takeLast(8).joinToString("° → ")}°C",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("OBD Speed (010D): ${telemetry.vehicleSpeedKmh ?: "--"} km/h | GPS Speed: ${gpsSpeedKmh?.toInt() ?: "--"} km/h")
                        Text("Engine Load (0104): ${telemetry.engineLoadPct?.let { "${"%.1f".format(it)}%" } ?: "--"} | Throttle (0111): ${telemetry.throttlePositionPct?.let { "${"%.1f".format(it)}%" } ?: "--"}")
                        Text("Intake Temp (010F): ${telemetry.intakeAirTempC?.let { "$it°C" } ?: "--"} | Ambient (0146): ${telemetry.ambientAirTempC?.let { "$it°C" } ?: "--"}")
                        Text("STFT: ${telemetry.shortTermFuelTrimPct?.let { "${"%.1f".format(it)}%" } ?: "--"} | LTFT: ${telemetry.longTermFuelTrimPct?.let { "${"%.1f".format(it)}%" } ?: "--"} | MAP: ${telemetry.mapKpa ?: "--"} kPa")
                        Text("MAF: ${telemetry.mafGramsPerSec?.let { "${"%.2f".format(it)} g/s" } ?: "--"} | Timing: ${telemetry.timingAdvanceDeg?.let { "${"%.1f".format(it)}°" } ?: "--"} | O2: ${telemetry.o2SensorVoltageV?.let { "${"%.2f".format(it)}V" } ?: "--"}")
                        Text("Fuel Level: ${telemetry.fuelLevelPct?.let { "${"%.1f".format(it)}%" } ?: "--"} | Voltage: ${telemetry.controlModuleVoltageV?.let { "${"%.2f".format(it)}V" } ?: "--"} | Runtime: ${telemetry.runtimeSinceStartSec ?: "--"}s")
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Transmission & CVT Monitoring", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Transmission DTCs (P07xx–P09xx): ${telemetry.transmissionDtcCodes.ifEmpty { listOf("None") }.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        telemetry.observedRpmSpeedRatio?.let { ratio ->
                            Text("Observed RPM/Speed Ratio: ${"%.1f".format(ratio)} rpm per km/h", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(telemetry.transmissionStatusNote, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val supportedCount = capabilities.count { it.status == PidSupportStatus.SUPPORTED }
                        Text("ECU Capability Registry ($supportedCount/${capabilities.size} supported)", fontWeight = FontWeight.SemiBold)
                        capabilities.forEach { cap ->
                            Text(
                                text = "${cap.pidHex} ${cap.name}: ${cap.status.name}",
                                style = MaterialTheme.typography.bodySmall,
                            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardToolsDialog(
    state: DashboardUiState,
    isParked: Boolean,
    onDismiss: () -> Unit,
    onOpenDrivingBehaviour: () -> Unit,
    onOpenDrivingIntelligence: () -> Unit,
    onOpenVehicleIntelligence: () -> Unit,
    onOpenRoadQuality: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenObd: () -> Unit,
    onOpenSoundStudio: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenSignsDb: () -> Unit,
    onOpenVisionDiag: () -> Unit,
    onOpenPerformanceThermal: () -> Unit = {},
    onOpenSmartwatchHealth: () -> Unit = {},
    onOpenSystemStatus: () -> Unit = {},
    onToggleMuteAlerts: () -> Unit,
    onCallEmergency: () -> Unit,
) {
    val w = state.weatherState
    val weatherLine = if (w.available && w.temperatureC != null) {
        "${"%.0f".format(w.temperatureC)}°C · ${w.conditionSummary}"
    } else {
        "Weather unavailable"
    }
    val destLine = state.navigationState.destinationName ?: "No destination set"

    var visible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        visible = true
        // Auto-hide smoothly after 12 seconds of inactivity so the driver view stays unobstructed
        kotlinx.coroutines.delay(12_000L)
        onDismiss()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99040A12))
                .clickable(onClick = onDismiss),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = visible,
                enter = androidx.compose.animation.slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = androidx.compose.animation.core.tween(260),
                ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
                exit = androidx.compose.animation.slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.82f)
                    .fillMaxHeight(),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = false, onClick = {}),
                    shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                    color = KoPilotClusterTokens.CardSurface,
                    border = BorderStroke(1.dp, KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.35f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "KoPilot Menu",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    text = "$weatherLine • $destLine",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.SecondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.CloseFullscreen,
                                    contentDescription = "Close Menu",
                                    tint = KoPilotClusterTokens.SecondaryText,
                                )
                            }
                        }

                        androidx.compose.material3.HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)

                        Text("VEHICLE & DRIVING", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.PrimaryAccent, fontWeight = FontWeight.Bold)
                        MenuDrawerItem("Road Quality", Icons.Default.Route, onOpenRoadQuality)
                        MenuDrawerItem("Driving Behaviour", Icons.Default.DirectionsCar, onOpenDrivingBehaviour)
                        MenuDrawerItem("Live Intelligence", Icons.Default.Explore, onOpenDrivingIntelligence)
                        MenuDrawerItem("Ask Car AI", Icons.Default.DirectionsCar, onOpenVehicleIntelligence)
                        MenuDrawerItem("OBD-II Gauges", Icons.Default.DirectionsCar, onOpenObd)
                        MenuDrawerItem("Engine Sound Studio", Icons.AutoMirrored.Filled.VolumeUp, onOpenSoundStudio)

                        androidx.compose.material3.HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)

                        Text("SENSORS, WATCH & VISION", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.PrimaryAccent, fontWeight = FontWeight.Bold)
                        MenuDrawerItem("System Status & Briefing", Icons.Default.FactCheck, onOpenSystemStatus)
                        MenuDrawerItem("Smartwatch & Health", Icons.Default.MyLocation, onOpenSmartwatchHealth)
                        MenuDrawerItem("Sensors Hub", Icons.Default.Explore, onOpenSensors)
                        MenuDrawerItem("Performance & Thermal", Icons.Default.Explore, onOpenPerformanceThermal)
                        MenuDrawerItem("Offline AI Diagnostics", Icons.Default.Videocam, onOpenVisionDiag)
                        MenuDrawerItem("Road Signs Database", Icons.Default.Map, onOpenSignsDb)
                        MenuDrawerItem("My Trips (.txt Logs)", Icons.Default.Route, onOpenTrips)

                        androidx.compose.material3.HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onToggleMuteAlerts,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    text = if (state.settings.alertsMuted) "Unmute" else "Mute Alerts",
                                    fontSize = 12.sp,
                                    color = KoPilotClusterTokens.PrimaryText,
                                )
                            }
                            Button(
                                onClick = onCallEmergency,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = KoPilotColors.DangerRed),
                            ) {
                                Text("Emergency", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuDrawerItem(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = KoPilotClusterTokens.ElevatedSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KoPilotClusterTokens.PrimaryAccent,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = title,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(">", color = KoPilotClusterTokens.SecondaryText, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RoadSignsDatabaseDialog(
    signs: List<LocalRoadSignEntry>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Kenyan Road Signs Database (${signs.size})",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (signs.isEmpty()) {
                    Text(
                        text = "No road sign catalog entries loaded.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    signs.forEach { sign ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "${sign.code} · ${sign.title}",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${sign.category} (${sign.shape})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = sign.spokenAdvice,
                                    style = MaterialTheme.typography.bodySmall,
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
