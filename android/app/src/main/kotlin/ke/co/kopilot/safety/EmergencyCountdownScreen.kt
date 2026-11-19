package ke.co.kopilot.safety

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.sensors.AccidentAssessment
import java.util.Locale

@Composable
fun EmergencyCountdownScreen(
    assessment: AccidentAssessment,
    remainingSeconds: Int,
    totalSeconds: Int,
    latitude: Double?,
    longitude: Double?,
    contactsCount: Int,
    vibrationEnabled: Boolean,
    onCancelAlert: () -> Unit,
    onSendAlertNow: () -> Unit,
    onOpenContacts: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(remainingSeconds, vibrationEnabled) {
        if (vibrationEnabled && remainingSeconds > 0) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator?.hasVibrator() == true) {
                    vibrator.vibrate(VibrationEffect.createOneShot(280L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (_: Throwable) {
                // Ignore vibration failures on devices/OS policies that restrict haptic feedback
            }
        }
    }

    val progress = if (totalSeconds > 0) {
        (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF3B090C), Color(0xFF0A1219), Color(0xFF071019)),
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Default.DirectionsCar,
            contentDescription = null,
            tint = Color(0xFFEF4444),
            modifier = Modifier.size(54.dp),
        )

        Text(
            text = "POSSIBLE ACCIDENT\nDETECTED",
            color = Color.White,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )

        Text(
            text = "KoPilot detected unusual impact or vehicle movement.",
            color = Color(0xFFD0DCE6),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        // Prominent Circular Countdown Timer
        Box(
            modifier = Modifier.size(164.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = Color(0x33FFFFFF),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color(0xFFEF4444),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$remainingSeconds",
                    color = Color.White,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "Seconds\nremaining",
                    color = Color(0xFFD0DCE6),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp,
                )
            }
        }

        Button(
            onClick = onCancelAlert,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
        ) {
            Icon(Icons.Default.Close, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Cancel Alert", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Button(
            onClick = onSendAlertNow,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White),
        ) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Send Alert Now", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = KoPilotClusterTokens.CardSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EmergencyDetailRow(
                    icon = Icons.Default.LocationOn,
                    label = "Location",
                    value = if (latitude != null && longitude != null) {
                        "Lat: ${"%.4f".format(Locale.US, latitude)}, Lng: ${"%.4f".format(Locale.US, longitude)}"
                    } else "Acquiring GPS...",
                )
                EmergencyDetailRow(
                    icon = Icons.Default.Speed,
                    label = "Last Speed",
                    value = assessment.preEventSpeedKmh?.let { "${it.toInt()} km/h" } ?: "Unavailable",
                )
                EmergencyDetailRow(
                    icon = Icons.Default.Warning,
                    label = "Event",
                    value = "${assessment.eventClass.label} (${assessment.confidencePercent}% confidence)",
                )
                Text(
                    text = "Triggers: ${assessment.triggeringMeasurements.joinToString(" • ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            }
        }

        Surface(
            onClick = onOpenContacts,
            modifier = Modifier.fillMaxWidth(),
            color = KoPilotClusterTokens.CardSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Group, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent)
                Text(
                    text = "Emergency contacts ($contactsCount)",
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("Manage", color = KoPilotClusterTokens.PrimaryAccent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EmergencyDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent, modifier = Modifier.size(18.dp))
        Column {
            Text(label, fontSize = 11.sp, color = KoPilotClusterTokens.SecondaryText)
            Text(value, fontSize = 13.sp, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold)
        }
    }
}
