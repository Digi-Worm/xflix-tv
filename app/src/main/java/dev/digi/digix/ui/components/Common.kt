package dev.digi.digix.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import dev.digi.digix.R
import dev.digi.digix.data.Meta
import dev.digi.digix.ui.theme.*

/** Focus lift shared by every card: subtle scale and crisp elevation. */
@Composable
internal fun focusScale(focused: Boolean) = animateFloatAsState(
    targetValue = if (focused) 1.07f else 1f,
    animationSpec = tween(180, easing = FastOutSlowInEasing),
    label = "focusScale",
)

/** Scale is read inside graphicsLayer so animations never recompose the card. */
internal fun Modifier.focusScaleLayer(scale: State<Float>): Modifier = graphicsLayer {
    scaleX = scale.value
    scaleY = scale.value
}

/**
 * tvOS poster: the artwork is the whole card. On focus the card lifts, brightens
 * and shows a quiet metadata line — no coloured badges, no match percentages.
 */
@Composable
fun PosterCard(
    meta: Meta,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val m = uiMetrics()
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "posterScale",
    )
    val elevation = animateFloatAsState(
        targetValue = if (focused) 26f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "posterElevation",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (focused) 0.85f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "posterRing",
    )

    Box(
        modifier = modifier
            .width(m.d(TvDimens.posterWidth))
            .height(m.d(TvDimens.posterHeight))
            .zIndex(if (focused) 10f else 1f)
            .focusScaleLayer(scale)
            .graphicsLayer { shadowElevation = elevation.value }
            .clip(RoundedCornerShape(m.d(TvDimens.corner)))
            .background(SurfaceHigh)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick),
    ) {
        if (!meta.poster.isNullOrBlank()) {
            AsyncImage(
                model = meta.poster,
                contentDescription = meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                meta.name,
                color = OnBg,
                fontSize = m.f(13f),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center).padding(m.d(12.dp)),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Soft white focus ring — drawn inside so the shape stays perfect.
        if (ringAlpha > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(2.dp, Color.White.copy(alpha = ringAlpha), RoundedCornerShape(m.d(TvDimens.corner)))
            )
        }

        // Caption rides permanently on touch devices, where there is no focus
        // state to reveal it, and on focus for D-pad navigation.
        if (focused || m.isTouch) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(m.d(72.dp))
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xF20A0A0A))
                        )
                    )
                    .padding(horizontal = m.d(12.dp), vertical = m.d(10.dp)),
            ) {
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text(
                        text = meta.name,
                        color = Color.White,
                        fontSize = m.f(13f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(
                        meta.releaseInfo?.year?.toString(),
                        meta.runtime?.takeIf { meta.videos.isNullOrEmpty() }?.let { "$it min" },
                    ).joinToString("  ·  ")
                    if (sub.isNotBlank()) {
                        Spacer(Modifier.height(m.d(3.dp)))
                        Text(
                            text = sub,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = m.f(10f),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Ranked poster for a "Top 10" shelf. The number sits behind the artwork as a
 * large outlined numeral, in tvOS' restrained style — not a red sticker.
 */
@Composable
fun Top10PosterCard(
    rank: Int,
    meta: Meta,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val m = uiMetrics()
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "top10Scale",
    )
    val elevation = animateFloatAsState(
        targetValue = if (focused) 26f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "top10Elevation",
    )
    val numAlpha by animateFloatAsState(
        targetValue = if (focused) 0.9f else 0.35f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "top10Num",
    )

    Row(
        modifier = modifier
            .zIndex(if (focused) 10f else 1f)
            .focusScaleLayer(scale)
            .graphicsLayer { shadowElevation = elevation.value }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = "$rank",
            fontSize = m.f(72f),
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = numAlpha * 0.22f),
            modifier = Modifier
                .offset(x = m.d(8.dp), y = m.d(4.dp))
                .zIndex(2f),
        )

        Box(
            modifier = Modifier
                .width(m.d(TvDimens.posterWidth - 10.dp))
                .height(m.d(TvDimens.posterHeight))
                .clip(RoundedCornerShape(m.d(TvDimens.corner)))
                .background(SurfaceHigh),
        ) {
            if (!meta.poster.isNullOrBlank()) {
                AsyncImage(
                    model = meta.poster,
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    meta.name,
                    color = OnBg,
                    fontSize = m.f(11f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Center).padding(m.d(8.dp)),
                )
            }
            if (focused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(m.d(2.dp), Color.White.copy(alpha = 0.8f), RoundedCornerShape(m.d(TvDimens.corner)))
                )
            }
        }
    }
}

/**
 * tvOS continue-watching tile: 16:9 art, a hairline progress bar, and the
 * episode/title set small and calm.
 */
@Composable
fun WideCard(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    progress: Float = 0.65f,
) {
    var focused by remember { mutableStateOf(false) }
    val m = uiMetrics()
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "wideScale",
    )
    val elevation = animateFloatAsState(
        targetValue = if (focused) 24f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "wideElevation",
    )

    Box(
        modifier = modifier
            .width(m.d(288.dp))
            .height(m.d(162.dp))
            .zIndex(if (focused) 10f else 1f)
            .focusScaleLayer(scale)
            .graphicsLayer { shadowElevation = elevation.value }
            .clip(RoundedCornerShape(TvDimens.corner))
            .background(SurfaceHigh)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick),
    ) {
        if (!image.isNullOrBlank()) {
            AsyncImage(
                model = image,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Transparent, Color(0xE60A0A0A))
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = m.d(13.dp), bottom = m.d(15.dp), end = m.d(13.dp)),
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = m.f(13f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Spacer(Modifier.height(m.d(2.dp)))
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = m.f(10f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (focused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(m.d(2.dp), Color.White.copy(alpha = 0.8f), RoundedCornerShape(m.d(TvDimens.corner)))
            )
        }
        // Hairline progress, Apple-style: thin, white, quiet.
        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.92f))
            )
        }
    }
}

