package ke.co.kopilot.feature.dashboard

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.camera.ThermalState
import ke.co.kopilot.feature.vision.LiteRtAcceleratorBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 6 — Performance & Thermal Management Screen.
 *
 * Displays:
 * - Current Android thermal status & thermal headroom (where supported by hardware)
 * - Battery temperature, battery level, discharge current (where reported by hardware), and JVM/native heap memory
 * - Camera preview FPS, analyzed inference FPS, dropped-frame percentage, and processing queue depth
 * - Average, P50, P95, and P99 inference latency breakdown
 * - Active acceleration backend selector and on-device hardware accelerator benchmark runner
 * - Per-module AI activity and processing frequency (Hz)
 * - Current 4-state thermal management tier (Normal, Warm, Hot, Critical) and active optimizations explanation
 * - Historical performance graph (Inference latency, FPS, and Battery temperature)
 * - Full JSON/text diagnostics export
 *
 * Strictly adheres to KoPilotClusterTokens design system with zero emojis.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PerformanceThermalScreen(
    onBack: () -> Unit,
    viewModel: DashboardViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cam = state.cameraStatus
    val vis = state.visionState
    val perf = vis.performanceMetrics
    val context = LocalContext.current
    var benchmarkRunning by remember { mutableStateOf(false) }

    val thermalColor = when (cam.thermalState) {
        ThermalState.NORMAL -> KoPilotClusterTokens.SmoothRoad
        ThermalState.WARM -> KoPilotClusterTokens.ModerateRoad
        ThermalState.HOT -> KoPilotClusterTokens.RoughRoad
        ThermalState.CRITICAL -> KoPilotClusterTokens.VeryRough
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
                title = {
                    Column {
                        Text(
                            text = "Performance & Thermal",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = "Real-Time Hardware Telemetry, Task Scheduler & Accelerator Profiling",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
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
            // Critical / Hot Thermal Warning Banner when active
            perf.thermalWarningBanner?.let { warning ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = thermalColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, thermalColor),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "THERMAL PROTECTION ACTIVE (${cam.thermalState.label.uppercase()})",
                            color = thermalColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = warning,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // 1. 4-State Adaptive Thermal Status & Hardware Sensors Card
            PerfSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ADAPTIVE THERMAL CONTROLLER",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.SecondaryText,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "State: ${cam.thermalState.label}",
                            style = MaterialTheme.typography.titleMedium,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = thermalColor.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, thermalColor),
                    ) {
                        Text(
                            text = cam.thermalState.name,
                            color = thermalColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                Text(
                    text = cam.thermalState.policySummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )

                HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TelemetryKpiTile(
                        label = "Android OS Thermal",
                        value = cam.osThermalStatusLabel,
                        accent = thermalColor,
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryKpiTile(
                        label = "Thermal Headroom (10s)",
                        value = cam.thermalHeadroom?.let { "%.2f".format(it) } ?: "Unsupported by device",
                        accent = KoPilotClusterTokens.PrimaryAccent,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TelemetryKpiTile(
                        label = "Battery Temperature",
                        value = cam.batteryTemperatureC?.let { "${"%.1f".format(it)} C" } ?: "Unavailable",
                        accent = when {
                            (cam.batteryTemperatureC ?: 0f) >= 45.5f -> KoPilotClusterTokens.VeryRough
                            (cam.batteryTemperatureC ?: 0f) >= 42.0f -> KoPilotClusterTokens.RoughRoad
                            (cam.batteryTemperatureC ?: 0f) >= 38.5f -> KoPilotClusterTokens.ModerateRoad
                            else -> KoPilotClusterTokens.SmoothRoad
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryKpiTile(
                        label = "Battery & Current",
                        value = buildString {
                            append(if (cam.batteryPercent >= 0) "${cam.batteryPercent}%" else "Unknown")
                            cam.batteryCurrentMilliamps?.let { append(" (${it} mA)") }
                        },
                        accent = if (cam.isBatteryLow) KoPilotClusterTokens.VeryRough else KoPilotClusterTokens.PrimaryText,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TelemetryKpiTile(
                        label = "JVM Heap Memory",
                        value = "${cam.jvmHeapUsedMb} MB / ${cam.jvmHeapMaxMb} MB",
                        accent = KoPilotClusterTokens.PrimaryText,
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryKpiTile(
                        label = "Native Tensor Heap",
                        value = "${cam.nativeHeapAllocatedMb} MB",
                        accent = KoPilotClusterTokens.SecondaryAccent,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "Hysteresis rule: Immediate escalation on thermal rise; 15-second cooldown hold + 1.5 C deadband required before stepping down.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            }

            // 2. Camera & Inference Frame Pipeline Telemetry
            PerfSectionCard {
                Text(
                    text = "CAMERA & INFERENCE LATENCY PERCENTILES",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TelemetryKpiTile(
                        label = "Camera Preview FPS",
                        value = "${"%.1f".format(cam.cameraFps)} fps",
                        accent = KoPilotClusterTokens.PrimaryAccent,
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryKpiTile(
                        label = "Analyzed Vision FPS",
                        value = "${"%.1f".format(cam.inferenceFps)} fps",
                        accent = KoPilotClusterTokens.SecondaryAccent,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TelemetryKpiTile(
                        label = "Frames Analyzed / Paced",
                        value = "${cam.analyzedFrames} / ${cam.droppedFrames} (${"%.1f".format(cam.droppedFramePercent)}%)",
                        accent = KoPilotClusterTokens.PrimaryText,
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryKpiTile(
                        label = "Resolution & Queue",
                        value = "${cam.actualResolution} | Q=${cam.processingQueueDepth}",
                        accent = KoPilotClusterTokens.PrimaryText,
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TelemetryKpiTile(
                        label = "Average Latency",
                        value = "${"%.1f".format(perf.avgTotalLatencyMs)} ms",
                        accent = KoPilotClusterTokens.PrimaryAccent,
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryKpiTile(
                        label = "P50 Median",
                        value = "${"%.0f".format(perf.p50TotalLatencyMs)} ms",
                        accent = KoPilotClusterTokens.PrimaryText,
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryKpiTile(
                        label = "P95 Tail",
                        value = "${"%.0f".format(perf.p95TotalLatencyMs)} ms",
                        accent = KoPilotClusterTokens.ModerateRoad,
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryKpiTile(
                        label = "P99 Max",
                        value = "${"%.0f".format(perf.p99TotalLatencyMs)} ms",
                        accent = KoPilotClusterTokens.RoughRoad,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 3. Hardware Acceleration Backend Selector & On-Device Benchmark Runner
            PerfSectionCard {
                Text(
                    text = "LITERT HARDWARE ACCELERATION & BENCHMARK",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Active Backend: ${vis.activeAccelerator} (Model: ${vis.modelVersion})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Select an execution delegate or run the on-device benchmark to verify numerical tensor consistency and pick the fastest backend for this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiteRtAcceleratorBackend.entries.forEach { backend ->
                        val selected = vis.selectedBackend == backend
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectAcceleratorBackend(backend) },
                            label = { Text(backend.displayName, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.22f),
                                selectedLabelColor = KoPilotClusterTokens.PrimaryAccent,
                            ),
                        )
                    }
                }

                Button(
                    onClick = {
                        benchmarkRunning = true
                        viewModel.runAcceleratorBenchmark()
                        benchmarkRunning = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KoPilotClusterTokens.PrimaryAccent,
                        contentColor = KoPilotClusterTokens.Background,
                    ),
                ) {
                    Text(
                        text = if (benchmarkRunning) "Running On-Device Benchmark..." else "Run On-Device Accelerator Benchmark",
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (vis.benchmarkResults.isNotEmpty()) {
                    HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)
                    Text(
                        text = "Measured On-Device Benchmark Results:",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    vis.benchmarkResults.forEach { res ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = KoPilotClusterTokens.ElevatedSurface,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (res.selectedAsBest) KoPilotClusterTokens.PrimaryAccent else Color.Transparent,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = res.backend.displayName,
                                        color = KoPilotClusterTokens.PrimaryText,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        text = when {
                                            res.selectedAsBest -> "SELECTED BEST"
                                            !res.available -> "UNAVAILABLE"
                                            !res.numericallyConsistent -> "REJECTED"
                                            else -> "VERIFIED"
                                        },
                                        color = when {
                                            res.selectedAsBest -> KoPilotClusterTokens.PrimaryAccent
                                            !res.available || !res.numericallyConsistent -> KoPilotClusterTokens.VeryRough
                                            else -> KoPilotClusterTokens.SmoothRoad
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                }
                                if (res.available) {
                                    Text(
                                        text = "Avg: ${"%.1f".format(res.avgLatencyMs)} ms | P50: ${"%.0f".format(res.p50LatencyMs)} ms | P95: ${"%.0f".format(res.p95LatencyMs)} ms | Max Diff: ${res.maxOutputDeviation}",
                                        color = KoPilotClusterTokens.SecondaryText,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Text(
                                    text = res.statusNote,
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }

            // 4. AI Module Activity, Priority Tiers & Processing Frequency
            PerfSectionCard {
                Text(
                    text = "AI MODULE SCHEDULING & PROCESSING FREQUENCY",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )
                if (perf.moduleTelemetry.isEmpty()) {
                    Text(
                        text = "Start a driving session with the camera enabled to record live per-module processing frequency and latency percentiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                } else {
                    perf.moduleTelemetry.forEach { mod ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = KoPilotClusterTokens.ElevatedSurface,
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "[Tier ${mod.priorityTier}] ${mod.displayName}",
                                        color = KoPilotClusterTokens.PrimaryText,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        text = if (mod.active) "${"%.1f".format(mod.frequencyHz)} Hz" else "PAUSED",
                                        color = if (mod.active) KoPilotClusterTokens.PrimaryAccent else KoPilotClusterTokens.ModerateRoad,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                }
                                Text(
                                    text = "Policy: ${mod.scheduleDescription}",
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                )
                                Text(
                                    text = "Avg: ${"%.1f".format(mod.avgLatencyMs)} ms | P50: ${"%.0f".format(mod.p50LatencyMs)} ms | P95: ${"%.0f".format(mod.p95LatencyMs)} ms | P99: ${"%.0f".format(mod.p99LatencyMs)} ms | Runs: ${mod.invocations}",
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            // 5. Active Thermal & Memory Optimizations Explanation
            PerfSectionCard {
                Text(
                    text = "ACTIVE PERFORMANCE & THERMAL OPTIMIZATIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )
                perf.activeOptimizations.forEach { item ->
                    Text(
                        text = "- $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.PrimaryText,
                    )
                }
            }

            // 6. Historical Performance Graph (Inference Latency & Battery Temperature Trend)
            PerfSectionCard {
                Text(
                    text = "HISTORICAL PERFORMANCE GRAPH (LAST 120 SECONDS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )
                val history = cam.history
                if (history.size < 2) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = KoPilotClusterTokens.ElevatedSurface,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Collecting performance samples (requires active camera analysis)...",
                                color = KoPilotClusterTokens.SecondaryText,
                                fontSize = 12.sp,
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Teal line: Frame inference latency (0..400 ms) | Amber line: Battery temperature (25..55 C)",
                        color = KoPilotClusterTokens.SecondaryText,
                        fontSize = 11.sp,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = KoPilotClusterTokens.ElevatedSurface,
                    ) {
                        val latencyColor = KoPilotClusterTokens.PrimaryAccent
                        val tempColor = KoPilotClusterTokens.ModerateRoad
                        val gridColor = KoPilotClusterTokens.SecondaryText.copy(alpha = 0.2f)
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                        ) {
                            val w = size.width
                            val h = size.height
                            drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
                            drawLine(gridColor, Offset(0f, h * 0.50f), Offset(w, h * 0.50f), strokeWidth = 1f)
                            drawLine(gridColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

                            val stepX = w / (history.size - 1).coerceAtLeast(1)
                            for (i in 1 until history.size) {
                                val prev = history[i - 1]
                                val curr = history[i]
                                val x1 = (i - 1) * stepX
                                val x2 = i * stepX

                                val yLat1 = h * (1f - (prev.latencyMs.toFloat() / 400f).coerceIn(0f, 1f))
                                val yLat2 = h * (1f - (curr.latencyMs.toFloat() / 400f).coerceIn(0f, 1f))
                                drawLine(
                                    color = latencyColor,
                                    start = Offset(x1, yLat1),
                                    end = Offset(x2, yLat2),
                                    strokeWidth = 2.2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )

                                val t1 = prev.batteryTempC
                                val t2 = curr.batteryTempC
                                if (t1 != null && t2 != null) {
                                    val yTemp1 = h * (1f - ((t1 - 25f) / 30f).coerceIn(0f, 1f))
                                    val yTemp2 = h * (1f - ((t2 - 25f) / 30f).coerceIn(0f, 1f))
                                    drawLine(
                                        color = tempColor,
                                        start = Offset(x1, yTemp1),
                                        end = Offset(x2, yTemp2),
                                        strokeWidth = 2.dp.toPx(),
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. Export Diagnostics Report Button
            PerfSectionCard {
                Text(
                    text = "DIAGNOSTICS EXPORT",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Export a timestamped performance, latency percentile, accelerator benchmark, and thermal telemetry report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
                OutlinedButton(
                    onClick = {
                        val reportText = buildDiagnosticsReportText(state)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "KoPilot Performance & Thermal Diagnostics Report")
                            putExtra(Intent.EXTRA_TEXT, reportText)
                        }
                        context.startActivity(Intent.createChooser(share, "Export Performance & Thermal Report"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Export Performance & Thermal Diagnostics (.txt)",
                        color = KoPilotClusterTokens.PrimaryAccent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PerfSectionCard(
    content: @Composable () -> Unit,
) {
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
private fun TelemetryKpiTile(
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
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = KoPilotClusterTokens.SecondaryText,
                fontSize = 10.sp,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
}

private fun buildDiagnosticsReportText(state: DashboardUiState): String {
    val cam = state.cameraStatus
    val vis = state.visionState
    val perf = vis.performanceMetrics
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return buildString {
        appendLine("KOPILOT PERFORMANCE & THERMAL DIAGNOSTICS REPORT")
        appendLine("Generated: ${fmt.format(Date())}")
        appendLine("====================================================")
        appendLine("THERMAL & HARDWARE TELEMETRY")
        appendLine("Adaptive Thermal State: ${cam.thermalState.name} (${cam.thermalState.label})")
        appendLine("Android OS Thermal Status: ${cam.osThermalStatusLabel}")
        appendLine("Thermal Headroom (10s): ${cam.thermalHeadroom?.let { "%.2f".format(it) } ?: "Unsupported by device"}")
        appendLine("Battery Temperature: ${cam.batteryTemperatureC?.let { "${"%.1f".format(it)} C" } ?: "Unavailable"}")
        appendLine("Battery Level: ${if (cam.batteryPercent >= 0) "${cam.batteryPercent}%" else "Unknown"}")
        appendLine("Battery Current: ${cam.batteryCurrentMilliamps?.let { "$it mA" } ?: "Unsupported by device"}")
        appendLine("JVM Heap Used: ${cam.jvmHeapUsedMb} MB / ${cam.jvmHeapMaxMb} MB")
        appendLine("Native Tensor Heap: ${cam.nativeHeapAllocatedMb} MB")
        appendLine()
        appendLine("CAMERA & INFERENCE PIPELINE")
        appendLine("Model: ${vis.modelVersion}")
        appendLine("Active Accelerator: ${vis.activeAccelerator}")
        appendLine("Camera Resolution: ${cam.actualResolution}")
        appendLine("Camera Preview FPS: ${"%.1f".format(cam.cameraFps)}")
        appendLine("Analyzed Inference FPS: ${"%.1f".format(cam.inferenceFps)}")
        appendLine("Frames Analyzed: ${cam.analyzedFrames}")
        appendLine("Frames Paced/Dropped: ${cam.droppedFrames} (${"%.1f".format(cam.droppedFramePercent)}%)")
        appendLine("Total Latency Avg / P50 / P95 / P99: ${"%.1f".format(perf.avgTotalLatencyMs)} / ${"%.0f".format(perf.p50TotalLatencyMs)} / ${"%.0f".format(perf.p95TotalLatencyMs)} / ${"%.0f".format(perf.p99TotalLatencyMs)} ms")
        appendLine()
        appendLine("PER-MODULE AI LATENCY & FREQUENCY")
        perf.moduleTelemetry.forEach { mod ->
            appendLine("- [Tier ${mod.priorityTier}] ${mod.displayName}: ${if (mod.active) "${"%.1f".format(mod.frequencyHz)} Hz" else "PAUSED"} | Avg ${"%.1f".format(mod.avgLatencyMs)} ms | P50 ${"%.0f".format(mod.p50LatencyMs)} ms | P95 ${"%.0f".format(mod.p95LatencyMs)} ms | Runs ${mod.invocations}")
        }
        if (vis.benchmarkResults.isNotEmpty()) {
            appendLine()
            appendLine("ON-DEVICE ACCELERATOR BENCHMARK RESULTS")
            vis.benchmarkResults.forEach { res ->
                appendLine("- ${res.backend.displayName}: Available=${res.available}, Avg=${"%.1f".format(res.avgLatencyMs)} ms, P50=${"%.0f".format(res.p50LatencyMs)} ms, P95=${"%.0f".format(res.p95LatencyMs)} ms, MaxDiff=${res.maxOutputDeviation}, Selected=${res.selectedAsBest} (${res.statusNote})")
            }
        }
    }
}
