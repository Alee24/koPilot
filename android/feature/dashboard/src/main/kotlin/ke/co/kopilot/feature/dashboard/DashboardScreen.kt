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
                    zoomRatio = state.visionState.recommendedZoomRatio,
                    performanceController = viewModel.cameraController,
                    onStartDrivingClick = viewModel::startDriving,
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
                        // Drivable road surface coloring + yellow lane lines when driving with camera active
                        if (state.isDrivingSessionActive && state.settings.sensorCameraEnabled) {
                            val lane = state.visionState.laneOverlay
                            val yellowLane = Color(0xFFFFD600)
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
                                    color = if (isShifting) alertRed.copy(alpha = 0.22f) else MintTeal.copy(alpha = 0.22f),
                                )
                            }

                            val lTop = Offset(lane.leftTopX * size.width, lane.leftTopY.coerceAtLeast(0.50f) * size.height)
                            val lBot = Offset(lane.leftBottomX * size.width, lane.leftBottomY * size.height)
                            val rTop = Offset(lane.rightTopX * size.width, lane.rightTopY.coerceAtLeast(0.50f) * size.height)
                            val rBot = Offset(lane.rightBottomX * size.width, lane.rightBottomY * size.height)
                            val leftColor = if (lane.shiftEvent == LaneShiftEvent.LANE_DEPARTURE_LEFT || lane.shiftEvent == LaneShiftEvent.SHIFTING_LEFT) alertRed else yellowLane
                            val rightColor = if (lane.shiftEvent == LaneShiftEvent.LANE_DEPARTURE_RIGHT || lane.shiftEvent == LaneShiftEvent.SHIFTING_RIGHT) alertRed else yellowLane
                            val dashEffect = if (lane.lanesDetected) null else PathEffect.dashPathEffect(floatArrayOf(18f, 14f), 0f)
                            val laneStroke = if (lane.lanesDetected) 4.dp.toPx() else 2.5.dp.toPx()
                            val laneAlpha = if (lane.lanesDetected) 0.95f else 0.55f

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
                    // Lane / Turn status pill at bottom-center of camera viewport
                    if (state.isDrivingSessionActive) {
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
                            text = "Auto ${"%.1f".format(state.visionState.recommendedZoomRatio)}x",
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
                        text = if (state.settings.cameraPrimaryLayout) "Map" else "Cam",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    )
                }
                Surface(
                    modifier = Modifier
                        .size(34.dp)
                        .clickable(onClick = onToggleFullscreen),
                    shape = CircleShape,
                    color = Color(0xCC0D141C),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen Camera",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
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
    val speedKmh = if (isGpsAvailable && !isStale) state.speedEvaluation.currentSpeedKmh else null
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
            modifier = Modifier.weight(0.9f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NavBlue,
                contentColor = Color.White,
            ),
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                text = "Navigate",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
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
    onToggleFullscreenCamera: () -> Unit,
) {
    val intel = state.intelligenceState
    val motion = state.motionAnalysis
    val stats = motion.behaviourStats
    val speedKmh = state.speedEvaluation.currentSpeedKmh?.toInt() ?: 0
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
        // 1. Header: Steering icon + KoPilot / AI Driving Copilot + Bell & Settings
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
                        .size(38.dp)
                        .background(KoPilotClusterTokens.ElevatedSurface, CircleShape)
                        .border(1.5.dp, KoPilotClusterTokens.PrimaryAccent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = KoPilotClusterTokens.PrimaryAccent,
                        modifier = Modifier.size(22.dp),
                    )
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
                IconButton(onClick = onOpenMenu, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Alerts & Tools",
                        tint = KoPilotClusterTokens.PrimaryText,
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
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
                    onClick = onOpenDrivingIntelligence,
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
    onToggleFullscreenCamera: () -> Unit,
) {
    val hudGlassBg = Color(0xBF071019)
    val hudGlassBorder = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.35f)
    val intel = state.intelligenceState
    val motion = state.motionAnalysis
    val stats = motion.behaviourStats
    val speedKmh = state.speedEvaluation.currentSpeedKmh?.toInt() ?: 0
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

    Box(modifier = modifier.background(KoPilotClusterTokens.Background)) {
        // 1. Full-Bleed CameraX Feed Backdrop
        ViewportCard(
            state = state,
            viewModel = viewModel,
            isFullscreen = true,
            onToggleFullscreen = onToggleFullscreenCamera,
        )

        // 2. Clearer Protective Vignette + Road-Adaptive AR Lane Vector Projection
        val curveOffsetDp = (motion.yawRateRadPerSec * 45f + motion.lateralAccelMps2 * 8f).coerceIn(-65f, 65f)
        val horizonShiftDp = (motion.pitchDegrees * 1.2f).coerceIn(-28f, 28f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color(0x8C071019),
                    0.22f to Color(0x00071019),
                    0.56f to Color(0x1A071019),
                    1.0f to Color(0xEB071019),
                ),
            )
            val horizonY = (size.height * 0.52f + horizonShiftDp.dp.toPx()).coerceIn(size.height * 0.50f, size.height * 0.65f)
            val bottomY = size.height * 0.84f
            val cx = size.width * 0.5f
            val targetX = cx + curveOffsetDp.dp.toPx()

            // Horizon reticle line
            drawLine(
                color = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.32f),
                start = Offset(20.dp.toPx(), horizonY),
                end = Offset(size.width - 20.dp.toPx(), horizonY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )

            // Dual AR lane projection curves adapting to road turn / lateral motion
            val dashEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(18f, 14f))
            val leftPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - size.width * 0.26f, bottomY)
                quadraticTo(cx - size.width * 0.12f, (bottomY + horizonY) * 0.52f, targetX - 10.dp.toPx(), horizonY)
            }
            val rightPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + size.width * 0.26f, bottomY)
                quadraticTo(cx + size.width * 0.12f, (bottomY + horizonY) * 0.52f, targetX + 10.dp.toPx(), horizonY)
            }
            drawPath(
                path = leftPath,
                color = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.8f),
                style = Stroke(width = 2.5.dp.toPx(), pathEffect = dashEffect, cap = StrokeCap.Round),
            )
            drawPath(
                path = rightPath,
                color = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.8f),
                style = Stroke(width = 2.5.dp.toPx(), pathEffect = dashEffect, cap = StrokeCap.Round),
            )
        }

        // 3. Floating HUD Foreground Layers (Camera unobstructed in upper/mid area; Speedometer lowered above Safety Index)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Top Floating Holographic Telemetry Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onOpenMenu,
                    color = hudGlassBg,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, hudGlassBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(KoPilotClusterTokens.PrimaryAccent, CircleShape),
                        )
                        Text(
                            text = "KOPILOT • HUD AR",
                            style = MaterialTheme.typography.labelMedium,
                            color = KoPilotClusterTokens.PrimaryAccent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Surface(
                    onClick = onOpenObdLive,
                    color = hudGlassBg,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, hudGlassBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("•", color = KoPilotClusterTokens.SecondaryText, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = if (obdConnected) "OBD LIVE" else "IMU",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.PrimaryAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Compact Compass + Weather HUD Pills (without altering Camera HUD layout)
            var showLargeCompass by remember { mutableStateOf(false) }
            if (showLargeCompass) {
                androidx.compose.material3.AlertDialog(
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
                        androidx.compose.material3.TextButton(onClick = { showLargeCompass = false }) {
                            Text("Close")
                        }
                    },
                )
            }

            if (state.settings.weatherHudEnabled || state.settings.compassHudEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.settings.weatherHudEnabled) {
                        val w = state.weatherState
                        val weatherText = when {
                            w.available && w.temperatureC != null -> {
                                val temp = "${w.temperatureC!!.toInt()}°C"
                                val ageSuffix = if (w.isStaleOfflineCache && w.fetchedAtMillis != null) {
                                    val mins = ((System.currentTimeMillis() - w.fetchedAtMillis!!) / 60_000L).coerceAtLeast(1L)
                                    " (${mins}m ago)"
                                } else ""
                                "${w.conditionSummary} · $temp$ageSuffix"
                            }
                            else -> "Weather unavailable"
                        }
                        val (weatherIcon, weatherTint) = remember(w.conditionSummary, w.available) {
                            val cond = w.conditionSummary.lowercase()
                            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                            val isNight = hour < 6 || hour >= 19
                            when {
                                !w.available -> Icons.Default.Cloud to KoPilotClusterTokens.SecondaryText
                                cond.contains("rain") || cond.contains("drizzle") || cond.contains("storm") || cond.contains("thunder") ->
                                    Icons.Default.Grain to Color(0xFF38BDF8)
                                cond.contains("cloud") || cond.contains("overcast") || cond.contains("fog") ->
                                    Icons.Default.Cloud to Color(0xFFCBD5E1)
                                isNight -> Icons.Default.NightsStay to Color(0xFFFDE68A)
                                else -> Icons.Default.WbSunny to Color(0xFFFACC15)
                            }
                        }
                        Surface(
                            color = hudGlassBg,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, hudGlassBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = weatherIcon,
                                    contentDescription = w.conditionSummary,
                                    tint = weatherTint,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = weatherText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (w.available && !w.isStaleOfflineCache) KoPilotClusterTokens.PrimaryText else KoPilotClusterTokens.SecondaryText,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    if (state.settings.compassHudEnabled) {
                        Surface(
                            onClick = { showLargeCompass = true },
                            color = hudGlassBg,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, hudGlassBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Explore,
                                    contentDescription = "Compass",
                                    tint = KoPilotClusterTokens.PrimaryAccent,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = state.compassReading.formatted,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.PrimaryAccent,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // Speed Limit Sign + Road Surface Glass Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = hudGlassBg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, hudGlassBorder),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color.White, CircleShape)
                                .border(2.5.dp, Color(0xFFDC2626), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = limitKmh?.toString() ?: "--",
                                style = MaterialTheme.typography.labelLarge,
                                color = KoPilotClusterTokens.Background,
                                fontWeight = FontWeight.Black,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI SPEED ZONE",
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.PrimaryAccent,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = state.navigationState.destinationName ?: "Kenyan Corridor",
                                style = MaterialTheme.typography.labelMedium,
                                color = KoPilotClusterTokens.PrimaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

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
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = "ROAD SURFACE",
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
                            modifier = Modifier.weight(1f),
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
                            modifier = Modifier.weight(1f),
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

                // Quick Voice, Start/Stop Drive & Navigation Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (state.isDrivingSessionActive) viewModel.endTrip() else viewModel.startDriving()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isDrivingSessionActive) KoPilotClusterTokens.VeryRough else KoPilotClusterTokens.PrimaryAccent,
                            contentColor = if (state.isDrivingSessionActive) Color.White else KoPilotClusterTokens.Background,
                        ),
                    ) {
                        Text(
                            text = if (state.isDrivingSessionActive) "Stop Drive" else "Start Driving",
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
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = hudGlassBg),
                        border = BorderStroke(1.dp, hudGlassBorder),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = KoPilotClusterTokens.PrimaryAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (state.voiceState.isListening) "Listening" else "Voice",
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenNavigation,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = hudGlassBg),
                        border = BorderStroke(1.dp, hudGlassBorder),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "Navigate",
                            tint = KoPilotClusterTokens.SecondaryAccent,
                            modifier = Modifier.size(20.dp),
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
    onDismiss: () -> Unit,
) {
    val cam = state.cameraStatus
    val vis = state.visionState
    val allErrors = (cam.recentErrors + vis.recentErrors).takeLast(10)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline AI Diagnostics") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        Text("Inference Latency: ${vis.lastInferenceLatencyMs} ms")
                        Text("Camera FPS: ${"%.1f".format(cam.cameraFps)} | Inference FPS: ${"%.1f".format(cam.inferenceFps)}")
                        Text("Camera Resolution: ${cam.actualResolution}")
                        Text("Frames Analyzed: ${cam.analyzedFrames} | Dropped: ${cam.droppedFrames}")
                        Text("Thermal: ${cam.thermalState.name} | Battery: ${if (cam.batteryPercent >= 0) "${cam.batteryPercent}%" else "Unknown"}")
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("KoPilot Tools & Pages", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Destination: $destLine\n$weatherLine",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onOpenRoadQuality) { Text("Road Quality") }
                    OutlinedButton(onClick = onOpenDrivingBehaviour) { Text("Driving Behaviour") }
                    OutlinedButton(onClick = onOpenDrivingIntelligence) { Text("Live Intelligence") }
                    OutlinedButton(onClick = onOpenVehicleIntelligence) { Text("Ask Car AI") }
                    OutlinedButton(onClick = onOpenSensors) { Text("Sensors Hub") }
                    OutlinedButton(onClick = onOpenObd) { Text("OBD-II Gauges") }
                    OutlinedButton(onClick = onOpenSoundStudio) { Text("Engine Sound Studio") }
                    OutlinedButton(onClick = onOpenTrips) { Text("My Trips (.txt)") }
                    OutlinedButton(onClick = onOpenSignsDb) { Text("Road Signs DB") }
                    OutlinedButton(onClick = onOpenVisionDiag) { Text("Offline AI Diag") }
                    OutlinedButton(onClick = onToggleMuteAlerts) {
                        Text(if (state.settings.alertsMuted) "Unmute Alerts" else "Mute Alerts")
                    }
                    Button(
                        onClick = onCallEmergency,
                        colors = ButtonDefaults.buttonColors(containerColor = KoPilotColors.DangerRed),
                    ) {
                        Text("Emergency Call")
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