/**
 * Modern Glassy Left Drawer Menu Item:
 * - Frosted glass pill container with smooth scale animation.
 * - Unselected: Translucent glass pill with crisp, high-contrast white text (easily visible on TV).
 * - Selected: soft white glass container with a brand accent bar.
 * - Focused: Pure white frosted glass capsule with crisp dark typography.
 * - Absolutely no bold borders.
 */
@Composable
fun BrandRailItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }

    val scale = animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "railItemScale",
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            focused -> Color(0xFF111111)
            selected -> Color.White
            else -> Color(0xEEFFFFFF)
        },
        animationSpec = tween(180),
        label = "contentColor",
    )

    val pillBgModifier = when {
        focused -> Modifier.background(
            Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF2F2F2))),
            RoundedCornerShape(24.dp)
        )
        selected -> Modifier.background(
            Brush.horizontalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF))),
            RoundedCornerShape(24.dp)
        )
        else -> Modifier.background(
            Brush.horizontalGradient(listOf(Color(0x1AFFFFFF), Color(0x0CFFFFFF))),
            RoundedCornerShape(24.dp)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (isExpanded) 10.dp else 6.dp, vertical = 4.dp)
            .focusScaleLayer(scale)
            .clip(RoundedCornerShape(24.dp))
            .then(pillBgModifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = if (isExpanded) 14.dp else 10.dp, vertical = 10.dp),
        contentAlignment = if (isExpanded) Alignment.CenterStart else Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isExpanded) Arrangement.Start else Arrangement.Center,
        ) {
            // Selected indicator pill
            if (selected && !focused) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(BrandAccent)
                )
                Spacer(Modifier.width(8.dp))
            }

            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )

            if (isExpanded) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = 14.sp,
                    fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Legacy signature RailItem forwarding to BrandRailItem for compatibility */
