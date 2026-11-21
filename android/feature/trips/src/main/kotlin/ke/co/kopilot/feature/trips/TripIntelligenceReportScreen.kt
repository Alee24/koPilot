package ke.co.kopilot.feature.trips

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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ke.co.kopilot.feature.navigation.KoPilotNavigationMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ReportBg = Color(0xFF071019)
private val ReportCard = Color(0xFF101F2B)
private val ReportBorder = Color(0xFF263A49)
private val ReportBlue = Color(0xFF2687F5)
private val ReportGreen = Color(0xFF16D98A)
private val ReportAmber = Color(0xFFF5C542)
private val ReportRed = Color(0xFFEF4444)
private val ReportText = Color(0xFFF5F8FA)
private val ReportMuted = Color(0xFFA4B4C3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripIntelligenceReportScreen(
    report: TripIntelligenceReport,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val tabs = listOf("OVERVIEW", "DETAILS", "EVENTS", "MEDIA", "EXPORT")
    val dateFmt = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()) }

    Scaffold(
        containerColor = ReportBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReportCard,
                    titleContentColor = ReportText,
                    navigationIconContentColor = ReportText,
                    actionIconContentColor = ReportText,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("KoPilot Trip Report", fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                        Text(
                            text = dateFmt.format(Date(report.startedAtMillis)),
                            fontSize = 11.sp,
                            color = ReportMuted,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export PDF Report") },
                                onClick = {
                                    showOverflowMenu = false
                                    val f = TripReportExporter.exportPdf(context, report)
                                    exportStatus = "Generated PDF: ${f.name}"
                                    TripReportExporter.shareFile(context, f, "application/pdf", "KoPilot Trip Report (PDF)")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Jump to Synchronized Replay") },
                                onClick = {
                                    showOverflowMenu = false
                                    selectedTab = 1
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("View Events Timeline") },
                                onClick = {
                                    showOverflowMenu = false
                                    selectedTab = 2
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = ReportCard,
                contentColor = ReportBlue,
                edgePadding = 8.dp,
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(positions[selectedTab]),
                        color = ReportBlue,
                    )
                },
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == idx) ReportBlue else ReportMuted,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }

            exportStatus?.let { msg ->
                Surface(
                    color = ReportCard,
                    border = BorderStroke(1.dp, ReportGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(msg, color = ReportGreen, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                }
            }

            when (selectedTab) {
                0 -> OverviewReportTab(report = report)
                1 -> DetailsReportTab(report = report)
                2 -> EventsReportTab(report = report)
                3 -> MediaReportTab(report = report)
                else -> ExportReportTab(
                    report = report,
                    onExportPdf = {
                        val f = TripReportExporter.exportPdf(context, report)
                        exportStatus = "PDF ready: ${f.name}"
                        TripReportExporter.shareFile(context, f, "application/pdf", "KoPilot Trip Report (PDF)")
                    },
                    onExportCsv = {
                        val f = TripReportExporter.exportCsv(context, report)
                        exportStatus = "CSV ready: ${f.name}"
                        TripReportExporter.shareFile(context, f, "text/csv", "KoPilot Trip Time-Series (CSV)")
                    },
                    onExportJson = {
                        val f = TripReportExporter.exportJson(context, report)
                        exportStatus = "JSON ready: ${f.name}"
                        TripReportExporter.shareFile(context, f, "application/json", "KoPilot Trip Report (JSON)")
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewReportTab(report: TripIntelligenceReport) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 3.1 Trip Completion & Safety Banner
        val isSafe = report.accidentBannerTitle.startsWith("No suspected")
        val bannerColor = when {
            report.accidentBannerTitle.contains("Suspected") -> ReportRed
            isSafe -> ReportGreen
            else -> ReportAmber
        }
        ReportSurface(borderColor = bannerColor.copy(alpha = 0.6f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = if (isSafe) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = bannerColor,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("${report.statusLabel} • ${report.accidentBannerTitle}", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(report.accidentBannerDetail, color = ReportMuted, fontSize = 12.sp)
                }
            }
            if (report.warnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                report.warnings.forEach { w ->
                    Text("• $w", color = ReportAmber, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                report.sensorCoverageSummary.forEach { (sensor, status) ->
                    Surface(shape = RoundedCornerShape(8.dp), color = ReportBg, border = BorderStroke(1.dp, ReportBorder)) {
                        Text("$sensor: $status", color = ReportMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }

        // 3.2 Journey Header
        ReportSurface {
            Text("${report.originLabel} → ${report.destinationLabel}", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            val startStr = timeFmt.format(Date(report.startedAtMillis))
            val endStr = report.endedAtMillis?.let { timeFmt.format(Date(it)) } ?: "Active"
            Text(
                text = "Start: $startStr • End: $endStr • Duration: ${report.durationSeconds / 60}m ${report.durationSeconds % 60}s",
                color = ReportMuted,
                fontSize = 12.sp,
            )
            Text(
                text = "Vehicle: ${report.vehicleName}",
                color = ReportBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 3.3 Four Prominent Summary Metric Cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Distance", "${"%.2f".format(Locale.US, report.distanceKm)} km", Modifier.weight(1f))
            MetricTile("Duration", "${report.durationSeconds / 60}m ${report.durationSeconds % 60}s", Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Avg Moving", report.avgMovingSpeedKmh?.let { "${it.toInt()} km/h" } ?: "Unavailable", Modifier.weight(1f))
            MetricTile("Max Speed", report.maxSpeedKmh?.let { "${it.toInt()} km/h" } ?: "Unavailable", Modifier.weight(1f))
        }

        ReportSurface {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallKpi("Moving Time", "${report.movingTimeSeconds / 60}m ${report.movingTimeSeconds % 60}s")
                SmallKpi("Idle Time", "${report.idleTimeSeconds / 60}m ${report.idleTimeSeconds % 60}s")
                SmallKpi("Avg (incl. stops)", report.avgTripSpeedKmh?.let { "${it.toInt()} km/h" } ?: "Unavailable")
                SmallKpi("GPS Quality", report.gpsQualityLabel)
            }
        }

        // 3.4 Interactive Route Map
        ReportSurface {
            Text("Recorded Route Map", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                if (report.routePoints.isNotEmpty()) {
                    KoPilotNavigationMap(
                        currentLat = report.routePoints.first().latitude,
                        currentLng = report.routePoints.first().longitude,
                        routePoints = report.routePoints,
                        destinationLatLng = report.routePoints.last(),
                        destinationName = report.destinationLabel,
                        hasLocationPermission = true,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(ReportBg), contentAlignment = Alignment.Center) {
                        Text("GPS route history unavailable for this trip.", color = ReportMuted, fontSize = 12.sp)
                    }
                }
            }
        }

        // 3.5 Transparent Explainable Trip Score
        ReportSurface {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Explainable Driving Score", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = report.scoreBreakdown.overallScore?.let { "$it / 100" } ?: "Unavailable",
                    color = ReportGreen,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                )
            }
            Text(report.scoreBreakdown.summaryNote, color = ReportMuted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            report.scoreBreakdown.components.forEach { comp ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${comp.name} (${comp.weightPercent}%)", color = ReportText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${comp.explanation} [${comp.provenance.label}]", color = ReportMuted, fontSize = 11.sp)
                    }
                    Text(
                        text = comp.score?.let { "$it" } ?: "N/A",
                        color = if (comp.score != null) ReportBlue else ReportMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailsReportTab(report: TripIntelligenceReport) {
    var replayCursor by remember { mutableFloatStateOf(0f) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Synchronized Trip Replay
        ReportSurface(borderColor = ReportBlue.copy(alpha = 0.5f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ReportBlue)
                Text("Synchronized Trip Replay", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (report.samples.isEmpty()) {
                Text(
                    "No time-series samples recorded for replay on this trip.",
                    color = ReportMuted,
                    fontSize = 12.sp,
                )
            } else {
                val idx = ((report.samples.size - 1) * replayCursor).toInt().coerceIn(0, report.samples.lastIndex)
                val sample = report.samples[idx]
                Slider(
                    value = replayCursor,
                    onValueChange = { replayCursor = it },
                )
                Text(
                    text = "Time: ${timeFmt.format(Date(sample.timestampMillis))} • Sample ${idx + 1}/${report.samples.size}",
                    color = ReportBlue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Text(
                    text = "GPS: ${sample.gpsSpeedKmh?.let { "${it.toInt()} km/h" } ?: "Unavailable"} | " +
                        "OBD Speed: ${sample.obdSpeedKmh?.let { "${it.toInt()} km/h" } ?: "Unavailable"} | " +
                        "RPM: ${sample.engineRpm ?: "Unavailable"} | " +
                        "Coolant: ${sample.coolantTempC?.let { "$it°C" } ?: "Unavailable"} | " +
                        "MAF: ${sample.mafGramsPerSec?.let { "%.2f g/s".format(it) } ?: "Unavailable"} | " +
                        "HR: ${sample.heartRateBpm?.let { "$it BPM" } ?: "Unavailable"}",
                    color = ReportText,
                    fontSize = 11.sp,
                )
            }
        }

        // Interactive Graphs (RPM, Speed, Heart Rate) when samples exist
        val speeds = report.samples.mapNotNull { it.gpsSpeedKmh ?: it.obdSpeedKmh }
        if (speeds.size >= 2) {
            ReportSurface {
                Text("Speed Profile (km/h)", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                ReportLineChart(values = speeds, lineColor = ReportBlue, modifier = Modifier.fillMaxWidth().height(72.dp))
            }
        }
        val rpms = report.samples.mapNotNull { it.engineRpm?.toFloat() }
        if (rpms.size >= 2) {
            ReportSurface {
                Text("Engine RPM Timeline", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                ReportLineChart(values = rpms, lineColor = ReportGreen, modifier = Modifier.fillMaxWidth().height(72.dp))
            }
        }
        val hrs = report.samples.mapNotNull { it.heartRateBpm?.toFloat() }
        if (hrs.size >= 2) {
            ReportSurface {
                Text("Smartwatch Heart Rate (BPM)", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                ReportLineChart(values = hrs, lineColor = ReportRed, modifier = Modifier.fillMaxWidth().height(72.dp))
            }
        }

        MetricSectionCard("Vehicle OBD-II Diagnostics", Icons.Default.Build, report.obdMetrics)
        MetricSectionCard("Phone Motion & Rough-Road Analytics", Icons.Default.Speed, report.motionMetrics)
        MetricSectionCard("Accident & 5s Verification", Icons.Default.Warning, report.safetyMetrics)
        MetricSectionCard("Smartwatch & Driver Wellness", Icons.Default.Favorite, report.wellnessMetrics)
        MetricSectionCard("Weather & Environment", Icons.Default.WbCloudy, report.weatherMetrics)
        MetricSectionCard("Device & App Performance", Icons.Default.Timeline, report.performanceMetrics)
    }
}

@Composable
private fun EventsReportTab(report: TripIntelligenceReport) {
    var selectedFilter by remember { mutableStateOf<TimelineEventCategory?>(null) }
    var selectedEvent by remember { mutableStateOf<TripTimelineEvent?>(null) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    selectedEvent?.let { ev ->
        AlertDialog(
            onDismissRequest = { selectedEvent = null },
            title = { Text(ev.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Category: ${ev.category.label} • Severity: ${ev.severity}", fontWeight = FontWeight.SemiBold)
                    Text("Time: ${timeFmt.format(Date(ev.timestampMillis))}")
                    if (ev.latitude != null && ev.longitude != null) {
                        Text("Location: ${"%.5f".format(Locale.US, ev.latitude)}, ${"%.5f".format(Locale.US, ev.longitude)}")
                    }
                    Text(ev.description)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedEvent = null }) { Text("Close") }
            },
        )
    }

    val filtered = remember(report.events, selectedFilter) {
        if (selectedFilter == null) report.events else report.events.filter { it.category == selectedFilter }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("All (${report.events.size})", fontSize = 11.sp) },
            )
            TimelineEventCategory.entries.take(3).forEach { cat ->
                FilterChip(
                    selected = selectedFilter == cat,
                    onClick = { selectedFilter = if (selectedFilter == cat) null else cat },
                    label = { Text(cat.label, fontSize = 11.sp) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimelineEventCategory.entries.drop(3).forEach { cat ->
                FilterChip(
                    selected = selectedFilter == cat,
                    onClick = { selectedFilter = if (selectedFilter == cat) null else cat },
                    label = { Text(cat.label, fontSize = 11.sp) },
                )
            }
        }

        if (filtered.isEmpty()) {
            ReportSurface {
                Text("No events recorded in this category.", color = ReportMuted, fontSize = 13.sp)
            }
        } else {
            filtered.forEach { ev ->
                ReportSurface(
                    modifier = Modifier.clickable { selectedEvent = ev },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(ev.title, color = ReportText, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(timeFmt.format(Date(ev.timestampMillis)), color = ReportMuted, fontSize = 11.sp)
                    }
                    Text("${ev.category.label} • ${ev.severity}", color = ReportBlue, fontSize = 11.sp)
                    Text(ev.description, color = ReportMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MediaReportTab(report: TripIntelligenceReport) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricSectionCard("Camera & Road Intelligence", Icons.Default.CameraAlt, report.cameraMetrics)
        if (report.mediaItems.isEmpty()) {
            ReportSurface {
                Text(
                    "No camera detections or media snapshots recorded for this trip. Raw frames are not stored unnecessarily.",
                    color = ReportMuted,
                    fontSize = 12.sp,
                )
            }
        } else {
            report.mediaItems.forEach { item ->
                ReportSurface {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(item.title, color = ReportText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(timeFmt.format(Date(item.timestampMillis)), color = ReportMuted, fontSize = 11.sp)
                    }
                    Text(item.description, color = ReportMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ExportReportTab(
    report: TripIntelligenceReport,
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReportSurface {
            Text("Export Trip Intelligence", color = ReportText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "Generate real, portable files with source provenance and timestamps (Completeness: ${report.dataCompletenessPercent}%).",
                color = ReportMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onExportPdf,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ReportBlue, contentColor = Color.White),
            ) {
                Icon(Icons.Default.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Export PDF Report", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onExportCsv,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, ReportBorder),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = ReportText)
                Spacer(Modifier.width(8.dp))
                Text("Export Time-Series CSV (${report.samples.size} samples)", color = ReportText)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onExportJson,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, ReportBorder),
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = ReportText)
                Spacer(Modifier.width(8.dp))
                Text("Export Machine-Readable JSON", color = ReportText)
            }
        }
    }
}

@Composable
private fun MetricSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    metrics: List<MeasuredMetric<String>>,
) {
    ReportSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = ReportBlue, modifier = Modifier.size(18.dp))
            Text(title, color = ReportText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        metrics.forEach { m ->
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(m.label, color = ReportMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        text = m.formattedValue,
                        color = if (m.provenance == MeasurementProvenance.UNAVAILABLE) ReportMuted else ReportText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = "${m.provenance.label} • ${m.source}",
                    color = ReportMuted.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    ReportSurface(modifier = modifier) {
        Text(label, color = ReportMuted, fontSize = 11.sp)
        Text(value, color = ReportText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun SmallKpi(label: String, value: String) {
    Column {
        Text(label, color = ReportMuted, fontSize = 10.sp)
        Text(value, color = ReportText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun ReportSurface(
    modifier: Modifier = Modifier,
    borderColor: Color = ReportBorder,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ReportCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun ReportLineChart(
    values: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val minV = values.minOrNull() ?: 0f
        val maxV = values.maxOrNull() ?: 1f
        val span = (maxV - minV).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { idx, v ->
            val x = idx * stepX
            val y = size.height * (1f - ((v - minV) / span).coerceIn(0f, 1f))
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
