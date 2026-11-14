package ke.co.kopilot.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object KoPilotColors {
    val CockpitBg = Color(0xFF0B0F14)
    val CockpitSurface = Color(0xFF141B23)
    val CockpitSurfaceElevated = Color(0xFF1C2632)
    val CockpitBorder = Color(0xFF2B3848)
    val TextPrimary = Color(0xFFF4F7FA)
    val TextSecondary = Color(0xFF9FB0C3)
    val AccentTeal = Color(0xFF00BFA5)
    val AccentBlue = Color(0xFF2979FF)
    val WarningAmber = Color(0xFFFFB300)
    val DangerRed = Color(0xFFEF5350)
    val GoodGreen = Color(0xFF4CAF50)
}

private val DarkAutomotiveScheme = darkColorScheme(
    primary = KoPilotColors.AccentTeal,
    onPrimary = Color(0xFF00201B),
    secondary = KoPilotColors.AccentBlue,
    onSecondary = Color.White,
    background = KoPilotColors.CockpitBg,
    onBackground = KoPilotColors.TextPrimary,
    surface = KoPilotColors.CockpitSurface,
    onSurface = KoPilotColors.TextPrimary,
    surfaceVariant = KoPilotColors.CockpitSurfaceElevated,
    onSurfaceVariant = KoPilotColors.TextSecondary,
    outline = KoPilotColors.CockpitBorder,
    error = KoPilotColors.DangerRed,
    onError = Color.White,
)

private val LightAutomotiveScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    secondary = Color(0xFF1565C0),
    onSecondary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF101820),
    surface = Color.White,
    onSurface = Color(0xFF101820),
    surfaceVariant = Color(0xFFE8EEF5),
    onSurfaceVariant = Color(0xFF4A5A6A),
    outline = Color(0xFFD0DAE5),
    error = Color(0xFFC62828),
    onError = Color.White,
)

val KoPilotTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 56.sp,
        lineHeight = 60.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun KoPilotTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkAutomotiveScheme else LightAutomotiveScheme,
        typography = KoPilotTypography,
        content = content,
    )
}
