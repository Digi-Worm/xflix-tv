package dev.digi.digix.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import dev.digi.digix.data.Meta
import dev.digi.digix.ui.HomeState
import dev.digi.digix.ui.components.*
import dev.digi.digix.ui.theme.*

/**
 * Apple TV–style home.
 *
 * Design rules borrowed from tvOS:
 *  - one oversized cinematic hero with the title set large and quiet, not shouted;
 *  - generous whitespace between rows so the eye lands on one shelf at a time;
 *  - soft 14 dp pill radii and a gentle lift on focus instead of hard borders;
 *  - a single accent used sparingly.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onMetaClick: (Meta, String) -> Unit,
    modifier: Modifier = Modifier,
    onSearchClick: () -> Unit = {},
) {
    if (state.loading) {
        LoadingState("Loading catalogs…")
        return
    }

    val featured = remember(state) {
        state.continueWatching.firstOrNull()
            ?: state.rows.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
    }

    var activeMeta by remember(featured) { mutableStateOf(featured) }

    // Android TV panels vary wildly in density: this one reports 320 dpi, so the
    // 1920x1080 surface is only 960x540 dp. Size the hero from the viewport
    // instead of pinning it to a dp value that overflows on such screens.
    BoxWithConstraints(modifier.fillMaxSize().background(Bg)) {
        // Leave the hero roughly half the screen so the first shelf peeks in
        // underneath it — that hint is what makes tvOS feel alive.
        val heroHeight = (maxHeight * 0.55f).coerceIn(230.dp, 400.dp)
        // Only shrink the type a little; below ~0.8 it stops being readable on a TV.
        val heroScale = (heroHeight.value / 400f).coerceIn(0.8f, 1f)

        // Focus restoration must never scroll the page off its own hero.
        val listState = rememberLazyListState()
        // tvOS opens with focus already on the hero's primary action, so the
        // page never scrolls itself away before the viewer has looked at it.
        val heroFocus = remember { FocusRequester() }

        LaunchedEffect(featured) {
            if (featured != null) {
                // Wait for the first frame so the hero is laid out and attached.
                withFrameNanos { }
                runCatching { heroFocus.requestFocus() }
                // Compose reveals a focused item by scrolling to it, which on a
                // tall hero can shove the artwork off screen. Re-assert the top.
                repeat(3) {
                    withFrameNanos { }
                    runCatching { listState.scrollToItem(0, 0) }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 56.dp),
        ) {
            val hero = activeMeta ?: featured
            if (hero != null) {
                item(key = "hero") {
                    AppleHero(
                        meta = hero,
                        height = heroHeight,
                        scale = heroScale,
                        playFocus = heroFocus,
                        onPlay = { onMetaClick(hero, hero.addonId ?: "") },
                        onMoreInfo = { onMetaClick(hero, hero.addonId ?: "") },
                    )
                }
            }

            item(key = "search") {
                GlassSearchRow(onClick = onSearchClick)
            }

            if (state.continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    AppleShelf(title = "Continue Watching") {
                        items(state.continueWatching) { meta ->
                            WideCard(
                                title = meta.name,
                                subtitle = meta.releaseInfo?.title,
                                image = meta.background ?: meta.poster,
                                onClick = { onMetaClick(meta, meta.addonId ?: "") },
                                onFocus = { activeMeta = meta },
                            )
                        }
                    }
                }
            }

            state.rows.forEachIndexed { index, row ->
                if (row.items.isNotEmpty()) {
                    item(key = "row-${row.id}-$index") {
                        val isTop = index == 0
                        AppleShelf(title = if (isTop) "Top 10 Today" else row.title) {
                            if (isTop) {
                                itemsIndexed(row.items.take(10)) { rank, meta ->
                                    Top10PosterCard(
                                        rank = rank + 1,
                                        meta = meta.copy(addonId = row.addonUrl),
                                        onClick = { onMetaClick(meta.copy(addonId = row.addonUrl), row.addonUrl) },
                                        onFocus = { activeMeta = meta },
                                    )
                                }
                            } else {
                                items(row.items) { meta ->
                                    PosterCard(
                                        meta = meta.copy(addonId = row.addonUrl),
                                        onClick = { onMetaClick(meta.copy(addonId = row.addonUrl), row.addonUrl) },
                                        onFocus = { activeMeta = meta },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.rows.isEmpty() && state.continueWatching.isEmpty()) {
                item(key = "empty") {
                    EmptyState("No addons installed. Add one from the Addons tab.")
                }
            }
        }
    }
}

/** One shelf: quiet uppercase title, then a horizontal rail with edge padding. */
@Composable
private fun AppleShelf(
    title: String,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            text = title.uppercase(),
            color = OnBg.copy(alpha = 0.62f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = TvDimens.contentStart, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = TvDimens.contentStart),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
            content = content,
        )
    }
}

