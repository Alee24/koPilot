package ke.co.kopilot.feature.sensors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.core.designsystem.theme.KoPilotColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SensorsScreen(
    onBack: () -> Unit,
    onOpenTrips: () -> Unit = {},
    initialTab: Int = 0,
    viewModel: SensorsViewModel = hiltViewModel(),
) {
    val hub by viewModel.hubState.collectAsStateWithLifecycle()
    val loc by viewModel.locationState.collectAsStateWithLifecycle()
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    val tabs = listOf("Overview", "Road Quality", "Driving Behaviour", "All Sensors")

    Scaffold(
        containerColor = KoPilotClusterTokens.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KoPilotClusterTokens.Background,
                    titleContentColor = KoPilotClusterTokens.PrimaryText,
                    navigationIconContentColor = KoPilotClusterTokens.PrimaryText,
                    actionIconContentColor = KoPilotClusterTokens.PrimaryText,
                ),
                title = {
                    Column {
                        Text("Sensors & Motion Fusion", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Real-time sensors, road quality and driving behaviour",
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Responsive Segmented Cluster Tabs (wraps cleanly on 320dp / large font)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tabs.forEachIndexed { idx, title ->
                    val selected = selectedTab == idx
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) KoPilotClusterTokens.PrimaryAccent else KoPilotClusterTokens.CardSurface,
                        border = BorderStroke(
                            1.dp,
                            if (selected) KoPilotClusterTokens.PrimaryAccent else KoPilotClusterTokens.Border,
                        ),
                        modifier = Modifier.clickable { selectedTab = idx },
                    ) {
                        Text(
                            text = title,
                            color = if (selected) KoPilotClusterTokens.Background else KoPilotClusterTokens.SecondaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> OverviewTab(hub = hub, loc = loc, viewModel = viewModel)
                1 -> RoadQualityTab(hub = hub, loc = loc, onOpenTrips = onOpenTrips)
                2 -> DrivingBehaviourTab(hub = hub)
                else -> AllSensorsTab(hub = hub, timeFmt = timeFmt)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(
    hub: SensorHubState,
    loc: ke.co.kopilot.feature.location.LocationTrackingState,
    viewModel: SensorsViewModel,
) {
    ClusterCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Phone Mount Calibration", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText)
                val mountColor = when (hub.mountCalibrationStatus) {
                    MountCalibrationStatus.CALIBRATED -> KoPilotClusterTokens.SmoothRoad
                    MountCalibrationStatus.CALIBRATION_REQUIRED -> KoPilotClusterTokens.ModerateRoad
                    MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED -> KoPilotClusterTokens.RoughRoad
                    MountCalibrationStatus.SENSOR_UNAVAILABLE -> Color(0xFFEF4444)
                }
                Text(
                    text = hub.mountCalibrationStatus.label,
                    color = mountColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            OutlinedButton(onClick = viewModel::recalibrateMount) {
                Text("Recalibrate", color = KoPilotClusterTokens.PrimaryAccent)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Compass: ${hub.compassReading.formatted} (${hub.compassReading.source})" +
                if (hub.compassReading.interferenceSuspected) " • Interference" else "",
            color = KoPilotClusterTokens.SecondaryText,
            fontSize = 12.sp,
        )
    }

    ClusterCard {
        Text("Live Sensor Monitoring", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hub.isMonitoring) {
                OutlinedButton(onClick = viewModel::stopMonitoring) {
                    Text("Stop Listeners", color = KoPilotClusterTokens.PrimaryText)
                }
            } else {
                Button(
                    onClick = { viewModel.startMonitoring() },
                    colors = ButtonDefaults.buttonColors(containerColor = KoPilotClusterTokens.PrimaryAccent),
                ) {
                    Text("Start Monitoring", color = KoPilotClusterTokens.Background, fontWeight = FontWeight.Bold)
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

    ClusterCard {
        val m = hub.motionAnalysis
        Text("Driving Motion Intelligence", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText)
        Spacer(Modifier.height(4.dp))
        Text("Longitudinal: ${m.longitudinalState.name} (${"%.2f".format(m.longitudinalAccelMps2)} m/s²)", color = KoPilotClusterTokens.SecondaryText)
        Text("Lateral / Turn: ${m.lateralState.name} (${"%.2f".format(m.yawRateRadPerSec)} rad/s)", color = KoPilotClusterTokens.SecondaryText)
        Text(
            "Orientation: Azimuth ${"%.0f".format(m.azimuthDegrees)}° • Pitch ${"%.0f".format(m.pitchDegrees)}° • Roll ${"%.0f".format(m.rollDegrees)}°",
            color = KoPilotClusterTokens.SecondaryText,
        )
        Text(
            "Road Vibration: ${m.roadVibrationLevel.name} (RMS ${"%.2f".format(m.verticalVibrationRmsMps2)} m/s²)",
            color = KoPilotClusterTokens.SecondaryText,
        )
        if (m.phoneMovementDetected) {
            Text("Phone movement detected", color = KoPilotClusterTokens.ModerateRoad, fontWeight = FontWeight.SemiBold)
        }
        if (m.suddenMovementDetected) {
            Text("Sudden impact/jerk detected", color = KoPilotClusterTokens.VeryRough, fontWeight = FontWeight.Bold)
        }
    }

    ClusterCard {
        val fix = loc.currentFix
        Text("GNSS & Location Intelligence", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText)
        Spacer(Modifier.height(4.dp))
        Text("Satellites: ${loc.satellitesUsedInFix} used / ${loc.satellitesInView} in view (Avg C/N0: ${"%.1f".format(loc.averageCn0DbHz)} dB-Hz)", color = KoPilotClusterTokens.SecondaryText)
        if (fix != null) {
            val ageSec = ((System.currentTimeMillis() - fix.timestampMillis) / 1000L).coerceAtLeast(0L)
            Text("Coordinates: ${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}", color = KoPilotClusterTokens.SecondaryText)
            Text("Accuracy: ±${fix.horizontalAccuracyMeters?.let { "%.1f".format(it) } ?: "--"} m • Freshness: ${ageSec}s ago", color = KoPilotClusterTokens.SecondaryText)
        } else {
            Text("GPS fix unavailable", color = KoPilotClusterTokens.SecondaryText)
        }
    }
}

@Composable
private fun RoadQualityTab(
    hub: SensorHubState,
    loc: ke.co.kopilot.feature.location.LocationTrackingState,
    onOpenTrips: () -> Unit,
) {
    val m = hub.motionAnalysis
    val stats = m.behaviourStats
    val roadLabel = roadClassLabel(stats.roadSmoothnessScore)
    val roadColor = roadScoreColor(stats.roadSmoothnessScore)
    val distKm = loc.distanceTraveledMeters / 1000.0
    val gpsSpeed = loc.currentFix?.speedKmh

    // 1. Road Smoothness Monitor Card
    ClusterCard {
        Text("Road Smoothness Monitor", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold)
        Text("Measures road shaking, scores quality and detects bumps.", fontSize = 11.sp, color = KoPilotClusterTokens.SecondaryText)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Circular Score Gauge
            Box(modifier = Modifier.size(124.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                    drawArc(
                        color = KoPilotClusterTokens.ElevatedSurface,
                        startAngle = 140f,
                        sweepAngle = 260f,
                        useCenter = false,
                        style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = roadColor,
                        startAngle = 140f,
                        sweepAngle = 260f * (stats.roadSmoothnessScore / 100f).coerceIn(0f, 1f),
                        useCenter = false,
                        style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Road Score", fontSize = 10.sp, color = KoPilotClusterTokens.SecondaryText)
                    Text("${stats.roadSmoothnessScore}/100", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = KoPilotClusterTokens.PrimaryText)
                    Text(roadLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = roadColor)
                }
            }

            // 4-band Breakdown
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BandPctRow("Smooth (80–100)", stats.smoothPct, KoPilotClusterTokens.SmoothRoad)
                BandPctRow("Moderate (55–79)", stats.moderatePct, KoPilotClusterTokens.ModerateRoad)
                BandPctRow("Rough (30–54)", stats.roughPct, KoPilotClusterTokens.RoughRoad)
                BandPctRow("Very Rough (0–29)", stats.veryRoughPct, KoPilotClusterTokens.VeryRough)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiSubCard("Bumps / Potholes", "${stats.potholeBumpCount}", Modifier.weight(1f))
            KpiSubCard("RMS Vibration", "${"%.2f".format(m.verticalVibrationRmsMps2)} m/s²", Modifier.weight(1f))
            KpiSubCard("Trip Distance", "${"%.1f".format(distKm)} km", Modifier.weight(1f))
        }
    }

    // 2. Live Road Vibration (Last 90 s) Canvas Graph
    ClusterCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Live Road Vibration (Last 90 s)", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold)
            Surface(
                shape = RoundedCornerShape(50),
                color = if (hub.isMonitoring) KoPilotClusterTokens.SmoothRoad.copy(alpha = 0.2f) else KoPilotClusterTokens.ElevatedSurface,
            ) {
                Text(
                    text = if (hub.isMonitoring) "• LIVE" else "IDLE",
                    color = if (hub.isMonitoring) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LiveVibrationChart(
            samples = stats.recentSamples,
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LegendDot("Smooth (< 0.7)", KoPilotClusterTokens.SmoothRoad)
            LegendDot("Moderate (0.7–1.4)", KoPilotClusterTokens.ModerateRoad)
            LegendDot("Rough (1.4–2.4)", KoPilotClusterTokens.RoughRoad)
            LegendDot("Very Rough (≥ 2.4)", KoPilotClusterTokens.VeryRough)
        }
    }

    // 3. Current Road Condition
    ClusterCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Current Road Condition", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText)
                Text(
                    text = "RMS: ${"%.2f".format(m.verticalVibrationRmsMps2)} m/s² • Speed: ${gpsSpeed?.toInt() ?: "--"} km/h • Mount: ${if (m.phoneMovementDetected) "Moving" else "Stable"}",
                    fontSize = 11.sp,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = roadColor.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, roadColor),
            ) {
                Text(
                    text = roadLabel,
                    color = roadColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }

    // 4. Trip Road-Quality Summary
    ClusterCard {
        Text("Trip Road-Quality Summary", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Average Road Score", color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
            Text("${stats.averageRoadScore} / 100", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        ConditionProgressRow("Smooth Road", stats.smoothPct, distKm, KoPilotClusterTokens.SmoothRoad)
        ConditionProgressRow("Moderate Road", stats.moderatePct, distKm, KoPilotClusterTokens.ModerateRoad)
        ConditionProgressRow("Rough Road", stats.roughPct, distKm, KoPilotClusterTokens.RoughRoad)
        ConditionProgressRow("Very Rough", stats.veryRoughPct, distKm, KoPilotClusterTokens.VeryRough)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total Bumps / Potholes", color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
            Text("${stats.potholeBumpCount}", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = KoPilotClusterTokens.ElevatedSurface,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenTrips),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent, modifier = Modifier.size(18.dp))
                    Text("View Trip Log (.txt)", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = KoPilotClusterTokens.SecondaryText)
            }
        }
    }
}

@Composable
private fun DrivingBehaviourTab(hub: SensorHubState) {
    val m = hub.motionAnalysis
    val stats = m.behaviourStats
    ClusterCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Driving Behaviour Monitor", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold)
            Text(
                text = "${stats.driverBehaviourScore} / 100",
                color = if (stats.driverBehaviourScore >= 80) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.ModerateRoad,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiSubCard("Braking Events", "${stats.brakingCount}", Modifier.weight(1f))
            KpiSubCard("Harsh Braking", "${stats.harshBrakingCount}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiSubCard("Accelerations", "${stats.accelerationCount}", Modifier.weight(1f))
            KpiSubCard("Hard Accel", "${stats.hardAccelerationCount}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KpiSubCard("Sharp Turns", "${stats.sharpCornerCount}", Modifier.weight(1f))
            KpiSubCard("Longitudinal", "${"%.2f".format(m.longitudinalAccelMps2)} m/s²", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text("Current State: ${m.longitudinalState.name} • ${m.lateralState.name}", color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
    }
}

@Composable
private fun AllSensorsTab(
    hub: SensorHubState,
    timeFmt: SimpleDateFormat,
) {
    Text("Physical Hardware Sensors (${hub.physicalSensors.count { it.isAvailable }} available)", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText)
    hub.physicalSensors.forEach { s ->
        ClusterCard {
            Text("${s.typeName} — ${s.status.name}", fontWeight = FontWeight.SemiBold, color = KoPilotClusterTokens.PrimaryText)
            Text("${s.name} (${s.vendor})", style = MaterialTheme.typography.bodyMedium, color = KoPilotClusterTokens.SecondaryText)
            Text("Reading: ${s.formattedReading} ${s.unit}".trim(), fontWeight = FontWeight.Medium, color = KoPilotClusterTokens.PrimaryAccent)
            val tsText = if (s.timestampMillis > 0L) timeFmt.format(Date(s.timestampMillis)) else "N/A"
            Text(
                "Category: ${s.category.name} • Rate: ${"%.1f".format(s.samplingHz)} Hz • Power: ${"%.2f".format(s.powerMa)} mA • Accuracy: ${s.accuracyLabel} • Updated: $tsText",
                fontSize = 11.sp,
                color = KoPilotClusterTokens.SecondaryText,
            )
            Text("Calibration: ${s.calibrationNote}", fontSize = 11.sp, color = KoPilotClusterTokens.SecondaryText)
        }
    }

    Text("Camera, Audio & Connectivity Interfaces", style = MaterialTheme.typography.titleMedium, color = KoPilotClusterTokens.PrimaryText)
    ClusterCard {
        Text("Cameras (${hub.cameras.size})", fontWeight = FontWeight.SemiBold, color = KoPilotClusterTokens.PrimaryText)
        hub.cameras.forEach { cam ->
            Text(
                "ID ${cam.cameraId} (${cam.lensFacing}): Focal ${cam.focalLengthsMm.joinToString()} mm • AF=${cam.autofocusSupported} • OIS=${cam.opticalStabilizationSupported} • EIS=${cam.videoStabilizationSupported}",
                fontSize = 12.sp,
                color = KoPilotClusterTokens.SecondaryText,
            )
        }
        Spacer(Modifier.height(6.dp))
        hub.audio?.let { a ->
            Text("Audio: Mic=${a.microphoneFeaturePresent} • BT Audio=${a.bluetoothAudioConnected}", fontWeight = FontWeight.SemiBold, color = KoPilotClusterTokens.PrimaryText)
            Text("Inputs: ${a.inputDevices.joinToString().ifBlank { "None" }}", fontSize = 11.sp, color = KoPilotClusterTokens.SecondaryText)
        }
        Spacer(Modifier.height(6.dp))
        hub.connectivity?.let { c ->
            Text("Connectivity", fontWeight = FontWeight.SemiBold, color = KoPilotClusterTokens.PrimaryText)
            Text("BT Classic=${c.bluetoothClassicSupported} (On=${c.bluetoothEnabled}) • BLE=${c.bleSupported}", fontSize = 12.sp, color = KoPilotClusterTokens.SecondaryText)
            Text("Wi-Fi=${c.wifiSupported} (Connected=${c.wifiConnected}) • Cellular=${c.cellularAvailable}", fontSize = 12.sp, color = KoPilotClusterTokens.SecondaryText)
        }
    }
}

@Composable
private fun LiveVibrationChart(
    samples: List<RoadVibrationSample>,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = KoPilotClusterTokens.Background,
        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Y-axis labels (4.0 down to 0.0 m/s²)
                Column(
                    modifier = Modifier.padding(end = 6.dp).height(110.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf("4.0", "3.0", "2.0", "1.0", "0.0").forEach { lbl ->
                        Text(lbl, fontSize = 9.sp, color = KoPilotClusterTokens.SecondaryText)
                    }
                }
                Box(modifier = Modifier.weight(1f).height(110.dp)) {
                    if (samples.size < 2) {
                        Text(
                            text = "Waiting for sensor samples (Start Monitoring or Start Driving)...",
                            color = KoPilotClusterTokens.SecondaryText,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val maxRms = 4.0f

                            // Threshold lines at 0.7, 1.4, 2.4 m/s²
                            listOf(
                                0.7f to KoPilotClusterTokens.ModerateRoad,
                                1.4f to KoPilotClusterTokens.RoughRoad,
                                2.4f to KoPilotClusterTokens.VeryRough,
                            ).forEach { (thresh, col) ->
                                val y = h * (1f - (thresh / maxRms).coerceIn(0f, 1f))
                                drawLine(
                                    color = col.copy(alpha = 0.30f),
                                    start = Offset(0f, y),
                                    end = Offset(w, y),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }

                            val stepX = w / (samples.size - 1).coerceAtLeast(1)
                            val fillPath = Path()
                            samples.forEachIndexed { idx, s ->
                                val x = idx * stepX
                                val y = h * (1f - (s.rmsMps2 / maxRms).coerceIn(0f, 1f))
                                if (idx == 0) {
                                    fillPath.moveTo(x, h)
                                    fillPath.lineTo(x, y)
                                } else {
                                    fillPath.lineTo(x, y)
                                }
                            }
                            fillPath.lineTo((samples.size - 1) * stepX, h)
                            fillPath.close()
                            drawPath(fillPath, color = KoPilotClusterTokens.SmoothRoad.copy(alpha = 0.14f))

                            for (i in 1 until samples.size) {
                                val prev = samples[i - 1]
                                val curr = samples[i]
                                val x1 = (i - 1) * stepX
                                val y1 = h * (1f - (prev.rmsMps2 / maxRms).coerceIn(0f, 1f))
                                val x2 = i * stepX
                                val y2 = h * (1f - (curr.rmsMps2 / maxRms).coerceIn(0f, 1f))
                                val segColor = rmsColor(curr.rmsMps2)
                                drawLine(
                                    color = segColor,
                                    start = Offset(x1, y1),
                                    end = Offset(x2, y2),
                                    strokeWidth = 2.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                                if (curr.isBumpSpike) {
                                    drawCircle(
                                        color = KoPilotClusterTokens.VeryRough,
                                        radius = 4.dp.toPx(),
                                        center = Offset(x2, y2),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("-90 s", "-60 s", "-30 s", "Now").forEach { lbl ->
                    Text(lbl, fontSize = 9.sp, color = KoPilotClusterTokens.SecondaryText)
                }
            }
        }
    }
}

@Composable
private fun BandPctRow(label: String, pct: Int, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, fontSize = 11.sp, color = KoPilotClusterTokens.SecondaryText)
        }
        Text("$pct%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KoPilotClusterTokens.PrimaryText)
    }
}

@Composable
private fun ConditionProgressRow(label: String, pct: Int, totalKm: Double, color: Color) {
    val segKm = totalKm * (pct / 100.0)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = KoPilotClusterTokens.SecondaryText,
            modifier = Modifier.weight(0.95f),
        )
        LinearProgressIndicator(
            progress = { (pct / 100f).coerceIn(0f, 1f) },
            color = color,
            trackColor = KoPilotClusterTokens.ElevatedSurface,
            modifier = Modifier.weight(1.1f).height(6.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text(
            text = "$pct% (${"%.1f".format(segKm)} km)",
            fontSize = 11.sp,
            color = KoPilotClusterTokens.PrimaryText,
            modifier = Modifier.weight(0.9f),
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 9.sp, color = KoPilotClusterTokens.SecondaryText)
    }
}

@Composable
private fun KpiSubCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = KoPilotClusterTokens.ElevatedSurface,
        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, fontSize = 10.sp, color = KoPilotClusterTokens.SecondaryText)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KoPilotClusterTokens.PrimaryText)
        }
    }
}

@Composable
private fun ClusterCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = KoPilotClusterTokens.CardSurface,
        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

private fun roadClassLabel(score: Int): String = when {
    score >= 80 -> "Smooth"
    score >= 55 -> "Moderate"
    score >= 30 -> "Rough"
    else -> "Very Rough"
}

private fun roadScoreColor(score: Int): Color = when {
    score >= 80 -> KoPilotClusterTokens.SmoothRoad
    score >= 55 -> KoPilotClusterTokens.ModerateRoad
    score >= 30 -> KoPilotClusterTokens.RoughRoad
    else -> KoPilotClusterTokens.VeryRough
}

private fun rmsColor(rms: Float): Color = when {
    rms < 0.7f -> KoPilotClusterTokens.SmoothRoad
    rms < 1.4f -> KoPilotClusterTokens.ModerateRoad
    rms < 2.4f -> KoPilotClusterTokens.RoughRoad
    else -> KoPilotClusterTokens.VeryRough
}
