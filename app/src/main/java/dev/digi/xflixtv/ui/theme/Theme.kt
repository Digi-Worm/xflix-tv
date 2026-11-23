package dev.digi.xflixtv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val BrandRed = Color(0xFFE50914)
val BrandRedDark = Color(0xFFB81D24)
val BrandRedGlow = Color(0xFFFF3344)
val BrandGreen = Color(0xFF46D369)

val Bg = Color(0xFF141414)
val Surface = Color(0xFF181818)
val SurfaceHigh = Color(0xFF242424)
val SurfaceFocused = Color(0xFF2E2E2E)
val Accent = BrandRed
val AccentDim = BrandRedDark
val AccentAlt = BrandRedGlow
val OnBg = Color(0xFFFFFFFF)
val Muted = Color(0xFFA3A3A3)
val MutedDim = Color(0xFF757575)
val Selection = Color(0xFFFFFFFF)

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
    background = Bg,
    onBackground = OnBg,
    surface = Surface,
    onSurface = OnBg,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
)

@Composable
fun XflixTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

@Composable
fun BrandStyleTheme(content: @Composable () -> Unit) = XflixTvTheme(content)