/** Slim frosted search entry under the hero. */
@Composable
private fun GlassSearchRow(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.01f else 1f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "glassSearchScale",
    )
    Row(
        modifier = Modifier
            .padding(start = TvDimens.contentStart, end = TvDimens.contentStart, top = 20.dp)
            .fillMaxWidth(0.5f)
            .zIndex(if (focused) 8f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (focused) 0.16f else 0.08f))
            .border(
                width = 1.dp,
                color = if (focused) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Search movies, shows, people",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
        )
    }
}

/**
 * tvOS hero: full-bleed art, a soft bottom fade into the page, and type that
 * breathes. No shouting badges, no match percentages.
 */
@Composable
private fun AppleHero(
    meta: Meta,
    height: Dp,
    scale: Float,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        AsyncImage(
            model = meta.background ?: meta.poster,
            contentDescription = meta.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Fade to page background so the art melts into the shelves.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color(0x55101010),
                        0.78f to Color(0xE6101010),
                        1.0f to Bg,
                    )
                )
        )
        // Left scrim for type contrast.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color(0xD9101010),
                        0.38f to Color(0x99101010),
                        0.78f to Color.Transparent,
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 36.dp, bottom = (30 * scale).dp)
                .fillMaxWidth(0.58f),
        ) {
            Text(
                text = if (meta.videos.isNullOrEmpty()) "FILM" else "SERIES",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = (11 * scale).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp,
            )
            Spacer(Modifier.height((7 * scale).dp))
            Text(
                text = meta.name,
                color = Color.White,
                fontSize = (40 * scale).sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (46 * scale).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val metaLine = listOfNotNull(
                meta.releaseInfo?.year?.toString(),
                meta.releaseInfo?.rating?.let { "IMDb $it" },
                // A series' `runtime` is per-episode, so it reads as nonsense here.
                // Anything under 10 minutes for a feature is a parse artefact, not a
                // real duration - better to omit it than to print "2 min".
                meta.runtime?.takeIf { meta.videos.isNullOrEmpty() && it >= 10 }?.let { "$it min" },
            ).joinToString("   ·   ")
            if (metaLine.isNotBlank()) {
                Spacer(Modifier.height((9 * scale).dp))
                Text(
                    metaLine,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = (13 * scale).sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            meta.description?.let { desc ->
                Spacer(Modifier.height((10 * scale).dp))
                Text(
                    desc,
                    color = Color.White.copy(alpha = 0.74f),
                    fontSize = (13 * scale).sp,
                    lineHeight = (19 * scale).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height((18 * scale).dp))
            Row(horizontalArrangement = Arrangement.spacedBy((12 * scale).dp)) {
                ApplePillButton(
                    label = "Play",
                    leadingIcon = Icons.Filled.PlayArrow,
                    filled = true,
                    scale = scale,
                    focusRequester = playFocus,
                    onClick = onPlay,
                )
                ApplePillButton(
                    label = "More Info",
                    leadingIcon = Icons.Outlined.Info,
                    filled = false,
                    scale = scale,
                    onClick = onMoreInfo,
                )
            }
        }
    }
}

/** tvOS pill: solid white for the primary action, frosted glass for the rest. */
@Composable
private fun ApplePillButton(
    label: String,
    filled: Boolean,
    scale: Float,
    onClick: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "pillScale",
    )
    val fg = if (filled) Color.Black else Color.White
    Box(
        Modifier
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
            }
            .clip(RoundedCornerShape(50))
            .background(
                if (filled) Color.White
                else Color.White.copy(alpha = if (focused) 0.26f else 0.16f)
            )
            .border(
                width = 1.dp,
                color = if (focused && !filled) Color.White.copy(alpha = 0.7f) else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = (24 * scale).dp,
                vertical = (11 * scale).dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size((16 * scale).dp),
                )
                Spacer(Modifier.width((8 * scale).dp))
            }
            Text(
                text = label,
                color = fg,
                fontSize = (13.5f * scale).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
