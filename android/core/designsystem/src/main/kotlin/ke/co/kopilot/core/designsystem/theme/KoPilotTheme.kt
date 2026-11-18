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
    primary = KoPilotClusterTokens.PrimaryAccent,
    onPrimary = KoPilotClusterTokens.Background,
    secondary = KoPilotClusterTokens.SecondaryAccent,
    onSecondary = Color(0xFF202020),
    background = KoPilotClusterTokens.Background,
    onBackground = KoPilotClusterTokens.PrimaryText,
    surface = KoPilotClusterTokens.CardSurface,
    onSurface = KoPilotClusterTokens.PrimaryText,
    surfaceVariant = KoPilotClusterTokens.ElevatedSurface,
    onSurfaceVariant = KoPilotClusterTokens.SecondaryText,
    outline = KoPilotClusterTokens.Border,
    error = KoPilotClusterTokens.VeryRough,
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
        fontSize = 48.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum",
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
