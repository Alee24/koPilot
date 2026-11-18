package ke.co.kopilot.feature.sensors

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.location.LocationTracker
import ke.co.kopilot.feature.location.LocationTrackingState
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class DrivingIntelligenceViewModel @Inject constructor(
    intelligence: RealTimeDrivingIntelligence,
    sensorRepo: SensorManagerRepository,
    locationTracker: LocationTracker,
) : ViewModel() {
    val intelligenceState: StateFlow<RealTimeDrivingIntelligenceState> = intelligence.state
    val hubState: StateFlow<SensorHubState> = sensorRepo.state
    val locationState: StateFlow<LocationTrackingState> = locationTracker.state
}

@Composable
fun DrivingIntelligenceScreen(
    onBack: () -> Unit,
    viewModel: DrivingIntelligenceViewModel = hiltViewModel(),
) {
    val intel by viewModel.intelligenceState.collectAsStateWithLifecycle()
    val hub by viewModel.hubState.collectAsStateWithLifecycle()
    val loc by viewModel.locationState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KoPilotClusterTokens.CardSurface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = KoPilotClusterTokens.PrimaryText,
                )
            }
            Text(
                text = "Real-Time Intelligence",
                style = MaterialTheme.typography.titleLarge,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                color = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = intel.drivingState.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.PrimaryAccent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Dual Score Cards: Safety & Fuel Efficiency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IntelligenceScoreCard(
                    title = "SAFETY SCORE",
                    score = intel.safetyScore,
                    subtitle = "Brakes: ${intel.harshBrakeCount} • Corners: ${intel.sharpCornerCount}",
                    accentColor = scoreColor(intel.safetyScore),
                    modifier = Modifier.weight(1f),
                )
                IntelligenceScoreCard(
                    title = "FUEL EFFICIENCY",
                    score = intel.fuelEfficiencyScore,
                    subtitle = "Idle: ${intel.idlingRatioPct}% • Var: ${"%.0f".format(intel.speedVariabilityKmh)}",
                    accentColor = scoreColor(intel.fuelEfficiencyScore),
                    modifier = Modifier.weight(1f),
                )
            }

            // Sensor Pipeline Health Strip
            IntelCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Sensors: ${if (hub.isMonitoring) "Active" else "Standby"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.PrimaryText,
                    )
                    Text(
                        text = "GPS: ${loc.currentFix?.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "--"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                    Text(
                        text = "Road RMS: ${"%.2f".format(hub.motionAnalysis.verticalVibrationRmsMps2)} m/s²",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                }
            }

            // Score Trend Chart
            IntelCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Score Trend (Active Trip)",
                        style = MaterialTheme.typography.titleSmall,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegendDot(label = "Safety", color = KoPilotClusterTokens.PrimaryAccent)
                        LegendDot(label = "Fuel", color = KoPilotClusterTokens.SecondaryAccent)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                ScoreTrendChart(
                    points = intel.scoreTrend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                )
            }

            // Local Coaching Advice (Offline)
            IntelCard {
                Text(
                    text = "Local Coaching Advice (Offline)",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                intel.coachingAdvice.forEach { tip ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(
                            color = if (tip.category == "Safety") {
                                KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.18f)
                            } else {
                                KoPilotClusterTokens.SecondaryAccent.copy(alpha = 0.18f)
                            },
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = tip.category.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tip.category == "Safety") {
                                    KoPilotClusterTokens.PrimaryAccent
                                } else {
                                    KoPilotClusterTokens.SecondaryAccent
                                },
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tip.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = KoPilotClusterTokens.PrimaryText,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Transparent Scoring Breakdown
            IntelCard {
                Text(
                    text = "Transparent Scoring Breakdown",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                intel.scoringBreakdown.forEachIndexed { index, factor ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = factor.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = KoPilotClusterTokens.PrimaryText,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = factor.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.SecondaryText,
                            )
                        }
                        Text(
                            text = if (factor.pointsDelta == 0) "0 pts" else "${factor.pointsDelta} pts",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (factor.pointsDelta < 0) {
                                KoPilotClusterTokens.ModerateRoad
                            } else {
                                KoPilotClusterTokens.SmoothRoad
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (index < intel.scoringBreakdown.lastIndex) {
                        HorizontalDivider(color = KoPilotClusterTokens.Border.copy(alpha = 0.5f))
                    }
                }
            }

            // Confirmed Trip Events Timeline
            IntelCard {
                Text(
                    text = "Confirmed Trip Events (${intel.recentEvents.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (intel.recentEvents.isEmpty()) {
                    Text(
                        text = "No harsh or notable driving events recorded in this session yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                } else {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    intel.recentEvents.take(25).forEachIndexed { index, evt ->
                        val sevColor = when (evt.severity) {
                            EventSeverity.HIGH -> KoPilotClusterTokens.VeryRough
                            EventSeverity.MODERATE -> KoPilotClusterTokens.RoughRoad
                            EventSeverity.INFO -> KoPilotClusterTokens.SecondaryAccent
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${fmt.format(Date(evt.timestampMillis))} • ${evt.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = sevColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = evt.detail + (evt.speedKmh?.let { " • ${it.roundToInt()} km/h" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.SecondaryText,
                                )
                            }
                            Text(
                                text = "S:${evt.safetyImpact} F:${evt.fuelImpact}",
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.PrimaryText,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (index < minOf(intel.recentEvents.size, 25) - 1) {
                            HorizontalDivider(color = KoPilotClusterTokens.Border.copy(alpha = 0.4f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun IntelCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = KoPilotClusterTokens.CardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun IntelligenceScoreCard(
    title: String,
    score: Int,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val animatedScore by animateIntAsState(
        targetValue = score.coerceIn(0, 100),
        animationSpec = tween(durationMillis = 350),
        label = "scoreInt",
    )
    val animatedSweep by animateFloatAsState(
        targetValue = (score.coerceIn(0, 100) / 100f) * 260f,
        animationSpec = tween(durationMillis = 350),
        label = "scoreSweep",
    )

    Surface(
        modifier = modifier,
        color = KoPilotClusterTokens.CardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = KoPilotClusterTokens.SecondaryText,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier.size(92.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 8.dp.toPx()
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(stroke / 2f, stroke / 2f)
                    drawArc(
                        color = KoPilotClusterTokens.ElevatedSurface,
                        startAngle = 140f,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = accentColor,
                        startAngle = 140f,
                        sweepAngle = animatedSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Text(
                    text = "$animatedScore",
                    style = MaterialTheme.typography.headlineMedium,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = KoPilotClusterTokens.SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScoreTrendChart(
    points: List<IntelligenceScorePoint>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        // Horizontal grid lines at 25, 50, 75, 100
        listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
            val y = size.height * frac
            drawLine(
                color = KoPilotClusterTokens.Border.copy(alpha = 0.5f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        if (points.size < 2) return@Canvas
        val safetyPath = Path()
        val fuelPath = Path()
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)

        points.forEachIndexed { idx, pt ->
            val x = idx * stepX
            val ySafety = size.height * (1f - (pt.safetyScore.coerceIn(0, 100) / 100f))
            val yFuel = size.height * (1f - (pt.fuelScore.coerceIn(0, 100) / 100f))
            if (idx == 0) {
                safetyPath.moveTo(x, ySafety)
                fuelPath.moveTo(x, yFuel)
            } else {
                safetyPath.lineTo(x, ySafety)
                fuelPath.lineTo(x, yFuel)
            }
        }

        drawPath(
            path = fuelPath,
            color = KoPilotClusterTokens.SecondaryAccent,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
        drawPath(
            path = safetyPath,
            color = KoPilotClusterTokens.PrimaryAccent,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = KoPilotClusterTokens.SecondaryText,
        )
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> KoPilotClusterTokens.SmoothRoad
    score >= 55 -> KoPilotClusterTokens.ModerateRoad
    score >= 30 -> KoPilotClusterTokens.RoughRoad
    else -> KoPilotClusterTokens.VeryRough
}
