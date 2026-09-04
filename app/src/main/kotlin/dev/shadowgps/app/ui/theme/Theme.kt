package dev.shadowgps.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette.
 *
 * Dark by default — this is an app used behind a windscreen, often at night — with a
 * green/amber/red scale reserved for exposure so the colour always means the same thing:
 * green is unseen, red is watched.
 */
object ShadowColors {
    val Ink = Color(0xFF0E1116)
    val Surface = Color(0xFF161B23)
    val SurfaceHigh = Color(0xFF1F2630)
    val Outline = Color(0xFF2E3846)
    val TextPrimary = Color(0xFFE8EDF4)
    val TextSecondary = Color(0xFF9AA7B8)

    val Accent = Color(0xFF38BDF8)

    /** Exposure scale, used by both the map and the route cards. */
    val Clear = Color(0xFF4ADE80)
    val Caution = Color(0xFFFBBF24)
    val Watched = Color(0xFFF87171)

    /** Route line colours: the chosen one, and the alternatives behind it. */
    val RouteSelected = Color(0xFF38BDF8)
    val RouteAlternate = Color(0xFF64748B)

    /**
     * Congestion bands painted over the chosen route.
     *
     * Deliberately the familiar yellow/orange/red rather than the exposure scale: a driver
     * reads traffic colour on a map without being taught it, and reusing the exposure
     * greens here would make one colour mean two different things.
     */
    val TrafficLight = Color(0xFFFACC15)
    val TrafficHeavy = Color(0xFFFB923C)
    val TrafficSevere = Color(0xFFEF4444)
}

private val DarkScheme = darkColorScheme(
    primary = ShadowColors.Accent,
    onPrimary = ShadowColors.Ink,
    secondary = ShadowColors.Clear,
    onSecondary = ShadowColors.Ink,
    background = ShadowColors.Ink,
    onBackground = ShadowColors.TextPrimary,
    surface = ShadowColors.Surface,
    onSurface = ShadowColors.TextPrimary,
    surfaceVariant = ShadowColors.SurfaceHigh,
    onSurfaceVariant = ShadowColors.TextSecondary,
    outline = ShadowColors.Outline,
    error = ShadowColors.Watched,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0369A1),
    secondary = Color(0xFF15803D),
    error = Color(0xFFB91C1C),
)

private val ShadowTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ShadowGpsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = ShadowTypography,
        content = content,
    )
}
