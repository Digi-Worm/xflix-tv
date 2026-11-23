package dev.digi.xflixtv.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * Viewport-derived sizing.
 *
 * The app runs on a 960x540dp TV panel, but the same APK also lands on phones,
 * tablets and other TV boxes that report very different dp sizes. Everything
 * that used to be a hardcoded dp/sp was therefore wrong somewhere: a deck built
 * for a 960dp-wide panel overflowed a 640dp one, and a 40sp headline was
 * enormous on a 360dp-tall phone.
 *
 * Rather than scaling every dimension by screen width (which would make a
 * phone's UI microscopic, since dp already normalises physical size), the scale
 * is derived from the *short* side of the viewport against the reference panel
 * and clamped to a narrow band. Layout that still cannot fit at that scale is
 * handled by wrapping (FlowRow), not by shrinking text below legibility.
 */
@Immutable
data class UiMetrics(
    /** 1.0 on the reference 960x540dp TV panel. Clamped to 0.72..1.25. */
    val scale: Float,
    /** Short side of the viewport in dp. */
    val shortSide: Dp,
    /** True when there is too little room for two-line control decks. */
    val isCompact: Boolean,
) {
    /** Scale a design dimension authored for the reference panel. */
    fun d(base: Dp): Dp = (base.value * scale).dp

    /** Scale a font size authored for the reference panel. */
    fun f(base: Float): TextUnit = (base * scale).sp
}

/** Reference short side: the TCL panel reports 540dp of height at 1080p. */
private const val REFERENCE_SHORT_SIDE = 540f

private val DefaultMetrics = UiMetrics(scale = 1f, shortSide = 540.dp, isCompact = false)

val LocalUiMetrics = staticCompositionLocalOf { DefaultMetrics }

/** Current viewport metrics, for composables that need to size themselves. */
@Composable
fun uiMetrics(): UiMetrics = LocalUiMetrics.current

/**
 * Wraps content and publishes viewport metrics for everything below.
 *
 * Must be placed inside a bounded box (it uses [BoxWithConstraints]).
 */
@Composable
fun ResponsiveScope(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val shortSide = min(maxWidth.value, maxHeight.value)
        val metrics = remember(shortSide) {
            UiMetrics(
                scale = (shortSide / REFERENCE_SHORT_SIDE).coerceIn(0.72f, 1.25f),
                shortSide = shortSide.dp,
                isCompact = shortSide < 460f,
            )
        }
        CompositionLocalProvider(LocalUiMetrics provides metrics) {
            content()
        }
    }
}