@Composable
fun RailItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    BrandRailItem(
        label = label,
        icon = icon ?: Icons.Default.Home,
        selected = selected,
        isExpanded = true,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Full-bleed hero billboard:
 * - High-res cinematic backdrop.
 * - Left & bottom vignette gradients.
 * - "DX" monogram and "FILM / SERIES" tag.
 * - Bold title, #1 in Movies, 98% Match, Ultra HD 4K chips.
 * - Solid White Play button + Translucent More Info button.
 */
@Composable
fun BrandHeroBillboard(
    meta: Meta,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(430.dp)
    ) {
        // High-res backdrop
        AsyncImage(
            model = meta.background ?: meta.poster,
            contentDescription = meta.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom gradient blend into #141414
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.55f to Color(0x66141414),
                        0.85f to Color(0xDD141414),
                        1.0f to Bg,
                    )
                )
        )
        // Left gradient vignette for text legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color(0xF5141414),
                        0.45f to Color(0xAA141414),
                        0.85f to Color.Transparent,
                    )
                )
        )

        // Billboard Content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = TvDimens.contentStart, bottom = 24.dp)
                .fillMaxWidth(0.68f),
        ) {
            // Digi X brand tag: "DX" monogram followed by FILM / SERIES
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "DX",
                    color = BrandAccent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                )
                val categoryTag = if (meta.videos.isNullOrEmpty()) "FILM" else "SERIES"
                Text(
                    categoryTag,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.5.sp,
                )
            }

            Spacer(Modifier.height(4.dp))

            // Title
            Text(
                text = meta.name,
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))

            // Badges Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Top 10 Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(BrandAccent, RoundedCornerShape(2.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        "TOP 10",
                        color = OnAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                Text(
                    "#1 in Movies Today",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "98% Match",
                    color = BrandSuccess,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                meta.releaseInfo?.year?.let {
                    Text(
                        "$it",
                        color = Color(0xFFC0C0C0),
                        fontSize = 13.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        "Ultra HD 4K",
                        color = Color(0xFFD0D0D0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            meta.description?.let { desc ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = desc,
                    color = Color(0xFFD5D5D5),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(18.dp))

            // Action Buttons: Play (White) & More Info (Translucent Gray)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BrandPlayButton(onClick = onPlay)
                BrandInfoButton(onClick = onMoreInfo)
            }
        }
    }
}

/** Play button: pure white background, bold black text, subtle scale on focus */
@Composable
fun BrandPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "Play",
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "playScale")

    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier
            .focusScaleLayer(scale)
            .onFocusChanged { focused = it.isFocused },
        shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(4.dp)),
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor = Color.White,
            contentColor = Color.Black,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** More info button: translucent gray, white text, subtle scale on focus */
@Composable
fun BrandInfoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "More Info",
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "infoScale")

    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier
            .focusScaleLayer(scale)
            .onFocusChanged { focused = it.isFocused },
        shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(4.dp)),
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor = Color(0x66FFFFFF),
            contentColor = Color.White,
            focusedContainerColor = Color(0x99FFFFFF),
            focusedContentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun RowHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        color = OnBg.copy(alpha = 0.62f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        modifier = modifier.padding(start = TvDimens.contentStart, top = 26.dp, bottom = 12.dp),
    )
}

@Composable
fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Muted.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun LoadingState(message: String = "Loading…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.85f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                color = Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            )
        }
    }
}

/** Frosted search entry: quiet, wide, and unmistakably tvOS. */
@Composable
fun SearchBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.012f else 1f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "searchScale",
    )
    val bgColor by animateColorAsState(
        targetValue = if (focused) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "searchBg",
    )
    val ringColor by animateColorAsState(
        targetValue = if (focused) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "searchRing",
    )
    val iconColor by animateColorAsState(
        targetValue = if (focused) Color.White else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "searchIcon",
    )
    val textColor by animateColorAsState(
        targetValue = if (focused) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.45f),
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "searchText",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TvDimens.contentStart, vertical = 14.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(bgColor, RoundedCornerShape(14.dp))
                .border(1.dp, ringColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = iconColor,
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Search movies, shows, people",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
