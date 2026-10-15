package imayx.opendots.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// AMOLED-first dark palette. Pure black background, soft surfaces.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00201B),
    primaryContainer = Color(0xFF0B3B35),
    onPrimaryContainer = Color(0xFFA7F3E8),
    secondary = Color(0xFF8EA6FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF0B0D10),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF14181D),
    onSurfaceVariant = Color(0xFF9AA3AD),
    surfaceContainerHighest = Color(0xFF1A1F26),
    error = Color(0xFFFF8A80),
    outline = Color(0xFF2A313A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6B5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA7F3E8),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF3B5BDB),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14181D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14181D),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF5A636E),
    surfaceContainerHighest = Color(0xFFE8EAED),
    error = Color(0xFFB3261E),
    outline = Color(0xFFD4D9DF),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Rounded shape system. Cards, dialogs, headers and sheets share these
 * radii so every screen feels like one app.
 */
val OdShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Consistent spacing scale used across all screens. */
object OdSpacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** Horizontal padding applied to screen content. */
    val screen: Dp = 16.dp

    /** Bottom clearance so content never hides behind the floating nav bar. */
    val navClearance: Dp = 96.dp
}

@Composable
fun OpenDotsTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = OdShapes,
        content = content,
    )
}
