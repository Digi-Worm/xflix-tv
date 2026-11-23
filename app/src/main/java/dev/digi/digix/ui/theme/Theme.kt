package dev.digi.digix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// --- Digi X TV palette -----------------------------------------------------
// Violet primary with cyan secondary. Violet is deliberate rather than cyan
// for the primary: it is used as a filled background behind white text in
// several places (TOP 10 badge, install button), and white-on-cyan fails
// contrast. Cyan is kept as the secondary/glow tone where it sits on dark.
val BrandAccent = Color(0xFF7C3AED)
val BrandAccentDark = Color(0xFF5B21B6)
val BrandAccentGlow = Color(0xFFA78BFA)
val BrandSuccess = Color(0xFF4ADE80)
val BrandCyan = Color(0xFF22D3EE)

val Bg = Color(0xFF0B0B18)
val Surface = Color(0xFF15152A)
val SurfaceHigh = Color(0xFF1F1F3A)
val SurfaceFocused = Color(0xFF2A2A4D)
val Accent = BrandAccent
val AccentDim = BrandAccentDark
val AccentAlt = BrandCyan
val OnBg = Color(0xFFFFFFFF)
val Muted = Color(0xFFA9A9C4)
val MutedDim = Color(0xFF74748F)
val Selection = Color(0xFFFFFFFF)

/**
 * Design tokens authored for the reference 960x540dp TV panel.
 *
 * These are deliberately *base* values, not viewport-corrected ones. Anything
 * that must adapt to the device is scaled through [UiMetrics.d] at the call
 * site, which already clamps to a legible band on phones and tablets.
 */
object TvDimens {
    val railWidth = 68.dp
    val posterWidth = 140.dp
    val posterHeight = 210.dp
    val rowGap = 16.dp
    val contentStart = 36.dp
    val corner = 10.dp
}

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentAlt,
    onSecondary = Bg,
    background = Bg,
    onBackground = OnBg,
    surface = Surface,
    onSurface = OnBg,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
)

@Composable
fun DigiXTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

@Composable
fun BrandStyleTheme(content: @Composable () -> Unit) = DigiXTheme(content)
