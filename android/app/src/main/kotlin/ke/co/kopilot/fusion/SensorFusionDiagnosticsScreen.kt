package ke.co.kopilot.fusion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CompassCalibration
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ke.co.kopilot.feature.sensors.MountCalibrationStatus
import ke.co.kopilot.feature.sensors.fusion.FusionTimelineSample
import ke.co.kopilot.feature.sensors.fusion.SensorFusionSnapshot
import ke.co.kopilot.feature.sensors.fusion.TerrainCategory
import java.util.Locale

private val FusionEmerald = Color(0xFF22C55E)
private val FusionBlue = Color(0xFF38BDF8)
private val FusionAmber = Color(0xFFF59E0B)
private val FusionRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorFusionDiagnosticsScreen(
    snapshot: SensorFusionSnapshot,
    onCalibrateMount: () -> Unit,
    onBack: () -> Unit,
) {
    val speedKmh = snapshot.vehicleSpeedKmh ?: 0f
    val isStationary = speedKmh < 3f
    val terrainColor = when (snapshot.terrainCategory) {
        TerrainCategory.FLAT -> FusionEmerald
        TerrainCategory.MILD_UPHILL, TerrainCategory.MILD_DOWNHILL -> FusionBlue
        TerrainCategory.STEEP_UPHILL, TerrainCategory.STEEP_DOWNHILL -> FusionAmber
        TerrainCategory.UNCERTAIN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Sensor Fusion & Terrain Diagnostics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${snapshot.terrainCategory.label} • Confidence: ${snapshot.confidence}",
                            style = MaterialTheme.typography.labelSmall,
                            color = terrainColor,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                FusionSectionCard(
                    title = "Terrain & Independent Evidence",
                    icon = Icons.Rounded.Terrain,
                    badge = snapshot.terrainCategory.label,
                    badgeColor = terrainColor,
                ) {
                    FusionLine("Estimated Gradient", snapshot.gradientPct?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "Uncertain")
                    FusionLine("Supporting Sources", snapshot.supportingSources.joinToString(", ") { it.label }.ifBlank { "None (<2 sources)" })
                    FusionLine("Conflicting Sources", snapshot.conflictingSources.joinToString(", ") { it.label }.ifBlank { "None" })
                    FusionLine("Elevation Gain / Loss", "${snapshot.elevationGainMeters.toInt()} m / ${snapshot.elevationLossMeters.toInt()} m")
                    FusionLine("Uphill / Downhill Dist", "${String.format(Locale.US, "%.2f", snapshot.uphillDistanceKm)} km / ${String.format(Locale.US, "%.2f", snapshot.downhillDistanceKm)} km")
                    FusionLine("Confirmed / Suppressed", "${snapshot.confirmedAdvisoriesCount} confirmed • ${snapshot.suppressedAlertsCount} suppressed")
                }
            }

            item {
                FusionSectionCard(
                    title = "Live Sensor & ECU Inputs",
                    icon = Icons.Rounded.Sensors,
                    badge = if (snapshot.obdConnected) "OBD Active" else "OBD Offline",
                    badgeColor = if (snapshot.obdConnected) FusionEmerald else FusionAmber,
                ) {
                    FusionLine("GPS Altitude / Vert Acc", "${snapshot.gpsAltitudeMeters?.let { "${it.toInt()} m" } ?: "Unavailable"} / ${snapshot.gpsVerticalAccuracyMeters?.let { "±${it.toInt()} m" } ?: "--"}")
                    FusionLine("Barometer / Trend", "${snapshot.baroPressureHpa?.let { String.format(Locale.US, "%.1f hPa", it) } ?: "Unavailable"} / ${snapshot.baroElevationTrendMeters?.let { String.format(Locale.US, "%+.1f m", it) } ?: "--"}")
                    FusionLine("Calibrated Vehicle Pitch", snapshot.calibratedPitchDeg?.let { String.format(Locale.US, "%+.1f°", it) } ?: "Unavailable")
                    FusionLine("Accelerometer / Gyro", "${if (snapshot.accelAvailable) "Active" else "Unavailable"} / ${if (snapshot.gyroAvailable) "Active" else "Unavailable"}")
                    FusionLine("OBD Speed / RPM / Load", "${snapshot.vehicleSpeedKmh?.let { "${it.toInt()} km/h" } ?: "--"} • ${snapshot.obdRpm?.let { "$it rpm" } ?: "--"} • ${snapshot.obdLoadPct?.let { "${it.toInt()}%" } ?: "--"}")
                }
            }

            item {
                val calColor = when (snapshot.mountCalibrationStatus) {
                    MountCalibrationStatus.CALIBRATED -> FusionEmerald
                    MountCalibrationStatus.MOUNT_MOVEMENT_SUSPECTED -> FusionRed
                    else -> FusionAmber
                }
                FusionSectionCard(
                    title = "Phone Mount Calibration",
                    icon = Icons.Rounded.CompassCalibration,
                    badge = snapshot.mountCalibrationStatus.name,
                    badgeColor = calColor,
                ) {
                    Text(
                        text = if (isStationary) {
                            "Vehicle is stationary. Tap below to calibrate the phone mount orientation baseline."
                        } else {
                            "Stop the vehicle safely (< 3 km/h) before recalibrating the phone mount."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onCalibrateMount,
                        enabled = isStationary,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(if (isStationary) "Calibrate Phone Mount Now" else "Stop Vehicle to Calibrate")
                    }
                }
            }

            if (snapshot.timelineSamples.size >= 2) {
                item {
                    FusionSectionCard(
                        title = "Gradient, Speed & Engine Load Timeline",
                        icon = Icons.Rounded.Timeline,
                        badge = "${snapshot.timelineSamples.size} pts",
                        badgeColor = FusionBlue,
                    ) {
                        FusionTimelineChart(samples = snapshot.timelineSamples)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Cyan: Speed • Amber: Engine Load • Green: Road Gradient",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Alert Verification & Suppression Audit Log",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (snapshot.recentFindings.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "No fusion findings recorded yet. Drive with GPS/sensors active to inspect verification decisions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(snapshot.recentFindings) { finding ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(
                            1.dp,
                            if (finding.suppressed) FusionAmber.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (finding.suppressed) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = if (finding.suppressed) FusionAmber else FusionEmerald,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = finding.eventCategory,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    text = if (finding.suppressed) "SUPPRESSED" else finding.severity.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (finding.suppressed) FusionAmber else FusionBlue,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(finding.explanation, style = MaterialTheme.typography.bodySmall)
                            finding.suppressionReason?.let {
                                Text("Reason: $it", style = MaterialTheme.typography.labelSmall, color = FusionAmber)
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            Text(
                                text = "Sources: ${finding.supportingSources.joinToString { it.label }.ifBlank { "None" }} • ${finding.sensorFreshnessSummary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FusionTimelineChart(samples: List<FusionTimelineSample>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        fun drawNormalized(values: List<Float?>, minVal: Float, maxVal: Float, color: Color) {
            val valid = values.mapIndexedNotNull { idx, v -> v?.let { idx to it } }
            if (valid.size < 2) return
            val path = Path()
            val stepX = size.width / (values.size - 1).coerceAtLeast(1)
            valid.forEachIndexed { order, (idx, v) ->
                val x = idx * stepX
                val norm = ((v - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
                val y = size.height * (1f - norm)
                if (order == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        drawNormalized(samples.map { it.speedKmh }, 0f, 120f, FusionBlue)
        drawNormalized(samples.map { it.loadPct }, 0f, 100f, FusionAmber)
        drawNormalized(samples.map { it.gradientPct }, -12f, 12f, FusionEmerald)
    }
}

@Composable
private fun FusionSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badge: String,
    badgeColor: Color,
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = badgeColor.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = badge,
                        color = badgeColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun FusionLine(label: String, value: String) {
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
