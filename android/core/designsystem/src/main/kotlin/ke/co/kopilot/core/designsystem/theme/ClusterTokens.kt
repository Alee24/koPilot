package ke.co.kopilot.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Digital instrument-cluster design tokens for KoPilot.
 */
object KoPilotClusterTokens {
    val Background = Color(0xFF071019)
    val CardSurface = Color(0xFF101F2B)
    val ElevatedSurface = Color(0xFF172A38)
    val GlassCardSurface = Color(0xB8101F2B)
    val GlassElevatedSurface = Color(0xA6172A38)
    val SpecularRimBorder = Color(0x14FFFFFF)
    val ElevatedRimBorder = Color(0x1FFFFFFF)
    val SubtleGlassBorder = Color(0xBF263A49)
    val PrimaryAccent = Color(0xFF00DDB5)
    val SecondaryAccent = Color(0xFF2687F5)
    val SmoothRoad = Color(0xFF16D98A)
    val ModerateRoad = Color(0xFFF5C542)
    val RoughRoad = Color(0xFFF28C38)
    val VeryRough = Color(0xFFEF4444)
    val PrimaryText = Color(0xFFF5F8FA)
    val SecondaryText = Color(0xFFA4B4C3)
    val Border = Color(0xFF263A49)
}

/**
 * Standardized spacing scale (8dp grid with 4dp micro-offsets, 48dp minimum touch target).
 */
object KoPilotSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val CockpitGap = 12.dp
    val TargetMin = 48.dp
}

/**
 * Standardized aerodynamic corner radii.
 */
object KoPilotRadii {
    val Xs = 6.dp
    val Sm = 8.dp
    val Btn = 10.dp
    val Md = 12.dp
    val Card = 14.dp
    val Lg = 16.dp
    val Xl = 18.dp
    val Pill = 50
}

/**
 * Motion timing constants for gauges and pill toggles.
 */
object KoPilotMotion {
    const val GaugeSweepMs = 180
    const val PillToggleMs = 120
}

/**
 * Standardized icon sizes.
 */
object KoPilotIconSizes {
    val Sm = 16.dp
    val Md = 20.dp
    val Lg = 24.dp
}
