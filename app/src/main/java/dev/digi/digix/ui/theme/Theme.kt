package dev.digi.digix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// --- Digi X TV palette -----------------------------------------------------
// Strictly neutral. There is no colour accent anywhere in the chrome: the
// artwork is the only colour on screen, which is the whole point of a cinema
// interface. "Accent" is therefore white, and every surface is a warm neutral
// grey rather than a tinted one - the earlier indigo tint read as cold and
// gave the whole app a gamer-RGB cast.
//
// Because the accent is white, anything that fills with it must draw BLACK
// text on top. That is what OnAccent is for; do not use OnBg there.
val BrandAccent = Color(0xFFFFFFFF)
val BrandAccentDark = Color(0xFFB0B0B0)
val BrandAccentGlow = Color(0xFFFFFFFF)

/** Formerly a green rating tint. Neutralised - see the note above. */
val BrandSuccess = Color(0xFFE8E8E8)

val Bg = Color(0xFF0A0A0A)
val Surface = Color(0xFF161616)
val SurfaceHigh = Color(0xFF212121)
val SurfaceFocused = Color(0xFF2C2C2C)

/** Text/icon colour to use on top of a filled [Accent] surface. */
val OnAccent = Color(0xFF0A0A0A)

val Accent = BrandAccent
val AccentDim = BrandAccentDark
val AccentAlt = Color(0xFF8A8A8A)
val OnBg = Color(0xFFFFFFFF)
val Muted = Color(0xFFA0A0A0)
val MutedDim = Color(0xFF6B6B6B)
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
    onPrimary = OnAccent,
    secondary = Color(0xFF9E9E9E),
    onSecondary = OnAccent,
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
