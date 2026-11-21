package ke.co.kopilot.ecu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ke.co.kopilot.feature.dashboard.DashboardUiState
import ke.co.kopilot.feature.diagnostics.EcuInsight
import ke.co.kopilot.feature.diagnostics.EcuPerformanceAnalysis
import ke.co.kopilot.feature.diagnostics.EcuSample
import ke.co.kopilot.feature.diagnostics.EcuVehicleBaseline
import ke.co.kopilot.feature.diagnostics.ObdLiveTelemetry
import ke.co.kopilot.feature.diagnostics.ObdPidCapability
import ke.co.kopilot.feature.diagnostics.PidSupportStatus
import ke.co.kopilot.feature.diagnostics.SpeedEfficiencyBucket
import java.util.Locale

private val EcuEmerald = Color(0xFF22C55E)
private val EcuBlue = Color(0xFF38BDF8)
private val EcuAmber = Color(0xFFF59E0B)
private val EcuRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcuPerformanceDashboardScreen(
    state: DashboardUiState,
    liveTelemetry: ObdLiveTelemetry,
    pidCapabilities: List<ObdPidCapability>,
    obdProtocol: String,
    obdLatencyMs: Long?,
    dtcCodes: List<String>,
    onScanDtc: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onBack: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("All", "Live ECU", "Fuel & Speed", "Engine & Temp", "DTC & Quality")
    val analysis = state.ecuPerformanceAnalysis
    val baseline = state.ecuBaseline

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ECU Performance Intelligence",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (state.diagnosticsState.obdConnected) {
                                "OBD-II Read-Only • ${state.ecuSamples.size} samples"
                            } else {
                                "OBD-II Offline • Cached & Trip Analytics"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.diagnosticsState.obdConnected) EcuEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tabs.indices.toList()) { index ->
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(tabs[index], maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EcuBlue.copy(alpha = 0.18f),
                            selectedLabelColor = EcuBlue,
                        ),
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (selectedTab == 0) {
                    item {
                        OverviewScoreCard(
                            analysis = analysis,
                            obdConnected = state.diagnosticsState.obdConnected,
                            onOpenMaintenance = onOpenMaintenance,
                        )
                    }
                }
                if (selectedTab == 0 || selectedTab == 1) {
                    item {
                        LiveEcuMonitorCard(
                            tel = liveTelemetry,
                            obdConnected = state.diagnosticsState.obdConnected,
                        )
                    }
                }
                if (selectedTab == 0 || selectedTab == 2) {
                    item { FuelEfficiencyCard(analysis = analysis) }
                    item { OptimalSpeedAnalysisCard(analysis = analysis) }
                }
                if (selectedTab == 0 || selectedTab == 3) {
                    item { RpmAndLoadCard(samples = state.ecuSamples, analysis = analysis) }
                    item { TemperatureIntelligenceCard(samples = state.ecuSamples, analysis = analysis) }
                    item { AirflowAndFuelTrimCard(analysis = analysis) }
                    item { UphillPerformanceCard(analysis = analysis) }
                }
                if (selectedTab == 0 || selectedTab == 4) {
                    item {
                        DtcDiagnosticsCard(
                            dtcCodes = dtcCodes,
                            obdConnected = state.diagnosticsState.obdConnected,
                            onScanDtc = onScanDtc,
                        )
                    }
                }
                if (selectedTab == 0) {
                    item { LongTermTrendsCard(analysis = analysis, baseline = baseline) }
                    item { RecommendationsCard(insights = analysis.insights) }
                }
                if (selectedTab == 0 || selectedTab == 4) {
                    item {
                        DataQualityCard(
                            capabilities = pidCapabilities,
                            protocol = obdProtocol,
                            latencyMs = obdLatencyMs,
                            sampleCount = state.ecuSamples.size,
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EcuCard(
    title: String,
    icon: ImageVector,
    badge: String? = null,
    badgeColor: Color = EcuBlue,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badge != null) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = badge,
                            color = badgeColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun OverviewScoreCard(
    analysis: EcuPerformanceAnalysis,
    obdConnected: Boolean,
    onOpenMaintenance: () -> Unit,
) {
    val score = analysis.performanceScore
    val scoreColor = when {
        score == null -> MaterialTheme.colorScheme.onSurfaceVariant
        score >= 85 -> EcuEmerald
        score >= 70 -> EcuAmber
        else -> EcuRed
    }
    EcuCard(
        title = "Overview — Nissan Tiida 1.5L CVT",
        icon = Icons.Rounded.DirectionsCar,
        badge = if (obdConnected) "OBD Live" else "Standby",
        badgeColor = if (obdConnected) EcuEmerald else EcuAmber,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(84.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = scoreColor.copy(alpha = 0.18f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
                    )
                    if (score != null) {
                        drawArc(
                            color = scoreColor,
                            startAngle = -90f,
                            sweepAngle = (score / 100f) * 360f,
                            useCenter = false,
                            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = score?.toString() ?: "--",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                    )
                    Text(
                        text = "/100",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetricLine("Distance", if (analysis.distanceKm > 0.0) String.format(Locale.US, "%.2f km", analysis.distanceKm) else "0.00 km")
                MetricLine("Economy", analysis.kmPerLiter?.let { String.format(Locale.US, "%.1f km/L", it) } ?: "Unavailable")
                MetricLine("Cruising", analysis.recommendedCruisingRange ?: "Insufficient data")
                MetricLine("Insights", "${analysis.insights.size} active findings")
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onOpenMaintenance,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open Vehicle Maintenance & Service")
        }
    }
}

@Composable
private fun LiveEcuMonitorCard(
    tel: ObdLiveTelemetry,
    obdConnected: Boolean,
) {
    EcuCard(
        title = "Live ECU Monitor",
        icon = Icons.Rounded.Speed,
        badge = if (obdConnected) "Read-Only" else "Disconnected",
        badgeColor = if (obdConnected) EcuBlue else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        val cells = listOf(
            "RPM" to (tel.engineRpm?.let { "$it rpm" } ?: "Unavailable"),
            "OBD Speed" to (tel.vehicleSpeedKmh?.let { "$it km/h" } ?: "Unavailable"),
            "Engine Load" to (tel.engineLoadPct?.let { String.format(Locale.US, "%.0f%%", it) } ?: "Unavailable"),
            "Throttle" to (tel.throttlePositionPct?.let { String.format(Locale.US, "%.0f%%", it) } ?: "Unavailable"),
            "Coolant" to (tel.coolantTempC?.let { "$it °C" } ?: "Unavailable"),
            "MAF Airflow" to (tel.mafGramsPerSec?.let { String.format(Locale.US, "%.1f g/s", it) } ?: "Unavailable"),
            "Voltage" to ((tel.controlModuleVoltageV ?: tel.adapterVoltageV)?.let { String.format(Locale.US, "%.1f V", it) } ?: "Unavailable"),
            "Fuel Rate" to (tel.engineFuelRateLph?.let { String.format(Locale.US, "%.2f L/h", it) } ?: "Unavailable"),
        )
        cells.chunked(2).forEach { pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                pair.forEach { (label, value) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (value == "Unavailable") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FuelEfficiencyCard(analysis: EcuPerformanceAnalysis) {
    EcuCard(
        title = "Fuel Efficiency",
        icon = Icons.Rounded.LocalGasStation,
        badge = analysis.fuelProvenance,
        badgeColor = if (analysis.fuelProvenance == "Unavailable") EcuAmber else EcuEmerald,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatTile("Fuel Used", analysis.totalFuelUsedLiters?.let { String.format(Locale.US, "%.2f L", it) } ?: "--", Modifier.weight(1f))
            StatTile("L/100km", analysis.litersPer100Km?.let { String.format(Locale.US, "%.1f", it) } ?: "--", Modifier.weight(1f))
            StatTile("km/L", analysis.kmPerLiter?.let { String.format(Locale.US, "%.1f", it) } ?: "--", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        MetricLine("Idle Fuel", analysis.idleFuelLiters?.let { String.format(Locale.US, "%.2f L", it) } ?: "Unavailable")
        MetricLine("Acceleration Fuel", analysis.accelFuelLiters?.let { String.format(Locale.US, "%.2f L", it) } ?: "Unavailable")
        MetricLine("Steady Cruise Fuel", analysis.cruiseFuelLiters?.let { String.format(Locale.US, "%.2f L", it) } ?: "Unavailable")
    }
}

@Composable
private fun OptimalSpeedAnalysisCard(analysis: EcuPerformanceAnalysis) {
    EcuCard(
        title = "Optimal Speed Analysis",
        icon = Icons.Rounded.Timeline,
        badge = analysis.recommendedCruisingRange ?: "Collecting",
        badgeColor = EcuEmerald,
    ) {
        if (analysis.speedBuckets.isEmpty()) {
            Text(
                text = "Drive across multiple speed ranges with OBD-II active to compute cruising efficiency buckets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SpeedBucketBarChart(buckets = analysis.speedBuckets, bestLabel = analysis.recommendedCruisingRange)
            Spacer(modifier = Modifier.height(8.dp))
            analysis.speedBuckets.forEach { bucket ->
                val isBest = bucket.label == analysis.recommendedCruisingRange
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        text = bucket.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                        color = if (isBest) EcuEmerald else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = bucket.avgLitersPer100Km?.let { String.format(Locale.US, "%.1f L/100km", it) } ?: "--",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${bucket.observationSeconds}s (${bucket.confidence})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedBucketBarChart(
    buckets: List<SpeedEfficiencyBucket>,
    bestLabel: String?,
) {
    val maxVal = buckets.mapNotNull { it.avgLitersPer100Km }.maxOrNull()?.coerceAtLeast(1.0) ?: 10.0
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        val count = buckets.size.coerceAtLeast(1)
        val slotWidth = size.width / count
        val barWidth = slotWidth * 0.55f
        buckets.forEachIndexed { idx, b ->
            val v = (b.avgLitersPer100Km ?: 0.0).toFloat()
            val ratio = (v / maxVal.toFloat()).coerceIn(0f, 1f)
            val barH = size.height * ratio
            val x = idx * slotWidth + (slotWidth - barWidth) / 2f
            val y = size.height - barH
            drawRect(
                color = if (b.label == bestLabel) EcuEmerald else EcuBlue,
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
            )
        }
    }
}

@Composable
private fun RpmAndLoadCard(
    samples: List<EcuSample>,
    analysis: EcuPerformanceAnalysis,
) {
    EcuCard(
        title = "RPM & Engine Load (CVT)",
        icon = Icons.Rounded.Speed,
        badge = analysis.avgRpm?.let { "$it avg RPM" } ?: "No Samples",
        badgeColor = EcuBlue,
    ) {
        val rpmPoints = samples.mapNotNull { it.rpm?.toFloat() }
        val loadPoints = samples.mapNotNull { it.engineLoadPct }
        if (rpmPoints.size >= 2) {
            DualTrendChart(
                primary = rpmPoints,
                secondary = loadPoints,
                primaryColor = EcuBlue,
                secondaryColor = EcuAmber,
                primaryMax = 6000f,
                secondaryMax = 100f,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        MetricLine("Min / Avg / Max RPM", "${analysis.minRpm ?: "--"} / ${analysis.avgRpm ?: "--"} / ${analysis.maxRpm ?: "--"}")
        MetricLine("Average Engine Load", analysis.avgLoadPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "Unavailable")
        MetricLine("Average Throttle", analysis.avgThrottlePct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "Unavailable")
        MetricLine("Idle RPM Stability (StdDev)", analysis.idleRpmStdDev?.let { String.format(Locale.US, "%.1f rpm", it) } ?: "Unavailable")
    }
}

@Composable
private fun TemperatureIntelligenceCard(
    samples: List<EcuSample>,
    analysis: EcuPerformanceAnalysis,
) {
    val hasExcursion = analysis.excursionCountAbove102C > 0
    EcuCard(
        title = "Engine Temperature Intelligence",
        icon = Icons.Rounded.Thermostat,
        badge = if (hasExcursion) "${analysis.excursionCountAbove102C} >=102°C" else "Normal",
        badgeColor = if (hasExcursion) EcuRed else EcuEmerald,
    ) {
        val tempPoints = samples.mapNotNull { it.coolantTempC?.toFloat() }
        if (tempPoints.size >= 2) {
            DualTrendChart(
                primary = tempPoints,
                secondary = emptyList(),
                primaryColor = if (hasExcursion) EcuRed else EcuEmerald,
                secondaryColor = EcuAmber,
                primaryMax = 120f,
                secondaryMax = 100f,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        MetricLine("Min / Avg / Max Coolant", "${analysis.minCoolantC ?: "--"} / ${analysis.avgCoolantC ?: "--"} / ${analysis.maxCoolantC ?: "--"} °C")
        MetricLine("Warm-up to 75°C", analysis.warmupSecondsTo75C?.let { "${it}s" } ?: "Not observed")
        MetricLine("Excursions >= 102°C", "${analysis.excursionCountAbove102C}")
    }
}

@Composable
private fun AirflowAndFuelTrimCard(analysis: EcuPerformanceAnalysis) {
    val ltftAlert = analysis.avgLtftPct?.let { kotlin.math.abs(it) >= 10f } == true
    EcuCard(
        title = "Airflow & Fuel Trims",
        icon = Icons.Rounded.Sensors,
        badge = if (ltftAlert) "Trim Deviation" else "Nominal",
        badgeColor = if (ltftAlert) EcuAmber else EcuEmerald,
    ) {
        MetricLine("Average MAF", analysis.avgMafGps?.let { String.format(Locale.US, "%.2f g/s", it) } ?: "Unavailable")
        MetricLine("Average MAP", analysis.avgMapKpa?.let { "$it kPa" } ?: "Unavailable")
        MetricLine("Short-Term Fuel Trim (STFT)", analysis.avgStftPct?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "Unavailable")
        MetricLine("Long-Term Fuel Trim (LTFT)", analysis.avgLtftPct?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "Unavailable")
    }
}

@Composable
private fun UphillPerformanceCard(analysis: EcuPerformanceAnalysis) {
    EcuCard(
        title = "Uphill Performance",
        icon = Icons.Rounded.Terrain,
        badge = "${analysis.uphillSampleCount} samples",
        badgeColor = EcuBlue,
    ) {
        MetricLine("Uphill Samples (>=3% grade)", "${analysis.uphillSampleCount}")
        MetricLine("Uphill Avg RPM", analysis.uphillAvgRpm?.let { "$it rpm" } ?: "Unavailable")
        MetricLine("Uphill Avg Engine Load", analysis.uphillAvgLoadPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "Unavailable")
    }
}

@Composable
private fun DtcDiagnosticsCard(
    dtcCodes: List<String>,
    obdConnected: Boolean,
    onScanDtc: () -> Unit,
) {
    EcuCard(
        title = "Diagnostic Trouble Codes (DTC)",
        icon = Icons.Rounded.ErrorOutline,
        badge = if (dtcCodes.isEmpty()) "Clear" else "${dtcCodes.size} Active",
        badgeColor = if (dtcCodes.isEmpty()) EcuEmerald else EcuRed,
    ) {
        if (dtcCodes.isEmpty()) {
            Text(
                text = "No active diagnostic trouble codes reported by ECU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dtcCodes.forEach { code ->
                Text(
                    text = "• $code",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = EcuRed,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onScanDtc,
            enabled = obdConnected,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(if (obdConnected) "Scan ECU Trouble Codes (Mode 03)" else "Connect OBD-II to Scan DTCs")
        }
    }
}

@Composable
private fun LongTermTrendsCard(
    analysis: EcuPerformanceAnalysis,
    baseline: EcuVehicleBaseline,
) {
    EcuCard(
        title = "Long-Term Vehicle Trends",
        icon = Icons.Rounded.Timeline,
        badge = "${baseline.tripCount} trips",
        badgeColor = EcuBlue,
    ) {
        if (baseline.tripCount == 0) {
            Text(
                text = "Complete trips with OBD-II connected to build your vehicle's multi-trip baseline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MetricLine("Baseline Avg RPM", "${baseline.avgRpm ?: "--"} (This trip: ${analysis.avgRpm ?: "--"})")
            MetricLine("Baseline Avg Load", "${baseline.avgLoadPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"} (This trip: ${analysis.avgLoadPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"})")
            MetricLine("Baseline Coolant", "${baseline.avgCoolantC ?: "--"} °C (This trip: ${analysis.avgCoolantC ?: "--"} °C)")
            MetricLine("Baseline L/100km", "${baseline.avgLitersPer100Km?.let { String.format(Locale.US, "%.1f", it) } ?: "--"} (This trip: ${analysis.litersPer100Km?.let { String.format(Locale.US, "%.1f", it) } ?: "--"})")
            MetricLine("Baseline LTFT", "${baseline.avgLtftPct?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "--"} (This trip: ${analysis.avgLtftPct?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "--"})")
        }
    }
}

@Composable
private fun RecommendationsCard(insights: List<EcuInsight>) {
    EcuCard(
        title = "Performance Recommendations",
        icon = Icons.Rounded.CheckCircle,
        badge = "${insights.size} items",
        badgeColor = EcuEmerald,
    ) {
        if (insights.isEmpty()) {
            Text(
                text = "No evidence-backed recommendations yet. Collect more driving samples with OBD-II connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            insights.forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (item.inspectionAppropriate) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = if (item.inspectionAppropriate) EcuAmber else EcuEmerald,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(item.evidence, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Action: ${item.recommendedAction} • Confidence: ${item.confidence} (${item.affectedTrips})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DataQualityCard(
    capabilities: List<ObdPidCapability>,
    protocol: String,
    latencyMs: Long?,
    sampleCount: Int,
) {
    val supportedCount = capabilities.count { it.status == PidSupportStatus.SUPPORTED }
    EcuCard(
        title = "Data Quality & Sensor Availability",
        icon = Icons.Rounded.Sensors,
        badge = "$supportedCount/${capabilities.size} PIDs",
        badgeColor = EcuBlue,
    ) {
        MetricLine("Protocol", protocol.ifBlank { "Auto" })
        MetricLine("ECU Latency", latencyMs?.let { "$it ms" } ?: "Unavailable")
        MetricLine("Trip Samples", "$sampleCount")
        if (capabilities.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            capabilities.take(16).forEach { cap ->
                val statusColor = when (cap.status) {
                    PidSupportStatus.SUPPORTED -> EcuEmerald
                    PidSupportStatus.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    PidSupportStatus.TEMPORARILY_UNAVAILABLE,
                    PidSupportStatus.REQUIRES_MANUFACTURER_INTEGRATION -> EcuAmber
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text("${cap.pidHex} • ${cap.name}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text(cap.status.name, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DualTrendChart(
    primary: List<Float>,
    secondary: List<Float>,
    primaryColor: Color,
    secondaryColor: Color,
    primaryMax: Float,
    secondaryMax: Float,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        fun drawSeries(pts: List<Float>, maxVal: Float, color: Color) {
            if (pts.size < 2) return
            val path = Path()
            val stepX = size.width / (pts.size - 1).coerceAtLeast(1)
            pts.forEachIndexed { i, v ->
                val x = i * stepX
                val norm = (v / maxVal).coerceIn(0f, 1f)
                val y = size.height * (1f - norm)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        drawSeries(primary, primaryMax, primaryColor)
        if (secondary.size >= 2) {
            drawSeries(secondary, secondaryMax, secondaryColor)
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
