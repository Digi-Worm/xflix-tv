package dev.digi.xflixtv.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.draw.scale
import dev.digi.xflixtv.data.availableAudioLanguages
import dev.digi.xflixtv.data.findBestForAudio
import dev.digi.xflixtv.data.Stream
import dev.digi.xflixtv.ui.DetailState
import androidx.compose.ui.platform.LocalContext
import dev.digi.xflixtv.XflixTvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import dev.digi.xflixtv.ui.components.focusScale
import dev.digi.xflixtv.ui.components.focusScaleLayer
import dev.digi.xflixtv.ui.components.*
import dev.digi.xflixtv.ui.theme.*

@Composable
fun DetailScreen(
    state: DetailState,
    onPlay: (Stream) -> Unit,
    onEpisodeSelect: (dev.digi.xflixtv.data.Video) -> Unit = {},
    resumeMs: Long = 0L,
    onToggleLibrary: (dev.digi.xflixtv.data.Meta) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val meta = state.meta
    if (state.loading || meta == null) {
        LoadingState("Loading details…")
        return
    }

    val playFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var focusRequestedFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(meta.id) {
        if (focusRequestedFor != meta.id) {
            focusRequestedFor = meta.id
            withFrameNanos { }
            withFrameNanos { }
            kotlinx.coroutines.delay(380)
            runCatching { playFocus.requestFocus() }
            withFrameNanos { }
            runCatching { listState.scrollToItem(0) }
            withFrameNanos { }
            runCatching { listState.scrollToItem(0) }
        }
    }

    var autoPlayPending by remember { mutableStateOf(false) }
    var selectedAudioLanguage by remember(meta.id) { mutableStateOf("Auto") }

    val availableAudios = remember(state.streams) { state.streams.availableAudioLanguages() }
    val effectiveStream = remember(state.streams, selectedAudioLanguage) {
        state.streams.findBestForAudio(selectedAudioLanguage) ?: state.streams.firstOrNull()
    }

    val app = XflixTvApp.instance
    val context = LocalContext.current
    // Warm every plausible first choice in parallel so pressing Play only has to
    // wait for the swarm, not for magnets to be queued one after another.
    LaunchedEffect(state.streams, effectiveStream) {
        val targets = listOfNotNull(effectiveStream) + state.streams
            .filter { !it.infoHash.isNullOrBlank() }
            .sortedByDescending { it.score(preferFallback = false) }
        val topToPrewarm = targets.distinctBy { it.infoHash }.take(2)
        withContext(Dispatchers.IO) {
            topToPrewarm.map { s ->
                async {
                    if (!s.infoHash.isNullOrBlank() && !app.torrentEngine.isKnownDead(s.infoHash!!)) {
                        app.torrentEngine.prewarm(
                            infoHash = s.infoHash!!,
                            trackers = s.trackers,
                            saveDir = java.io.File(context.filesDir, "torrents"),
                            fileIdx = s.fileIdx ?: 0,
                        )
                    }
                }
            }.awaitAll()
        }
    }

    LaunchedEffect(state.streams, autoPlayPending, effectiveStream) {
        if (autoPlayPending && effectiveStream != null) {
            autoPlayPending = false
            onPlay(effectiveStream)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(bottom = 64.dp),
    ) {
        item(key = "backdrop-${meta.id}") {
            Backdrop(
                meta = meta,
                playFocus = playFocus,
                streamsLoading = state.streamsLoading,
                isAutoPlayPending = autoPlayPending,
                resumeMs = resumeMs,
                onPlay = {
                    if (effectiveStream != null) {
                        onPlay(effectiveStream)
                    } else {
                        autoPlayPending = true
                    }
                },
                onToggleLibrary = onToggleLibrary,
            )
        }

        val episodes = meta.videos?.filter { (it.season ?: 0) >= 1 } ?: emptyList()
        if (episodes.isNotEmpty()) {
            item(key = "episodes-${meta.id}") {
                EpisodeSection(
                    videos = episodes,
                    selected = state.selectedVideo,
                    onSelect = onEpisodeSelect,
                )
            }
        }

        // Available Audio Languages Selector
        if (!state.streamsLoading && availableAudios.isNotEmpty()) {
            item(key = "audio-selector-${meta.id}") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = TvDimens.contentStart, end = TvDimens.contentStart, top = 8.dp, bottom = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "AVAILABLE AUDIO",
                            color = MutedDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "(${availableAudios.size} options · Auto-picks best quality)",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        item(key = "audio-auto") {
                            AudioFilterChip(
                                label = "Auto (Best Quality)",
                                isSelected = selectedAudioLanguage == "Auto",
                                onClick = { selectedAudioLanguage = "Auto" },
                            )
                        }
                        items(availableAudios, key = { "audio-$it" }) { lang ->
                            val bestForLang = state.streams.findBestForAudio(lang)
                            val q = bestForLang?.qualityLabel?.takeIf { it.isNotBlank() } ?: "HD"
                            AudioFilterChip(
                                label = "$lang ($q)",
                                isSelected = selectedAudioLanguage == lang,
                                onClick = { selectedAudioLanguage = lang },
                            )
                        }
                    }
                }
            }
        }

        // Clean stream status badge (no technical torrent lists)
        item(key = "stream-status-${meta.id}") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = TvDimens.contentStart, end = TvDimens.contentStart, top = 12.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.streamsLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = BrandRed,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Selecting optimal quality for your TV & network…",
                        color = MutedDim,
                        fontSize = 13.sp,
                    )
                } else if (effectiveStream != null) {
                    val isCustom = selectedAudioLanguage != "Auto"
                    val badgeText = if (isCustom) "BEST ${selectedAudioLanguage.uppercase()} SOURCE AUTO-SELECTED"
                        else "BEST SOURCE AUTO-SELECTED"
                    Box(
                        modifier = Modifier
                            .background(BrandGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            badgeText,
                            color = BrandGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    val qualityBadge = effectiveStream.qualityLabel.ifEmpty { "HD" }
                    val sizeText = effectiveStream.sizeLabel?.let { " · $it" } ?: ""
                    Text(
                        "$qualityBadge$sizeText · ${effectiveStream.seeders} seeds (Ready for instant play)",
                        color = MutedDim,
                        fontSize = 13.sp,
                    )
                } else {
                    Text(
                        "No playable sources found for this title.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.10f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "chipScale",
    )
    val bg = when {
        isFocused -> Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEEEEEE)))
        isSelected -> Brush.horizontalGradient(listOf(Color(0xFFE50914), Color(0xFFB81D24)))
        else -> Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF)))
    }
    val textColor = when {
        isFocused -> Color.Black
        isSelected -> Color.White
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .onFocusChanged { isFocused = it.hasFocus }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected && !isFocused) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun Backdrop(
    meta: dev.digi.xflixtv.data.Meta,
    playFocus: FocusRequester,
    streamsLoading: Boolean,
    isAutoPlayPending: Boolean = false,
    resumeMs: Long,
    onPlay: () -> Unit,
    onToggleLibrary: (dev.digi.xflixtv.data.Meta) -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        AsyncImage(
            model = meta.background ?: meta.poster,
            contentDescription = meta.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Vertical gradient
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x33000000),
                    0.5f to Color(0xB3141414),
                    1f to Bg,
                )
            )
        )
        // Horizontal vignette
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xEE141414), Color(0x88141414), Color.Transparent))
            )
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = TvDimens.contentStart, vertical = 26.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!meta.poster.isNullOrBlank()) {
                AsyncImage(
                    model = meta.poster,
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(136.dp)
                        .height(204.dp)
                        .clip(RoundedCornerShape(TvDimens.corner)),
                )
                Spacer(Modifier.width(26.dp))
            }
            Column(Modifier.weight(1f)) {
                // Xflix Brand Tag: Red "X" + "FLIX FILM" or "FLIX SERIES" so X never repeats
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "X",
                        color = BrandRed,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                    )
                    val categoryTag = if (!meta.videos.isNullOrEmpty()) "FLIX SERIES" else "FLIX FILM"
                    Text(
                        categoryTag,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.5.sp,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(meta.name, color = OnBg, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("98% Match", color = BrandGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    meta.releaseInfo?.year?.let { Chip("$it") }
                    meta.runtime?.let { Chip("${it} min") }
                    Chip("Ultra HD 4K")
                    Chip("5.1 Audio")
                    meta.genre.take(2).forEach { Chip(it) }
                }

                meta.description?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it,
                        color = Color(0xFFD0D0D6),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.85f),
                    )
                }

                Spacer(Modifier.height(18.dp))

                // Action buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.tv.material3.Button(
                        onClick = onPlay,
                        modifier = Modifier.focusRequester(playFocus),
                        shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(4.dp)),
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isAutoPlayPending) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = Color.Black,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Starting…",
                                    color = Color.Black,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (resumeMs > 60_000) "Resume ${shortClock(resumeMs)}" else "Play",
                                    color = Color.Black,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    androidx.tv.material3.Button(
                        onClick = { meta?.let(onToggleLibrary) },
                        shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(4.dp)),
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = Color(0x66FFFFFF),
                            contentColor = OnBg,
                            focusedContainerColor = Color(0x99FFFFFF),
                            focusedContentColor = Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "My List",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (streamsLoading) {
                        Spacer(Modifier.width(16.dp))
                        Text("Finding sources…", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "chipScale",
    )
    val bgColor by animateColorAsState(
        targetValue = if (focused) BrandRed else Color.White.copy(alpha = 0.10f),
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "chipBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (focused) Color.White else Color(0xFFE6E6EA),
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "chipText",
    )

    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .focusScaleLayer(scale)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { shadowElevation = if (focused) 4f else 0f },
    ) {
        Text(text, color = textColor, fontSize = 11.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun EpisodeSection(
    videos: List<dev.digi.xflixtv.data.Video>,
    selected: dev.digi.xflixtv.data.Video?,
    onSelect: (dev.digi.xflixtv.data.Video) -> Unit,
) {
    val seasons = remember(videos) { videos.mapNotNull { it.season }.distinct().sorted() }
    var season by remember(videos) {
        mutableIntStateOf(selected?.season ?: seasons.firstOrNull() ?: 1)
    }
    val episodes = remember(videos, season) {
        videos.filter { it.season == season }.sortedBy { it.episode ?: 0 }
    }

    Column(Modifier.padding(top = 6.dp, bottom = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = TvDimens.contentStart, end = TvDimens.contentStart, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Episodes", color = OnBg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            seasons.forEach { s ->
                SeasonChip(
                    label = "Season $s",
                    selected = s == season,
                    onClick = { season = s },
                )
                Spacer(Modifier.width(8.dp))
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = TvDimens.contentStart),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(episodes, key = { it.id }) { video ->
                EpisodeCard(
                    video = video,
                    active = selected?.id == video.id,
                    onClick = { onSelect(video) },
                )
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "seasonChipScale",
    )
    val elevation = animateFloatAsState(
        targetValue = if (focused) 8f else (if (selected) 4f else 0f),
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "seasonChipElevation",
    )

    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    focused -> BrandRed
                    selected -> BrandRed
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .focusScaleLayer(scale)
            .graphicsLayer { shadowElevation = elevation.value }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.2.sp,
        )
    }
}

@Composable
private fun EpisodeCard(
    video: dev.digi.xflixtv.data.Video,
    active: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.03f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "episodeScale",
    )
    val elevation = animateFloatAsState(
        targetValue = if (focused) 12f else 2f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "episodeElevation",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) Color.White else (if (active) BrandRed else Color.Transparent),
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "episodeBorder",
    )
    val epColor by animateColorAsState(
        targetValue = if (focused || active) BrandRed else Muted,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "episodeNumColor",
    )
    val title = video.name?.takeIf { it.isNotBlank() } ?: video.title ?: "Episode ${video.episode ?: "?"}"

    Column(
        Modifier
            .width(268.dp)
            .height(100.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                shadowElevation = elevation.value
            }
            .clip(RoundedCornerShape(TvDimens.corner))
            .background(if (focused) SurfaceFocused else (if (active) BrandRed.copy(alpha = 0.25f) else Surface))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            "E${video.episode ?: "?"}",
            color = epColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        Text(
            title,
            color = OnBg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun shortClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}

@Composable
private fun StreamRow(
    stream: Stream,
    best: Boolean,
    focusRequester: FocusRequester?,
    onPlay: (Stream) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.012f else 1f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "streamScale",
    )
    val elevation = animateFloatAsState(
        targetValue = if (focused) 8f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "streamElevation",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) Color.White else (if (best) BrandRed.copy(alpha = 0.4f) else Color.Transparent),
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "streamBorder",
    )
    val nameColor by animateColorAsState(
        targetValue = if (focused) Color.White else OnBg,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "streamNameColor",
    )

    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = TvDimens.contentStart, vertical = 5.dp)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            shadowElevation = elevation.value
        }
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { focused = it.isFocused }

    androidx.tv.material3.Card(
        onClick = { onPlay(stream) },
        modifier = modifier,
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(TvDimens.corner)),
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = if (focused) SurfaceFocused else Surface,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .width(74.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (best) BrandRed.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stream.qualityLabel.ifEmpty { "HD" },
                    color = if (best) BrandRed else OnBg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (best) "BEST" else if (stream.isCached) "CACHED" else "SOURCE",
                    color = if (best) BrandRed else MutedDim,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stream.name?.replace("\n", " · ") ?: "Stream",
                    color = nameColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stream.title?.lines()?.firstOrNull()?.take(110) ?: stream.infoHash.orEmpty(),
                    color = Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val stats = buildList {
                    stream.seeders.takeIf { it > 0 }?.let { add("$it seeders") }
                    stream.sizeLabel?.let { add(it) }
                    stream.audioLabel?.let { label ->
                        add(if (stream.channelLabel != null) "$label ${stream.channelLabel}" else label)
                    }
                    if (stream.audioLabel == null) stream.channelLabel?.let { add(it) }
                    if (stream.isCached) add("instant")
                    if (stream.url != null) add("direct link")
                }
                if (stats.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stats.joinToString("  ·  "),
                        color = if (stream.audioLabel == "Atmos") BrandRed else MutedDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (focused) BrandRed else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = if (focused) Color.White else OnBg, modifier = Modifier.size(18.dp))
            }
        }
    }
}
