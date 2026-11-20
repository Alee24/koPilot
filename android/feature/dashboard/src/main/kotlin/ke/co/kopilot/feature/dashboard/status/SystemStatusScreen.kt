package ke.co.kopilot.feature.dashboard.status

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.dashboard.DashboardUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SystemStatusScreen(
    state: DashboardUiState,
    onRunCheckAllSystems: () -> Unit,
    onStopVoiceBriefing: () -> Unit,
    onOpenDetailedDiagnostics: () -> Unit,
    onStartTrip: () -> Unit,
    onBack: () -> Unit,
) {
    val report = state.lastSystemStatusReport
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(Unit) {
        if (report == null && !state.isSystemCheckRunning) {
            onRunCheckAllSystems()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
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
                    text = "System Status",
                    color = KoPilotClusterTokens.PrimaryText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = report?.let { "Last check: ${timeFmt.format(Date(it.checkedAtMillis))}" } ?: "Ready to verify all subsystems",
                    color = KoPilotClusterTokens.SecondaryText,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (state.isSystemCheckRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = KoPilotClusterTokens.PrimaryAccent,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report != null) {
                item {
                    val badgeColor = when (report.readiness) {
                        SystemReadinessLevel.ALL_OPERATIONAL -> Color(0xFF10B981)
                        SystemReadinessLevel.READY_WITH_LIMITATIONS -> Color(0xFFF59E0B)
                        SystemReadinessLevel.ATTENTION_REQUIRED -> Color(0xFFEF4444)
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = badgeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, badgeColor),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = report.readiness.label.uppercase(),
                                color = badgeColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                            )
                            if (report.diagnosticWarnings.isNotEmpty()) {
                                Text(
                                    text = "Warnings: ${report.diagnosticWarnings.joinToString(" • ")}",
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontSize = 12.sp,
                                )
                            } else if (report.limitations.isNotEmpty()) {
                                Text(
                                    text = "Limitations: ${report.limitations.joinToString(" • ")}",
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontSize = 12.sp,
                                )
                            } else {
                                Text(
                                    text = "All required subsystems verified from live sensors.",
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                item {
                    StatusSectionCard("CORE SENSORS & LOCATION") {
                        StatusRow("Camera", report.cameraStatus)
                        StatusRow("GPS", report.gpsStatus)
                        StatusRow("Compass Heading", report.headingDisplay)
                        StatusRow("Motion Sensors", report.motionSensorsStatus)
                    }
                }

                item {
                    StatusSectionCard("VEHICLE OBD-II TELEMETRY") {
                        StatusRow("OBD-II Adapter", report.obdStatus)
                        StatusRow("Vehicle Protocol", report.obdProtocol)
                        StatusRow("Engine RPM", report.rpmText)
                        StatusRow("Fuel Level", report.fuelText)
                        StatusRow("Mass Airflow (MAF)", report.mafText)
                        StatusRow("Vehicle Speed", report.vehicleSpeedText)
                        StatusRow("Coolant Temperature", report.coolantText)
                        StatusRow("Control Voltage", report.voltageText)
                        StatusRow(
                            "Diagnostic Warnings",
                            if (report.diagnosticWarnings.isEmpty()) "None detected" else report.diagnosticWarnings.joinToString("; "),
                        )
                    }
                }

                item {
                    StatusSectionCard("SMARTWATCH & DRIVER WELLNESS") {
                        StatusRow("Smartwatch", report.smartwatchStatus)
                        StatusRow("Latest Heart Rate", report.heartRateText)
                        StatusRow("Sleep History", report.sleepSummaryText)
                    }
                }

                item {
                    StatusSectionCard("WEATHER CONDITIONS") {
                        StatusRow("Reported Weather", report.weatherSummaryText)
                        StatusRow("Observation Age", report.weatherObservationLabel)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Fixed bottom action bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = KoPilotClusterTokens.CardSurface,
            border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onRunCheckAllSystems,
                        enabled = !state.isSystemCheckRunning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KoPilotClusterTokens.PrimaryAccent,
                            contentColor = KoPilotClusterTokens.Background,
                        ),
                    ) {
                        Icon(Icons.Default.FactCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.isSystemCheckRunning) "Checking..." else "Check All Systems", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onStopVoiceBriefing,
                        modifier = Modifier.weight(0.85f),
                    ) {
                        Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop Briefing", fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onOpenDetailedDiagnostics,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Detailed Diagnostics", fontSize = 12.sp)
                    }
                    if (!state.isDrivingSessionActive) {
                        Button(
                            onClick = onStartTrip,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color(0xFF041311),
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start Trip", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSectionCard(title: String, content: @Composable () -> Unit) {
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
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Text(value, color = KoPilotClusterTokens.PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
