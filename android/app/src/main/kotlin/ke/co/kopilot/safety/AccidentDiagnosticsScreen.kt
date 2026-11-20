package ke.co.kopilot.safety

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.dashboard.DashboardUiState
import ke.co.kopilot.feature.sensors.AccidentState
import ke.co.kopilot.feature.sensors.AccidentThresholdConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccidentDiagnosticsScreen(
    state: DashboardUiState,
    onUpdateThresholdConfig: (AccidentThresholdConfig) -> Unit,
    onRunSimulation: (String) -> Unit,
    onRecalibrateMount: () -> Unit,
    onBack: () -> Unit,
) {
    val assessment = state.latestAccidentAssessment
    val cfg = state.accidentThresholdConfig
    var editorUnlocked by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = KoPilotClusterTokens.PrimaryText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Accident Detection Diagnostics",
                    color = KoPilotClusterTokens.PrimaryText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Multi-Sensor Confirmation & Safe Replay (${cfg.configVersion})",
                    color = KoPilotClusterTokens.SecondaryText,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Live State Machine & Sensor Telemetry
            item {
                DiagCard(title = "LIVE STATE MACHINE & SENSOR TELEMETRY") {
                    val curState = assessment?.state ?: AccidentState.NORMAL
                    val stateColor = when (curState) {
                        AccidentState.POSSIBLE_ACCIDENT, AccidentState.EMERGENCY_NOTIFICATION -> Color(0xFFEF4444)
                        AccidentState.SEVERE_EVENT_FALLBACK, AccidentState.SUSPICIOUS_EVENT, AccidentState.VERIFYING -> Color(0xFFF59E0B)
                        AccidentState.CANCELED -> Color(0xFF38BDF8)
                        else -> Color(0xFF10B981)
                    }
                    Surface(
                        color = stateColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, stateColor),
                    ) {
                        Text(
                            text = "STATE: ${curState.name} (${curState.label})",
                            color = stateColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    MetricRow("Linear Acceleration", "${"%.2f".format(assessment?.impactIntensityMps2 ?: 0f)} m/s² (${"%.2f".format(assessment?.peakImpactG ?: 0f)} g)")
                    MetricRow("Vehicle Pitch / Roll Delta", "${"%.1f".format(assessment?.orientationChangeDeg ?: 0f)}°")
                    MetricRow("Gyroscope Angular Vel", "${"%.1f".format(kotlin.math.abs(Math.toDegrees(state.motionAnalysis.yawRateRadPerSec.toDouble())))} °/s")
                    MetricRow("GPS Speed", state.locationState.currentFix?.speedKmh?.let { "${"%.1f".format(it)} km/h (±${state.locationState.currentFix?.horizontalAccuracyMeters?.toInt() ?: 0}m)" } ?: "Unavailable")
                    MetricRow("OBD Speed / RPM", if (state.diagnosticsState.obdConnected) "${state.diagnosticsState.obdSpeedKmh ?: "--"} km/h • ${state.diagnosticsState.engineRpm ?: "--"} RPM" else "OBD-II disconnected")
                    MetricRow("Smartwatch Heart Rate", state.heartRateState.currentBpm?.let { "$it BPM (${state.heartRateState.dataSource})" } ?: "Unavailable")
                    MetricRow("Sensor Quality", assessment?.sensorQuality?.qualityLabel ?: "Initializing")
                    MetricRow("Mount Calibration", state.mountCalibrationStatus.label)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onRecalibrateMount, modifier = Modifier.fillMaxWidth()) {
                        Text("Recalibrate Phone Mount")
                    }
                }
            }

            // 2. Active Independent Evidence Groups (A, B, C, D)
            item {
                DiagCard(title = "INDEPENDENT EVIDENCE GROUPS (A / B / C / D)") {
                    val groups = assessment?.evidenceGroups.orEmpty()
                    if (groups.isEmpty()) {
                        Text("Waiting for live sensor frames...", color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
                    } else {
                        groups.forEach { g ->
                            val badgeColor = when {
                                g.corroborated -> Color(0xFFEF4444)
                                g.available -> Color(0xFF10B981)
                                else -> Color(0xFF64748B)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Group ${g.group.code} • ${g.group.label}", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        text = when {
                                            g.corroborated -> "ACTIVE"
                                            g.available -> "READY"
                                            else -> "UNAVAILABLE"
                                        },
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                }
                                Text(
                                    text = g.details.joinToString(" • "),
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Confirmation Reasons: ${assessment?.triggeringMeasurements?.joinToString(" • ") ?: "Normal"}",
                        color = KoPilotClusterTokens.PrimaryText,
                        fontSize = 12.sp,
                    )
                }
            }

            // 3. Safe Simulation & Replay Lab (Never Sends Real SMS)
            item {
                DiagCard(title = "SAFE SIMULATION / REPLAY LAB (ZERO REAL SMS)") {
                    Text(
                        text = "Test false-positive rejection and impact confirmation offline. Real emergency SMS is strictly blocked in simulation mode.",
                        color = KoPilotClusterTokens.SecondaryText,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SimButton("Pothole") { onRunSimulation("POTHOLE") }
                        SimButton("Phone Rotation") { onRunSimulation("PHONE_ROTATION") }
                        SimButton("Harsh Braking") { onRunSimulation("HARSH_BRAKING") }
                        SimButton("Erratic Swerving") { onRunSimulation("SWERVING") }
                        Button(
                            onClick = { onRunSimulation("IMPACT_REPLAY") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
                        ) {
                            Text("Replay Impact", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 4. Protected Threshold Configuration
            item {
                DiagCard(title = "PROTECTED THRESHOLD CONFIGURATION") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unlock Engineering Thresholds", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Config ${cfg.configVersion} • Provisional bounded values", color = KoPilotClusterTokens.SecondaryText, fontSize = 11.sp)
                        }
                        Switch(checked = editorUnlocked, onCheckedChange = { editorUnlocked = it })
                    }
                    if (editorUnlocked) {
                        Spacer(Modifier.height(6.dp))
                        ThresholdSlider(
                            label = "Strong Impact Peak",
                            valueText = "${"%.1f".format(cfg.strongImpactG)} g",
                            value = cfg.strongImpactG,
                            range = 1.8f..4.0f,
                            onChange = { onUpdateThresholdConfig(cfg.copy(strongImpactG = it, configVersion = "v2.0-custom")) },
                        )
                        ThresholdSlider(
                            label = "Severe Impact Peak",
                            valueText = "${"%.1f".format(cfg.severeImpactG)} g",
                            value = cfg.severeImpactG,
                            range = 3.0f..6.5f,
                            onChange = { onUpdateThresholdConfig(cfg.copy(severeImpactG = it, configVersion = "v2.0-custom")) },
                        )
                        ThresholdSlider(
                            label = "Abrupt Speed Loss",
                            valueText = "${cfg.abruptSpeedLossKmh.toInt()} km/h",
                            value = cfg.abruptSpeedLossKmh,
                            range = 12f..40f,
                            onChange = { onUpdateThresholdConfig(cfg.copy(abruptSpeedLossKmh = it, configVersion = "v2.0-custom")) },
                        )
                        ThresholdSlider(
                            label = "Rollover Angle",
                            valueText = "${cfg.rolloverAngleDeg.toInt()}°",
                            value = cfg.rolloverAngleDeg,
                            range = 45f..80f,
                            onChange = { onUpdateThresholdConfig(cfg.copy(rolloverAngleDeg = it, configVersion = "v2.0-custom")) },
                        )
                        OutlinedButton(
                            onClick = { onUpdateThresholdConfig(AccidentThresholdConfig()) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reset to Provisional Defaults")
                        }
                    }
                }
            }

            // 5. Alert, Canceled & False-Alert History
            item {
                Text(
                    text = "SAFETY & FALSE-ALERT HISTORY (${state.safetyEventHistory.size})",
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.safetyEventHistory.isEmpty()) {
                item {
                    DiagCard(title = "NO EVENTS RECORDED") {
                        Text(
                            text = "No safety events, canceled alerts, or simulations have been logged in this session.",
                            color = KoPilotClusterTokens.SecondaryText,
                            fontSize = 12.sp,
                        )
                    }
                }
            } else {
                items(state.safetyEventHistory) { rec ->
                    DiagCard(title = "${rec.category} • ${timeFmt.format(Date(rec.timestampMillis))}") {
                        Text(
                            text = "State: ${rec.state.label} | Peak: ${"%.2f".format(rec.peakLinearG)}g | Tilt: ${"%.0f".format(rec.pitchRollMaxDeg)}°",
                            color = KoPilotClusterTokens.PrimaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Groups: ${rec.activeEvidenceGroups.ifEmpty { listOf("None") }.joinToString(", ")}",
                            color = KoPilotClusterTokens.SecondaryText,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "Outcome: ${rec.confirmationOutcome} | SMS: ${rec.smsResult}",
                            color = if (rec.canceledByDriver) Color(0xFF38BDF8) else KoPilotClusterTokens.SecondaryText,
                            fontSize = 12.sp,
                        )
                        rec.cameraEvidenceRef?.let { ref ->
                            Text(text = "Evidence: $ref", color = KoPilotClusterTokens.SecondaryText, fontSize = 11.sp)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = KoPilotClusterTokens.CardSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = KoPilotClusterTokens.PrimaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
        Text(value, color = KoPilotClusterTokens.PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SimButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun ThresholdSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = KoPilotClusterTokens.PrimaryText, fontSize = 12.sp)
            Text(valueText, color = KoPilotClusterTokens.PrimaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
