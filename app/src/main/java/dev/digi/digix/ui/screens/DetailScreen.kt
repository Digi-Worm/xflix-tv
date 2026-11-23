package dev.digi.digix.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import dev.digi.digix.data.availableAudioLanguages
import dev.digi.digix.data.findBestForAudio
import dev.digi.digix.data.Stream
import dev.digi.digix.ui.DetailState
import androidx.compose.ui.platform.LocalContext
import dev.digi.digix.DigiXApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import dev.digi.digix.ui.components.focusScale
import dev.digi.digix.ui.components.focusScaleLayer
import dev.digi.digix.ui.components.*
import dev.digi.digix.ui.cast.CastIconButton
import dev.digi.digix.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    state: DetailState,
    onPlay: (Stream) -> Unit,
    onEpisodeSelect: (dev.digi.digix.data.Video) -> Unit = {},
    resumeMs: Long = 0L,
    onToggleLibrary: (dev.digi.digix.data.Meta) -> Unit = {},
    /** Present only on touch devices: hands the title to a TV instead of playing here. */
    onCastRequest: ((dev.digi.digix.data.CastRequest) -> Unit)? = null,
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

    val app = DigiXApp.instance
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
                onCast = onCastRequest?.let { request ->
                    {
                        val target = effectiveStream
                        if (target != null) {
                            request(
                                dev.digi.digix.data.CastRequest(
                                    title = listOfNotNull(
                                        meta.name,
                                        state.selectedVideo?.let { ep ->
                                            listOfNotNull(ep.season, ep.episode)
                                                .takeIf { it.size == 2 }
                                                ?.let { "S${it[0]}E${it[1]}" }
                                        },
                                    ).joinToString(" · "),
                                    infoHash = target.infoHash,
                                    fileIdx = target.fileIdx ?: 0,
                                    url = target.url,
                                    trackers = target.trackers,
                                    poster = meta.poster,
                                    positionMs = resumeMs,
                                )
                            )
                        } else {
                            autoPlayPending = true
                        }
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
                val m = uiMetrics()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = m.d(TvDimens.contentStart),
                            end = m.d(TvDimens.contentStart),
                            top = m.d(8.dp),
                            bottom = m.d(4.dp),
                        )
                ) {
                    // FlowRow: on a phone the count/hint string is longer than
                    // the space left beside the label, so it drops to its own
                    // line instead of being squeezed to a few characters.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(m.d(8.dp)),
                        verticalArrangement = Arrangement.spacedBy(m.d(2.dp)),
                    ) {
                        Text(
                            "AVAILABLE AUDIO",
                            color = MutedDim,
                            fontSize = m.f(10.5f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = m.f(1f),
                        )
                        Text(
                            "(${availableAudios.size} options · Auto-picks best quality)",
                            color = Muted,
                            fontSize = m.f(10.5f),
                        )
                    }
                    Spacer(Modifier.height(m.d(8.dp)))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(m.d(8.dp)),
                        contentPadding = PaddingValues(vertical = m.d(4.dp)),
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
            val m = uiMetrics()
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = m.d(TvDimens.contentStart),
                        end = m.d(TvDimens.contentStart),
                        top = m.d(12.dp),
                        bottom = m.d(24.dp),
                    ),
                horizontalArrangement = Arrangement.spacedBy(m.d(10.dp)),
                verticalArrangement = Arrangement.spacedBy(m.d(5.dp)),
            ) {
                if (state.streamsLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = BrandAccent,
                            modifier = Modifier.size(m.d(15.dp)),
                            strokeWidth = m.d(2.dp),
                        )
                        Spacer(Modifier.width(m.d(9.dp)))
                        Text(
                            "Selecting optimal quality for your TV & network…",
                            color = MutedDim,
                            fontSize = m.f(12f),
                        )
                    }
                } else if (effectiveStream != null) {
                    val isCustom = selectedAudioLanguage != "Auto"
                    val badgeText = if (isCustom) "BEST ${selectedAudioLanguage.uppercase()} SOURCE AUTO-SELECTED"
                        else "BEST SOURCE AUTO-SELECTED"
                    Box(
                        modifier = Modifier
                            .background(BrandSuccess.copy(alpha = 0.15f), RoundedCornerShape(m.d(4.dp)))
                            .padding(horizontal = m.d(8.dp), vertical = m.d(4.dp)),
                    ) {
                        Text(
                            badgeText,
                            color = BrandSuccess,
                            fontSize = m.f(10.5f),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    val qualityBadge = effectiveStream.qualityLabel.ifEmpty { "HD" }
                    val sizeText = effectiveStream.sizeLabel?.let { " · $it" } ?: ""
                    Text(
                        "$qualityBadge$sizeText · ${effectiveStream.seeders} seeds (Ready for instant play)",
                        color = MutedDim,
                        fontSize = m.f(12f),
                    )
                } else {
                    Text(
                        "No playable sources found for this title.",
                        color = Muted,
                        fontSize = m.f(12f),
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
    val m = uiMetrics()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.10f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "chipScale",
    )
    val bg = when {
        isFocused -> Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEEEEEE)))
        isSelected -> Brush.horizontalGradient(listOf(Color(0xFFDCDCDC), Color(0xFFC4C4C4)))
        else -> Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF)))
    }
    val textColor = when {
        isFocused -> Color.Black
        isSelected -> Color.Black
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(m.d(20.dp)))
            .background(bg)
            .clickable(onClick = onClick)
            .onFocusChanged { isFocused = it.hasFocus }
            .padding(horizontal = m.d(15.dp), vertical = m.d(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected && !isFocused) {
                Box(
                    modifier = Modifier
                        .size(m.d(6.dp))
                        .clip(CircleShape)
                        .background(Color.White)
                )
                Spacer(Modifier.width(m.d(6.dp)))
            }
            Text(
                text = label,
                color = textColor,
                fontSize = m.f(12.5f),
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Backdrop(
    meta: dev.digi.digix.data.Meta,
    playFocus: FocusRequester,
    streamsLoading: Boolean,
    isAutoPlayPending: Boolean = false,
    resumeMs: Long,
    onPlay: () -> Unit,
    onCast: (() -> Unit)? = null,
    onToggleLibrary: (dev.digi.digix.data.Meta) -> Unit = {},
) {
    // The backdrop was a fixed 420dp, which is more than half a phone's portrait
    // viewport and pushed the actions off the first screen. It now takes a share
    // of the available height instead, and drops the poster on narrow screens
    // where the poster column would squeeze the title into a sliver.
    val m = uiMetrics()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val backdropHeight = if (m.isPortrait) {
            (maxHeight * 0.5f).coerceIn(260.dp, 420.dp)
        } else {
            (maxHeight * 0.66f).coerceIn(300.dp, 460.dp)
        }
        val showPoster = maxWidth >= m.d(520.dp)
    Box(Modifier.fillMaxWidth().height(backdropHeight)) {
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
                .padding(horizontal = m.d(TvDimens.contentStart), vertical = m.d(20.dp)),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (showPoster && !meta.poster.isNullOrBlank()) {
                AsyncImage(
                    model = meta.poster,
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(m.d(110.dp))
                        .height(m.d(165.dp))
                        .clip(RoundedCornerShape(m.d(TvDimens.corner))),
                )
                Spacer(Modifier.width(m.d(20.dp)))
            }
            Column(Modifier.weight(1f)) {
                // Digi X brand tag: "DX" monogram followed by FILM / SERIES
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.d(6.dp)),
                ) {
                    Text(
                        "DX",
                        color = BrandAccent,
                        fontSize = m.f(18f),
                        fontWeight = FontWeight.Black,
                        letterSpacing = m.f(-1f),
                    )
                    val categoryTag = if (!meta.videos.isNullOrEmpty()) "SERIES" else "FILM"
                    Text(
                        categoryTag,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = m.f(11f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = m.f(2.2f),
                    )
                }

                Spacer(Modifier.height(m.d(4.dp)))
                Text(meta.name, color = OnBg, fontSize = m.f(30f), fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(m.d(7.dp)))

                // Was a plain Row of six chips, which ran off the right edge of a
                // portrait phone. FlowRow wraps them onto a second line instead.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(m.d(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(m.d(6.dp)),
                ) {
                    Text("98% Match", color = BrandSuccess, fontSize = m.f(13f), fontWeight = FontWeight.Bold)
                    meta.releaseInfo?.year?.let { Chip("$it") }
                    meta.runtime?.let { Chip("${it} min") }
                    Chip("Ultra HD 4K")
                    Chip("5.1 Audio")
                    meta.genre.take(2).forEach { Chip(it) }
                }

                meta.description?.let {
                    Spacer(Modifier.height(m.d(9.dp)))
                    Text(
                        it,
                        color = Color(0xFFD0D0D6),
                        fontSize = m.f(12f),
                        lineHeight = m.f(17f),
                        maxLines = if (m.isPortrait) 4 else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(if (m.isPortrait) 1f else 0.85f),
                    )
                }

                Spacer(Modifier.height(m.d(14.dp)))

                // Action buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.tv.material3.Button(
                        onClick = onPlay,
                        modifier = Modifier.focusRequester(playFocus),
                        shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(m.d(6.dp))),
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                        contentPadding = PaddingValues(horizontal = m.d(22.dp), vertical = m.d(10.dp)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isAutoPlayPending) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = Color.Black,
                                    modifier = Modifier.size(m.d(18.dp)),
                                    strokeWidth = m.d(2.dp),
                                )
                                Spacer(Modifier.width(m.d(7.dp)))
                                Text(
                                    "Starting…",
                                    color = Color.Black,
                                    fontSize = m.f(14f),
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(m.d(21.dp)),
                                )
                                Spacer(Modifier.width(m.d(5.dp)))
                                Text(
                                    if (resumeMs > 60_000) "Resume ${shortClock(resumeMs)}" else "Play",
                                    color = Color.Black,
                                    fontSize = m.f(14f),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(m.d(11.dp)))
                    androidx.tv.material3.Button(
                        onClick = { meta?.let(onToggleLibrary) },
                        shape = androidx.tv.material3.ButtonDefaults.shape(RoundedCornerShape(m.d(6.dp))),
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = Color(0x66FFFFFF),
                            contentColor = OnBg,
                            focusedContainerColor = Color(0x99FFFFFF),
                            focusedContentColor = Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = m.d(17.dp), vertical = m.d(10.dp)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(m.d(18.dp)),
                            )
                            Spacer(Modifier.width(m.d(5.dp)))
                            Text(
                                "My List",
                                color = Color.White,
                                fontSize = m.f(13f),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    // Casting is a phone affordance: on a TV there is nothing to
                    // cast to, so the callback is simply absent there.
                    onCast?.let { cast ->
                        Spacer(Modifier.width(m.d(11.dp)))
                        CastIconButton(
                            active = false,
                            onClick = cast,
                        )
                    }
                    if (streamsLoading) {
                        Spacer(Modifier.width(m.d(13.dp)))
                        Text("Finding sources…", color = Muted, fontSize = m.f(12f))
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Chip(text: String) {
    var focused by remember { mutableStateOf(false) }
    val m = uiMetrics()
    val scale = animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "chipScale",
    )
    val bgColor by animateColorAsState(
        targetValue = if (focused) BrandAccent else Color.White.copy(alpha = 0.10f),
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "chipBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (focused) OnAccent else Color(0xFFE6E6EA),
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "chipText",
    )

    Box(
        Modifier
            .clip(RoundedCornerShape(m.d(4.dp)))
            .background(bgColor)
            .padding(horizontal = m.d(8.dp), vertical = m.d(4.dp))
            .focusScaleLayer(scale)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { shadowElevation = if (focused) 4f else 0f },
    ) {
        Text(text, color = textColor, fontSize = m.f(10.5f), fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun EpisodeSection(
    videos: List<dev.digi.digix.data.Video>,
    selected: dev.digi.digix.data.Video?,
    onSelect: (dev.digi.digix.data.Video) -> Unit,
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
                    focused -> BrandAccent
                    selected -> BrandAccent
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
    video: dev.digi.digix.data.Video,
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
        targetValue = if (focused) Color.White else (if (active) BrandAccent else Color.Transparent),
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "episodeBorder",
    )
    val epColor by animateColorAsState(
        targetValue = if (focused || active) BrandAccent else Muted,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "episodeNumColor",
    )
    val title = video.name?.takeIf { it.isNotBlank() } ?: video.title ?: "Episode ${video.episode ?: "?"}"
    val m = uiMetrics()

    Column(
        Modifier
            .width(m.d(240.dp))
            .height(m.d(92.dp))
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                shadowElevation = elevation.value
            }
            .clip(RoundedCornerShape(TvDimens.corner))
            .background(if (focused) SurfaceFocused else (if (active) BrandAccent.copy(alpha = 0.25f) else Surface))
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
        targetValue = if (focused) Color.White else (if (best) BrandAccent.copy(alpha = 0.4f) else Color.Transparent),
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
                    .background(if (best) BrandAccent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stream.qualityLabel.ifEmpty { "HD" },
                    color = if (best) BrandAccent else OnBg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (best) "BEST" else if (stream.isCached) "CACHED" else "SOURCE",
                    color = if (best) BrandAccent else MutedDim,
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
                        color = if (stream.audioLabel == "Atmos") BrandAccent else MutedDim,
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
                    .background(if (focused) BrandAccent else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = if (focused) Color.White else OnBg, modifier = Modifier.size(18.dp))
            }
        }
    }
}
