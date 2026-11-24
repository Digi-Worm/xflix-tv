package dev.digi.digix.ui.player

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed

import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.material3.Text
import dev.digi.digix.R
import dev.digi.digix.data.Stream
import dev.digi.digix.data.TorrentEngine
import dev.digi.digix.data.availableAudioLanguages
import dev.digi.digix.data.findBestForAudio
import dev.digi.digix.ui.theme.Accent
import dev.digi.digix.ui.theme.Bg
import dev.digi.digix.ui.theme.Muted
import dev.digi.digix.ui.theme.OnBg
import dev.digi.digix.ui.theme.OnAccent
import dev.digi.digix.ui.theme.BrandAccent
import dev.digi.digix.ui.theme.SurfaceFocused
import dev.digi.digix.ui.theme.ResponsiveScope
import dev.digi.digix.ui.theme.uiMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PREFETCH_BYTES = 256L * 1024
/**
 * How long the play button may block before ExoPlayer is handed the local URL.
 * The torrent server waits per piece on demand, so a short head start beats a
 * long "fully buffered" wait: decoding starts while the swarm still delivers.
 */
private const val PREFETCH_HEAD_START_MS = 2_500L
private const val SEEK_STEP_MS = 10_000L
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Stable identity for a stream, including the file index so episodes of one pack
 *  are tracked independently. */
private fun Stream.failKey(): String =
    "${infoHash ?: url ?: externalUrl ?: "stream"}#${fileIdx ?: 0}"

/**
 * Order candidate sources for automatic failover.
 *
 * Decodability comes first, then the normal score. A 10-bit x265 file cannot be
 * played on this TV no matter how healthy its swarm is, so every failover path
 * must rank it below any 8-bit alternative - otherwise the player hops from one
 * undecodable file to the next and the viewer sees it as endless source switching.
 */
private fun List<Stream>.bestFallbacks(
    failed: Collection<String>,
    torrentEngine: TorrentEngine,
    preferFallback: Boolean,
): Stream? = asSequence()
    .filter { it.failKey() !in failed }
    .filter { s -> s.infoHash.isNullOrBlank() || !torrentEngine.isKnownDead(s.infoHash!!) }
    .sortedWith(
        compareByDescending<Stream> { !it.isTenBit }
            .thenByDescending { it.score(preferFallback = preferFallback) }
    )
    .firstOrNull()

private enum class Control(val label: String) {
    Rewind("10s back"),
    PlayPause("Play"),
    Forward("10s ahead"),
    Audio("Audio"),
    Subtitles("Subtitles"),
    Quality("Quality"),
    Speed("Speed"),
    Screen("Screen"),
}

/** Raise/keep the picture filled — TV panels are 16:9 but plenty of releases aren't. */
private val ASPECT_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)

private enum class Panel { None, Audio, Subtitles, Quality, Speed }

private data class TrackChoice(
    val label: String,
    val detail: String?,
    val group: TrackGroup?,
    val index: Int,
    val selected: Boolean,
    val targetStream: Stream? = null,
    val targetLanguage: String? = null,
)

/**
 * Full-screen player for direct addon URLs and torrents (via the local server).
 *
 * Controls are drawn in Compose rather than by the Media3 controller so the
 * remote never fights for focus: the whole screen is one focus target and the
 * D-pad drives a small seek/controls/panel state machine.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    stream: Stream,
    allStreams: List<Stream> = emptyList(),
    torrentEngine: TorrentEngine,
    title: String? = null,
    startPositionMs: Long = 0L,
    onProgress: (Long) -> Unit,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    val engineState by torrentEngine.state.collectAsState()

    var currentStream by remember(stream) { mutableStateOf(stream) }
    var isAutoAdaptive by remember { mutableStateOf(true) }
    var savedPositionMs by remember { mutableStateOf(startPositionMs) }
    val failedStreamKeys = remember { mutableStateListOf<String>() }
    var hudNotification by remember { mutableStateOf<String?>(null) }
    var hasStartedPlaying by remember { mutableStateOf(false) }
    var autoDowngraded by remember { mutableStateOf(false) }
    var preferredAudioLanguage by remember { mutableStateOf<String?>(null) }

    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var maxPositionMs by remember { mutableStateOf(0L) }  // only ever increases — safe to use in STATE_ENDED
    var durationMs by remember { mutableStateOf(0L) }
    var bufferedMs by remember { mutableStateOf(0L) }
    // Bumped whenever the URL is handed to ExoPlayer so the prepare effect re-runs even
    // if two different torrents resolve to the same "127.0.0.1:<port>/<fileIdx>" string.
    var prepareNonce by remember { mutableIntStateOf(0) }
    // Invalidates any delayed retry Runnable whenever the active stream changes, so a
    // retry queued for an old URL can never prepare over the new one.
    var retryGeneration by remember { mutableIntStateOf(0) }

    // Overlay state machine
    var overlayVisible by remember { mutableStateOf(true) }
    var seekFocused by remember { mutableStateOf(true) }
    var controlIndex by remember { mutableIntStateOf(1) }
    var panel by remember { mutableStateOf(Panel.None) }
    var panelIndex by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var speed by remember { mutableFloatStateOf(1f) }
    var aspectIndex by remember { mutableIntStateOf(0) }

    val focusRequester = remember { FocusRequester() }
    val isTorrent = !currentStream.infoHash.isNullOrBlank()
    val preparing = resolvedUrl == null && error == null

    // Resolve the active stream into a playable URL.
    LaunchedEffect(currentStream) {
        resolvedUrl = null
        error = null
        isBuffering = true
        retries = 0
        retryGeneration++
        maxPositionMs = 0L
        hasStartedPlaying = false
        try {
            // Prefer a torrent that is already serving data. The detail screen
            // prewarms the top candidates, so picking a warm one means Play starts
            // in well under a second instead of waiting on a cold peer handshake.
            if (currentStream.url.isNullOrBlank() && isTorrent) {
                val warm = allStreams
                    .filter { !it.infoHash.isNullOrBlank() }
                    .filter { !torrentEngine.isKnownDead(it.infoHash!!) }
                    .filter { torrentEngine.isReady(it.infoHash!!) }
                    .maxByOrNull { it.score(preferFallback = false) }
                if (warm != null && warm.failKey() != currentStream.failKey()) {
                    currentStream = warm
                    return@LaunchedEffect
                }
            }
            val direct = currentStream.url
            if (direct != null && direct.startsWith("http", true)) {
                resolvedUrl = direct
            } else if (!currentStream.infoHash.isNullOrBlank()) {
                val fileIdx = currentStream.fileIdx ?: 0
                val ok = runCatching {
                    torrentEngine.open(
                        infoHash = currentStream.infoHash!!,
                        trackers = currentStream.trackers,
                        saveDir = java.io.File(context.filesDir, "torrents").apply { mkdirs() },
                        fileIdx = fileIdx,
                    )
                }.getOrDefault(false)
                if (!ok) {
                    val key = currentStream.failKey()
                    failedStreamKeys.add(key)
                    if (isAutoAdaptive) {
                        val fallback = allStreams.bestFallbacks(
                            failed = failedStreamKeys,
                            torrentEngine = torrentEngine,
                            preferFallback = false,
                        )
                        if (fallback != null) {
                            hudNotification = "Source unavailable · Finding next best source…"
                            currentStream = fallback
                            return@LaunchedEffect
                        }
                    }
                    error = torrentEngine.state.value.error ?: "Could not load this torrent"
                    return@LaunchedEffect
                }
                // Only block for a short head-start. The local server streams pieces
                // on demand (it waits per piece), so ExoPlayer can begin its HTTP
                // handshake and decoder setup while the swarm is still delivering.
                // Previously this awaited the full prefetch budget, adding seconds of
                // black screen on every play.
                withContext(Dispatchers.IO) {
                    torrentEngine.awaitPrefetch(fileIdx, PREFETCH_BYTES, timeoutMs = PREFETCH_HEAD_START_MS)
                }
                // Hand the URL over even if the head pieces are still arriving: the
                // server blocks per piece, and ExoPlayer's own buffer covers the gap.
                // Bailing out here made a slightly slow swarm look like a dead source.
                val readyUrl = torrentEngine.urlFor(fileIdx)
                if (readyUrl == null && torrentEngine.metadataReady()) {
                    // Metadata exists, so the handle is valid and the server path is
                    // correct - give ExoPlayer the URL rather than failing the source.
                    hudNotification = "Buffering from the swarm…"
                }
                // Hand the URL to ExoPlayer even if prefetch is still filling, as long as server is ready.
                // Do NOT failover and hop streams when swarm is alive and downloading!
                val url = torrentEngine.urlFor(fileIdx) ?: run {
                    failedStreamKeys.add(currentStream.failKey())
                    null
                }
                if (url != null) {
                    resolvedUrl = url
                } else {
                    val key = currentStream.failKey()
                    failedStreamKeys.add(key)
                    if (isAutoAdaptive) {
                        val fallback = allStreams.bestFallbacks(
                            failed = failedStreamKeys,
                            torrentEngine = torrentEngine,
                            preferFallback = false,
                        )
                        if (fallback != null) {
                            hudNotification = "Connecting to alternative source…"
                            currentStream = fallback
                            return@LaunchedEffect
                        }
                    }
                    error = "Torrent file could not be served"
                }
            } else if (!currentStream.externalUrl.isNullOrBlank()) {
                runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        Uri.parse(currentStream.externalUrl),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                onEnded()
            } else {
                error = "Unsupported stream"
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                val key = currentStream.failKey()
                failedStreamKeys.add(key)
                if (isAutoAdaptive) {
                    val fallback = allStreams.bestFallbacks(
                        failed = failedStreamKeys,
                        torrentEngine = torrentEngine,
                        preferFallback = false,
                    )
                    if (fallback != null) {
                        hudNotification = "Connecting to alternative source…"
                        currentStream = fallback
                        return@LaunchedEffect
                    }
                }
                error = "Failed to resolve stream: ${e.message?.take(100)}"
                Log.e("PlayerScreen", "Stream resolution failed", e)
            }
        }
    }

    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("XTV/1.2 (Android TV)")
    }
    val dataSourceFactory = remember { DefaultDataSource.Factory(context, httpFactory) }
    // Pick the highest video bitrate the panel can decode and keep the movie's
    // original audio instead of downmixing it. Multichannel passthrough is left
    // to the audio sink so soundbars still get DD+/Atmos bitstreams.
    val trackSelector = remember {
        DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(null)
                    .setPreferredTextLanguage(null)
                    .setConstrainAudioChannelCountToDeviceCapabilities(false)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setForceHighestSupportedBitrate(true)
            )
        }
    }
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 8_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 500,   // first frame as soon as ~0.5s is ready
                /* bufferForPlaybackAfterRebufferMs = */ 1_200, // resume quickly but not thrash
            )
            // Allocation-based control starts playback sooner on constrained TV
            // hardware than waiting for a time target to fill.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
    val exoPlayer: ExoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            // Keep 32-bit float audio and per-track playback params so 5.1/7.1
            // soundtracks downmix cleanly and speed changes don't chipmunk.
            .setEnableAudioFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(true)
        val player = try {
            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(SEEK_STEP_MS)
                .setSeekForwardIncrementMs(SEEK_STEP_MS)
                .build()
                .apply {
                    playWhenReady = true
                    setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        /* handleAudioFocus = */ true,
                    )
                    setHandleAudioBecomingNoisy(true)
                }
        } catch (e: Exception) {
            Log.e("PlayerScreen", "ExoPlayer creation failed", e)
            error = "Player initialization failed: ${e.message?.take(100)}"
            // Return a dummy player that won't crash - error will be shown in UI
            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setTrackSelector(trackSelector)
                .build()
                .also { it.playWhenReady = false }
        }
        player
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                isPlaying = exoPlayer.isPlaying
                if (state == Player.STATE_ENDED) {
                    // Use maxPositionMs (not positionMs) because ExoPlayer resets currentPosition
                    // to 0 when state=ENDED, which would defeat the "already played" guard.
                    if (hasStartedPlaying && maxPositionMs > 5_000L) {
                        onEnded()
                    } else if (isAutoAdaptive) {
                        // Delay 3s before switching — ExoPlayer sometimes fires ENDED then
                        // immediately transitions to READY on its own (HTTP reconnect).
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (exoPlayer.playbackState != Player.STATE_ENDED) return@postDelayed
                            val key = currentStream.failKey()
                            failedStreamKeys.add(key)
                            val fallback = allStreams.bestFallbacks(
                                failed = failedStreamKeys,
                                torrentEngine = torrentEngine,
                                preferFallback = true,
                            )
                            if (fallback != null) {
                                val targetLabel = fallback.qualityLabel.ifEmpty { "alternative source" }
                                hudNotification = "Source stalled · Switching to $targetLabel…"
                                currentStream = fallback
                                retries = 0
                                error = null
                            } else {
                                error = "Could not stream this video · Please try another quality option"
                                isBuffering = false
                            }
                        }, 3_000L)
                    } else {
                        error = "Playback ended unexpectedly"
                        isBuffering = false
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(e: PlaybackException) {
                // For torrent streams: retry up to 3 times with backoff before switching source
                if (retries < 3 && !currentStream.infoHash.isNullOrBlank()) {
                    retries++
                    isBuffering = true
                    resolvedUrl?.let { url ->
                        // Capture the stream+retry identity so a late Runnable from an
                        // earlier stream can never prepare over the new one.
                        val gen = retryGeneration
                        val attempt = retries
                        val safePosition = maxPositionMs.coerceAtLeast(0)
                        val delayMs = when (attempt) { 1 -> 600L; 2 -> 1_500L; else -> 3_000L }
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (gen != retryGeneration) return@postDelayed
                            runCatching {
                                prepareNonce++
                                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)), safePosition)
                                exoPlayer.prepare()
                                exoPlayer.playWhenReady = true
                            }
                        }, delayMs)
                    }
                } else if (isAutoAdaptive) {
                    val key = currentStream.failKey()
                    failedStreamKeys.add(key)
                    val fallback = allStreams.bestFallbacks(
                        failed = failedStreamKeys,
                        torrentEngine = torrentEngine,
                        preferFallback = true,
                    )
                    if (fallback != null) {
                        savedPositionMs = exoPlayer.currentPosition.coerceAtLeast(maxPositionMs)
                        val targetLabel = fallback.qualityLabel.ifEmpty { "alternative source" }
                        hudNotification = "Source unavailable · Switching to $targetLabel"
                        currentStream = fallback
                        retries = 0
                        error = null
                    } else {
                        error = e.message?.take(180) ?: "Playback failed"
                        isBuffering = false
                    }
                } else {
                    error = e.message?.take(180) ?: "Playback failed"
                    isBuffering = false
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            onProgress(exoPlayer.currentPosition.coerceAtLeast(0))
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Track list + position ticker
    LaunchedEffect(Unit) {
        while (true) {
            tracks = exoPlayer.currentTracks
            val cur = exoPlayer.currentPosition.coerceAtLeast(0)
            positionMs = cur
            if (cur > maxPositionMs) maxPositionMs = cur
            val d = exoPlayer.duration
            durationMs = if (d > 0) d else 0L
            bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0)
            speed = exoPlayer.playbackParameters.speed
            delay(500)
        }
    }

    // Persist progress on a slow cadence; DataStore writes are not free.
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            if (exoPlayer.isPlaying) onProgress(exoPlayer.currentPosition)
        }
    }

    // Keyed on prepareNonce too: two torrents can resolve to the identical
    // "127.0.0.1:<port>/<fileIdx>" string, and a plain URL key would skip the
    // re-prepare, leaving the player stuck on the old stream.
    LaunchedEffect(resolvedUrl, prepareNonce) {
        resolvedUrl?.let {
            try {
                val targetPos = if (savedPositionMs > 0) savedPositionMs else startPositionMs
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(it)), targetPos)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            } catch (e: Exception) {
                Log.e("PlayerScreen", "Media preparation failed", e)
                error = "Playback failed: ${e.message?.take(100)}"
            }
        }
    }

    // Mark playback successfully active once position advances beyond 2.5s
    LaunchedEffect(isPlaying, positionMs) {
        if (isPlaying && positionMs > 2_500L && !hasStartedPlaying) {
            hasStartedPlaying = true
        }
    }

    // Auto-select container track matching preferred audio language if present
    LaunchedEffect(tracks, preferredAudioLanguage) {
        val lang = preferredAudioLanguage ?: return@LaunchedEffect
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val langTag = format.language.orEmpty()
                val label = format.label.orEmpty()
                val matches = langTag.startsWith(lang.take(2), ignoreCase = true) ||
                    langTag.equals(lang, ignoreCase = true) ||
                    label.contains(lang, ignoreCase = true)
                if (matches) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    break
                }
            }
        }
    }

    // Dynamic network downgrade:
    // ONLY runs if playback was ACTIVE for 10+ seconds and then hit a continuous stall >= 20s.
    // NEVER runs during initial startup. At most ONCE per title session (no thrashing!).
    LaunchedEffect(isBuffering, isAutoAdaptive, hasStartedPlaying, autoDowngraded, currentStream, resolvedUrl) {
        if (isBuffering && isAutoAdaptive && hasStartedPlaying && !autoDowngraded && resolvedUrl != null && maxPositionMs > 10_000L) {
            delay(20_000)  // wait 20s before giving up — torrent needs time to buffer more pieces
            if (isBuffering && isAutoAdaptive && hasStartedPlaying && !autoDowngraded && resolvedUrl != null && maxPositionMs > 10_000L) {
                val key = currentStream.failKey()
                failedStreamKeys.add(key)
                val fallback = allStreams.bestFallbacks(
                    failed = failedStreamKeys,
                    torrentEngine = torrentEngine,
                    preferFallback = true,
                )
                if (fallback != null) {
                    autoDowngraded = true
                    hasStartedPlaying = false
                    savedPositionMs = exoPlayer.currentPosition.coerceAtLeast(positionMs)
                    val targetLabel = fallback.qualityLabel.ifEmpty { "smoother stream" }
                    hudNotification = "Adapting quality for smooth playback · $targetLabel"
                    currentStream = fallback
                }
            }
        }
    }

    // Cold-start watchdog.
    //
    // The downgrade effect above can't help a title that never starts: it needs
    // hasStartedPlaying and 10s of elapsed position. But the worst case on this
    // TV is precisely that - a thin swarm leaves ExoPlayer in STATE_BUFFERING
    // forever without ever raising an error (it has a URL, it just gets no
    // bytes), so nothing failed and nothing switched. Measured: 1 seed, 0%,
    // ~40s of black screen before the swarm happened to recover.
    //
    // If a torrent hasn't produced meaningfully download progress in 12s, treat
    // it as dead and move to the next best source instead of waiting.
    LaunchedEffect(currentStream, resolvedUrl, hasStartedPlaying, isAutoAdaptive) {
        if (resolvedUrl == null || hasStartedPlaying || !isAutoAdaptive) return@LaunchedEffect
        if (currentStream.infoHash.isNullOrBlank()) return@LaunchedEffect
        val startedOn = currentStream.failKey()
        delay(12_000)
        // Still on the same source, still not playing, still no real throughput.
        //
        // Health must be judged by download rate, not by `progress`. Progress is the
        // completion of the whole torrent, so a 141-minute movie sits at well under
        // 1% for a long time even while streaming perfectly - checking it caused the
        // watchdog to abandon healthy sources. A live swarm delivers hundreds of
        // KB/s; a starved one delivers nothing.
        val rate = torrentEngine.state.value.downloadRate
        val progressed = rate > 60_000 || torrentEngine.state.value.progress > 0.02f
        if (progressed || !isBuffering) return@LaunchedEffect

        failedStreamKeys.add(startedOn)
        val fallback = allStreams.bestFallbacks(
            failed = failedStreamKeys,
            torrentEngine = torrentEngine,
            preferFallback = true,
        )
        if (fallback != null) {
            val targetLabel = fallback.qualityLabel.ifEmpty { "another source" }
            hudNotification = "This source is too slow · Switching to $targetLabel"
            currentStream = fallback
            retries = 0
            error = null
        }
    }

    // Undecodable-file watchdog.
    //
    // Distinct from the cold-start watchdog above, which only fires when the swarm
    // is starved. This one covers the opposite failure: the swarm is perfect but
    // the picture never appears. Measured on this TV with a 1080p 10-bit x265
    // release - 1.4 MB/s and 15 peers, yet the deck read 00:00 / --:-- forever
    // while MediaCodec logged a continuous "drop frame" loop. Duration stays
    // unknown because the container is parsed but no frame is ever produced.
    //
    // Symptom signature: torrent downloading healthily, no duration, no position
    // progress, still buffering. If that holds for 10s the file cannot be decoded
    // by this hardware, so stop retrying it and move to a source that can play.
    LaunchedEffect(currentStream, resolvedUrl, prepareNonce, hasStartedPlaying, isAutoAdaptive) {
        if (resolvedUrl == null || hasStartedPlaying || !isAutoAdaptive) return@LaunchedEffect
        if (currentStream.infoHash.isNullOrBlank()) return@LaunchedEffect
        val watched = currentStream.failKey()
        delay(10_000)
        // Only act if we are still on the same source and it still has not begun.
        if (currentStream.failKey() != watched || hasStartedPlaying) return@LaunchedEffect
        if (maxPositionMs > 1_000L || durationMs > 0L) return@LaunchedEffect
        if (!isBuffering) return@LaunchedEffect

        // The swarm must be healthy, otherwise the cold-start watchdog owns this.
        val engine = torrentEngine.state.value
        val swarmHealthy = engine.downloadRate > 200_000 || engine.peers >= 4
        if (!swarmHealthy) return@LaunchedEffect

        Log.w(
            "PlayerScreen",
            "undecodable source: no duration/frames after 10s despite ${engine.downloadRate / 1024}KB/s " +
                "and ${engine.peers} peers - switching",
        )
        failedStreamKeys.add(watched)
        val fallback = allStreams.bestFallbacks(
            failed = failedStreamKeys,
            torrentEngine = torrentEngine,
            preferFallback = true,
        )
        if (fallback != null) {
            val targetLabel = fallback.qualityLabel.ifEmpty { "another source" }
            hudNotification = "This file will not decode on this TV · Switching to $targetLabel"
            currentStream = fallback
            retries = 0
            error = null
        } else {
            error = "No source on this TV can decode · Try another title"
            isBuffering = false
        }
    }

    LaunchedEffect(hudNotification) {
        if (hudNotification != null) {
            delay(4_000)
            hudNotification = null
        }
    }

    // Auto-hide overlay after 5s when playing with no panel open.
    // The LaunchedEffect restarts whenever overlayVisible changes (key press sets it true → resets timer).
    LaunchedEffect(overlayVisible, panel, isPlaying, isBuffering, seekFocused) {
        if (overlayVisible && panel == Panel.None && isPlaying && !isBuffering && !seekFocused) {
            delay(5_000)
            // Re-check: only hide if still in the same state
            if (isPlaying && !isBuffering && panel == Panel.None) {
                overlayVisible = false
                seekFocused = true  // reset to seekbar row when overlay hides
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    BackHandler(enabled = panel != Panel.None || overlayVisible) {
        when {
            panel != Panel.None -> panel = Panel.None
            overlayVisible -> overlayVisible = false
        }
    }
    val internalAudioTracks = remember(tracks) { trackChoices(tracks, C.TRACK_TYPE_AUDIO, "Audio") }
    val availableAudios = remember(allStreams) { allStreams.availableAudioLanguages() }

    val audioChoices = remember(internalAudioTracks, availableAudios, currentStream, preferredAudioLanguage) {
        val list = mutableListOf<TrackChoice>()

        // 1. All available audio languages detected across content sources
        availableAudios.forEachIndexed { idx, lang ->
            val best = allStreams.findBestForAudio(lang)
            if (best != null) {
                val isCurrentStream = (best.infoHash != null && best.infoHash == currentStream.infoHash) ||
                    (best.url != null && best.url == currentStream.url)
                val isSelected = if (preferredAudioLanguage != null) {
                    lang.equals(preferredAudioLanguage, ignoreCase = true)
                } else {
                    isCurrentStream
                }
                val q = best.qualityLabel.ifEmpty { "HD" }
                val detailParts = listOfNotNull(
                    if (isCurrentStream) "Active source" else "Best quality source",
                    q,
                    best.sizeLabel,
                    if (best.seeders > 0) "${best.seeders} seeds" else null,
                )
                list += TrackChoice(
                    label = lang,
                    detail = detailParts.joinToString(" · "),
                    group = null,
                    index = idx,
                    selected = isSelected,
                    targetStream = best,
                    targetLanguage = lang,
                )
            }
        }

        // 2. Any internal audio tracks detected in the currently playing file
        if (internalAudioTracks.size > 1) {
            internalAudioTracks.forEach { t ->
                list += t
            }
        }

        // Fallback to internal tracks if no source-level audio tags
        if (list.isEmpty()) {
            list.addAll(internalAudioTracks)
        }
        list
    }
    val subtitleChoices = remember(tracks) {
        val hasSelected = tracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT &&
                (0 until group.length).any { i -> runCatching { group.isTrackSelected(i) }.getOrDefault(false) }
        }
        listOf(
            TrackChoice(
                label = "Off",
                detail = "Hide subtitles",
                group = null,
                index = 0,
                selected = !hasSelected,
            )
        ) + trackChoices(tracks, C.TRACK_TYPE_TEXT, "Subtitle")
    }

    val qualityChoices = remember(allStreams, currentStream, isAutoAdaptive) {
        val list = mutableListOf<TrackChoice>()
        list += TrackChoice(
            label = "Auto (Adaptive)",
            detail = "Recommended · Adapts quality to network speed & TV",
            group = null,
            index = 0,
            selected = isAutoAdaptive,
        )
        val displayCandidates = if (allStreams.isNotEmpty()) allStreams else listOf(currentStream)
        displayCandidates.forEachIndexed { idx, s ->
            val q = s.qualityLabel.ifEmpty { "HD" }
            val detailParts = listOfNotNull(
                s.sizeLabel,
                if (s.seeders > 0) "${s.seeders} seeds" else null,
                s.name?.take(30) ?: s.title?.substringBefore("\n")?.take(30),
            )
            val isThisSelected = !isAutoAdaptive && (
                (s.infoHash != null && s.infoHash == currentStream.infoHash) ||
                (s.url != null && s.url == currentStream.url)
            )
            list += TrackChoice(
                label = "$q ${if (idx == 0) "(Best)" else ""}",
                detail = detailParts.joinToString(" · ").takeIf { it.isNotBlank() },
                group = null,
                index = idx + 1,
                selected = isThisSelected,
            )
        }
        list
    }

    fun chooseAudio(index: Int) {
        val choice = audioChoices.getOrNull(index) ?: return

        // Case 1: Internal track in active container
        if (choice.group != null) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
                .build()
            hudNotification = "Audio: ${choice.label}"
            return
        }

        // Case 2: Language selected from content sources -> Auto-select best quality & performance stream
        val target = choice.targetStream
        val lang = choice.targetLanguage
        if (target != null) {
            preferredAudioLanguage = lang
            val isSame = (target.infoHash != null && target.infoHash == currentStream.infoHash) ||
                (target.url != null && target.url == currentStream.url)
            if (isSame) {
                hudNotification = "Already playing best source for ${lang ?: "this audio"}"
                return
            }

            savedPositionMs = exoPlayer.currentPosition.coerceAtLeast(positionMs)
            isAutoAdaptive = false
            autoDowngraded = false
            hasStartedPlaying = false
            val q = target.qualityLabel.ifEmpty { "HD" }
            val seeds = if (target.seeders > 0) " · ${target.seeders} seeds" else ""
            hudNotification = "Switching to ${lang ?: "selected"} audio · $q$seeds"
            currentStream = target
        }
    }

    fun chooseSubtitle(index: Int) {
        val choice = subtitleChoices.getOrNull(index) ?: return
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (choice.group == null) {
            exoPlayer.trackSelectionParameters = builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        exoPlayer.trackSelectionParameters = builder
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
            .build()
    }

    fun chooseQuality(index: Int) {
        val displayCandidates = if (allStreams.isNotEmpty()) allStreams else listOf(currentStream)
        if (index == 0) {
            isAutoAdaptive = true
            autoDowngraded = false
            hasStartedPlaying = false
            hudNotification = "Quality: Auto (Adaptive)"
            val best = displayCandidates.firstOrNull()
            if (best != null && (best.infoHash != currentStream.infoHash || best.url != currentStream.url)) {
                savedPositionMs = exoPlayer.currentPosition.coerceAtLeast(positionMs)
                currentStream = best
            }
        } else {
            val chosen = displayCandidates.getOrNull(index - 1)
            if (chosen != null) {
                isAutoAdaptive = false
                autoDowngraded = true
                hasStartedPlaying = false
                val isSame = (chosen.infoHash != null && chosen.infoHash == currentStream.infoHash) ||
                    (chosen.url != null && chosen.url == currentStream.url)
                if (isSame) {
                    hudNotification = "Quality locked to ${chosen.qualityLabel.ifEmpty { "current source" }}"
                    return
                }
                savedPositionMs = exoPlayer.currentPosition.coerceAtLeast(positionMs)
                val q = chosen.qualityLabel.ifEmpty { "selected source" }
                val seeds = if (chosen.seeders > 0) " · ${chosen.seeders} seeds" else ""
                hudNotification = "Switching quality to $q$seeds"
                currentStream = chosen
            }
        }
    }

    fun activateControl(control: Control) {
        when (control) {
            Control.Rewind -> seekBy(exoPlayer, -SEEK_STEP_MS)
            Control.Forward -> seekBy(exoPlayer, SEEK_STEP_MS)
            Control.PlayPause -> {
                if (exoPlayer.isPlaying) exoPlayer.pause() else {
                    if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
                    exoPlayer.play()
                }
            }
            Control.Audio -> { panel = Panel.Audio; panelIndex = audioChoices.indexOfFirst { it.selected }.coerceAtLeast(0) }
            Control.Subtitles -> { panel = Panel.Subtitles; panelIndex = subtitleChoices.indexOfFirst { it.selected }.coerceAtLeast(0) }
            Control.Quality -> { panel = Panel.Quality; panelIndex = qualityChoices.indexOfFirst { it.selected }.coerceAtLeast(0) }
            Control.Speed -> { panel = Panel.Speed; panelIndex = SPEEDS.indexOfFirst { it == speed }.coerceAtLeast(0) }
            Control.Screen -> aspectIndex = (aspectIndex + 1) % ASPECT_MODES.size
        }
        overlayVisible = true
    }

    fun panelItems(): List<TrackChoice> = when (panel) {
        Panel.Audio -> audioChoices
        Panel.Subtitles -> subtitleChoices
        Panel.Quality -> qualityChoices
        Panel.Speed -> SPEEDS.mapIndexed { i, s ->
            TrackChoice("${s}×", if (s == 1f) "Normal" else null, null, i, s == speed)
        }
        Panel.None -> emptyList()
    }

    val loadingProgress: Float = remember(
        preparing,
        isBuffering,
        engineState.ready,
        engineState.peers,
        engineState.bufferProgress,
        resolvedUrl,
        bufferedMs,
        positionMs,
    ) {
        if (!preparing && !isBuffering) {
            1.0f
        } else if (isTorrent && !engineState.ready) {
            // Peer discovery & metadata phase: 6% to 40% based on connected peers
            (0.06f + (engineState.peers.toFloat() / 15f).coerceAtMost(1f) * 0.34f)
        } else if (isTorrent && resolvedUrl == null) {
            // Piece 0 / prefetch buffer phase: 40% to 80%
            0.40f + (engineState.bufferProgress * 0.40f)
        } else {
            // Initial media decode / rebuffering phase: 80% to 98%
            if (bufferedMs > positionMs) {
                val delta = bufferedMs - positionMs
                val pct = (delta.toFloat() / 2000f).coerceIn(0f, 1f)
                0.80f + (pct * 0.18f)
            } else {
                0.82f
            }
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = loadingProgress.coerceIn(0.06f, 1f),
        animationSpec = tween(280, easing = LinearOutSlowInEasing),
        label = "loadingProgress",
    )

    ResponsiveScope {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Every key press resets the auto-hide timer
                // Back/Escape is handled specially below and does NOT set overlayVisible = true
                val isBackKey = ev.key == Key.Back || ev.key == Key.Escape
                if (!isBackKey) overlayVisible = true
                val items = panelItems()
                if (panel != Panel.None) {
                    // ── Panel is open: Up/Down navigate, OK selects, Back/Left closes ──
                    when (ev.key) {
                        Key.DirectionUp -> {
                            panelIndex = (panelIndex - 1).coerceAtLeast(0); true
                        }
                        Key.DirectionDown -> {
                            panelIndex = (panelIndex + 1).coerceAtMost(items.lastIndex.coerceAtLeast(0)); true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            when (panel) {
                                Panel.Audio -> chooseAudio(panelIndex)
                                Panel.Subtitles -> chooseSubtitle(panelIndex)
                                Panel.Quality -> chooseQuality(panelIndex)
                                Panel.Speed -> {
                                    val s = SPEEDS.getOrNull(panelIndex) ?: 1f
                                    exoPlayer.setPlaybackSpeed(s)
                                    speed = s
                                }
                                Panel.None -> Unit
                            }
                            panel = Panel.None
                            true
                        }
                        Key.DirectionLeft, Key.Back, Key.Escape -> {
                            panel = Panel.None; true
                        }
                        else -> true  // consume all keys while panel open
                    }
                } else {
                    // ── Normal playback mode ──
                    // seekFocused=true  → bottom seek bar row
                    // seekFocused=false → control button row
                    when (ev.key) {
                        Key.DirectionUp -> {
                            // Move focus from seekbar UP to control row
                            if (seekFocused) seekFocused = false
                            true
                        }
                        Key.DirectionDown -> {
                            // Move focus from control row DOWN to seekbar
                            if (!seekFocused) seekFocused = true
                            true
                        }
                        Key.DirectionLeft -> {
                            if (!seekFocused) {
                                // Navigate left through control buttons
                                controlIndex = (controlIndex - 1).coerceAtLeast(0)
                            } else {
                                // Seek backward
                                seekBy(exoPlayer, -SEEK_STEP_MS)
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (!seekFocused) {
                                // Navigate right through control buttons
                                controlIndex = (controlIndex + 1).coerceAtMost(Control.entries.lastIndex)
                            } else {
                                // Seek forward
                                seekBy(exoPlayer, SEEK_STEP_MS)
                            }
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (!seekFocused) {
                                // Activate the highlighted control button
                                activateControl(Control.entries[controlIndex])
                            } else {
                                // OK on seekbar = toggle play/pause
                                activateControl(Control.PlayPause)
                            }
                            true
                        }
                        Key.MediaPlayPause -> { activateControl(Control.PlayPause); true }
                        Key.MediaRewind, Key.MediaStepBackward -> { seekBy(exoPlayer, -SEEK_STEP_MS); true }
                        Key.MediaFastForward, Key.MediaStepForward -> { seekBy(exoPlayer, SEEK_STEP_MS); true }
                        Key.Back, Key.Escape -> {
                            // overlayVisible was NOT set to true for Back key (see above)
                            // If overlay is currently visible, hide it and consume; else let BackHandler exit
                            if (overlayVisible) {
                                overlayVisible = false
                                true
                            } else {
                                false  // let BackHandler handle exit
                            }
                        }
                        else -> false
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = ASPECT_MODES[aspectIndex].first
                }
            },
            update = { view ->
                view.resizeMode = ASPECT_MODES[aspectIndex].first
            },
            // On a phone there is no key event to reveal the deck, so tapping the
            // picture is the only way in. On a TV this is harmless: the remote
            // still drives everything and the tap handler simply never fires.
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (panel != Panel.None) {
                                panel = Panel.None
                            } else if (!overlayVisible) {
                                overlayVisible = true
                            } else {
                                // Second tap on the picture toggles playback, which
                                // is the convention on every mobile player.
                                activateControl(Control.PlayPause)
                            }
                        },
                    )
                },
        )

        // Title + status, top left
        AnimatedVisibility(
            visible = overlayVisible && !preparing && error == null,
            enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            val m = uiMetrics()
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
                    )
                    .padding(horizontal = m.d(20.dp), vertical = m.d(11.dp)),
            ) {
                Text(
                    title ?: currentStream.displayName,
                    color = OnBg,
                    fontSize = m.f(15f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val format = tracks.videoFormat()
                val audio = tracks.selectedAudioFormat()
                if (format != null || audio != null || isTorrent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        videoBadge(format, audio)?.let { label ->
                            Text(
                                label,
                                color = Accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            // Download throughput is the meaningful health figure
                            // here. Torrent "progress" is completion of the whole
                            // file, so a feature that is streaming perfectly still
                            // reads 0% - which looked like a fault on screen.
                            if (isTorrent) {
                                val mbps = engineState.downloadRate / 1024f / 1024f
                                "%.1f MB/s  /  %d peers".format(mbps, engineState.peers)
                            } else {
                                "Screen: ${ASPECT_MODES[aspectIndex].second}"
                            },
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = (preparing || isBuffering) && error == null,
            enter = fadeIn(animationSpec = tween(250, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(250, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            DigiXProgressLogo(progress = animatedProgress)
        }

        AnimatedVisibility(
            visible = !isPlaying && !isBuffering && error == null && !preparing,
            enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { exoPlayer.play() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }

        // Bottom control deck - fully transparent, viewport-adaptive.
        AnimatedVisibility(
            visible = overlayVisible && !preparing && error == null,
            enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val m = uiMetrics()
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x59000000), Color(0x8C000000))
                        )
                    )
                    .padding(
                        start = m.d(20.dp),
                        end = m.d(20.dp),
                        top = m.d(9.dp),
                        bottom = m.d(7.dp),
                    ),
            ) {
                // Timeline row: elapsed on the left, remaining on the right, and
                // the scrubber taking whatever space is left. Previously the
                // clock was appended after eight fixed-width buttons, which is
                // why it was clipped off the edge on narrower panels.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        clock(positionMs),
                        color = OnBg,
                        fontSize = m.f(10.5f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Box(Modifier.weight(1f).padding(horizontal = m.d(9.dp))) {
                        SeekBar(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            bufferedMs = bufferedMs,
                            focused = seekFocused,
                            onSeek = { targetMs ->
                                exoPlayer.seekTo(targetMs)
                                positionMs = targetMs
                            },
                            onSeekStart = { seekFocused = true; overlayVisible = true },
                            onSeekEnd = { seekFocused = false },
                        )
                    }
                    Text(
                        if (durationMs > 0) clock(durationMs) else "--:--",
                        color = OnBg.copy(alpha = 0.62f),
                        fontSize = m.f(10.5f),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.height(m.d(6.dp)))
                //
                // Two clusters, pushed to opposite ends: transport on the left
                // (where the eye starts), settings on the right.
                //
                // The settings used to be a FlowRow, which wrapped onto a second
                // and third line once the panel narrowed — the deck then covered
                // a large slice of the picture, which is the opposite of the
                // point. They are one horizontally scrollable line instead, so the
                // deck keeps a fixed, short height on every device.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Transport cluster: rewind, play, forward. Icon-only circles
                    // read instantly and cost a fraction of the old three-line cards.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(m.d(6.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransportButton(
                            icon = Icons.Filled.Replay10,
                            label = "Back 10 seconds",
                            selected = overlayVisible && !seekFocused && controlIndex == Control.Rewind.ordinal,
                            onClick = {
                                seekFocused = false
                                controlIndex = Control.Rewind.ordinal
                                activateControl(Control.Rewind)
                            },
                        )
                        TransportButton(
                            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            label = if (isPlaying) "Pause" else "Play",
                            primary = true,
                            selected = overlayVisible && !seekFocused && controlIndex == Control.PlayPause.ordinal,
                            onClick = {
                                seekFocused = false
                                controlIndex = Control.PlayPause.ordinal
                                activateControl(Control.PlayPause)
                            },
                        )
                        TransportButton(
                            icon = Icons.Filled.Forward10,
                            label = "Forward 10 seconds",
                            selected = overlayVisible && !seekFocused && controlIndex == Control.Forward.ordinal,
                            onClick = {
                                seekFocused = false
                                controlIndex = Control.Forward.ordinal
                                activateControl(Control.Forward)
                            },
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Settings cluster: one pill per setting, showing its current
                    // value in plain words ("Audio English", "Quality 1080p").
                    // Single scrollable line, so it can never grow the deck's height.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(m.d(6.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        DeckPill(
                            icon = Icons.Filled.VolumeUp,
                            label = "Audio",
                            value = shortAudio(audioChoices, preferredAudioLanguage),
                            index = Control.Audio.ordinal,
                            controlIndex = controlIndex,
                            active = overlayVisible && !seekFocused,
                            onClick = {
                                seekFocused = false
                                controlIndex = Control.Audio.ordinal
                                activateControl(Control.Audio)
                            },
                        )
                        DeckPill(
                            icon = Icons.Filled.Subtitles,
                            label = "Subtitles",
                            value = shortSubtitle(subtitleChoices),
                            index = Control.Subtitles.ordinal,
                            controlIndex = controlIndex,
                            active = overlayVisible && !seekFocused,
                            onClick = {
                                seekFocused = false
                                controlIndex = Control.Subtitles.ordinal
                                activateControl(Control.Subtitles)
                            },
                        )
                        DeckPill(
                            icon = Icons.Filled.HighQuality,
                            label = "Quality",
                            value = shortQuality(currentStream, tracks),
                            index = Control.Quality.ordinal,
                            controlIndex = controlIndex,
                            active = overlayVisible && !seekFocused,
                            onClick = {
                                seekFocused = false
                                controlIndex = Control.Quality.ordinal
                                activateControl(Control.Quality)
                            },
                        )
                        DeckPill(
                            icon = androidx.compose.material.icons.Icons.Filled.Speed,
                            label = "Speed",
                            value = "${trimSpeed(speed)}x",
                            index = Control.Speed.ordinal,
                            controlIndex = controlIndex,
                            active = overlayVisible && !seekFocused,
                            onClick = {
                                seekFocused = false
                                controlIndex = Control.Speed.ordinal
                                activateControl(Control.Speed)
                            },
                        )
                        DeckPill(
                            icon = Icons.Filled.AspectRatio,
                            label = "Screen",
                            value = ASPECT_MODES[aspectIndex].second,
                            index = Control.Screen.ordinal,
                            controlIndex = controlIndex,
                            active = overlayVisible && !seekFocused,
                            onClick = {
                                seekFocused = false
                                controlIndex = Control.Screen.ordinal
                                activateControl(Control.Screen)
                            },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = panel != Panel.None,
            enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = uiMetrics().d(24.dp)),
        ) {
            OptionsPanel(
                title = when (panel) {
                    Panel.Audio -> "Audio Languages & Tracks"
                    Panel.Subtitles -> "Subtitles"
                    Panel.Quality -> "Stream Quality"
                    Panel.Speed -> "Playback speed"
                    Panel.None -> ""
                },
                items = panelItems(),
                selectedIndex = panelIndex,
            )
        }

        // Floating HUD Notification Banner (Top Center)
        AnimatedVisibility(
            visible = hudNotification != null,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(400)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 26.dp),
        ) {
            hudNotification?.let { msg ->
                val hm = uiMetrics()
                Box(
                    Modifier
                        .clip(RoundedCornerShape(hm.d(20.dp)))
                        .background(Color(0xEE141414))
                        .padding(horizontal = hm.d(18.dp), vertical = hm.d(9.dp)),
                ) {
                    Text(
                        msg,
                        color = Color.White,
                        fontSize = hm.f(12f),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)),
        ) {
            error?.let {
                val em = uiMetrics()
                Box(
                    Modifier.fillMaxSize().background(Color(0xF2000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = em.d(400.dp))
                            .fillMaxWidth(0.86f)
                            .padding(horizontal = em.d(22.dp), vertical = em.d(18.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(it, color = OnBg, fontSize = em.f(15f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(em.d(14.dp)))
                        Text("Press Back to return", color = Muted, fontSize = em.f(11.5f))
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    focused: Boolean,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    var dragging by remember { mutableStateOf(false) }
    val dragProgress = remember { mutableStateOf(progress) }
    var widthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxWidth()
            .height(if (focused || dragging) 10.dp else 5.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color.White.copy(alpha = if (focused || dragging) 0.32f else 0.20f))
            .onGloballyPositioned { coords ->
                widthPx = coords.size.width.toFloat()
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragProgress.value = progress
                        onSeekStart()
                    },
                    onDrag = { change, _ ->
                        change.consumeAllChanges()
                        val dx = change.positionChange().x
                        val newProgress = (dragProgress.value + dx / widthPx).coerceIn(0f, 1f)
                        dragProgress.value = newProgress
                    },
                    onDragEnd = {
                        dragging = false
                        val targetMs = (dragProgress.value * durationMs).toLong()
                        onSeek(targetMs)
                        onSeekEnd()
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val tapProgress = (offset.x / widthPx).coerceIn(0f, 1f)
                        val targetMs = (tapProgress * durationMs).toLong()
                        onSeek(targetMs)
                    }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth(buffered)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.30f)),
        )
        Box(
            Modifier
                .fillMaxWidth(if (dragging) dragProgress.value else progress)
                .fillMaxHeight()
                .clip(RoundedCornerShape(7.dp))
                .background(Accent),
        )
        // Thumb indicator when dragging
        if (dragging) {
            val thumbOffset = with(density) { (dragProgress.value * widthPx - 7.dp.toPx()) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(androidx.compose.ui.Alignment.CenterStart)
                    .graphicsLayer { translationX = thumbOffset },
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                )
            }
        }
    }
}

/**
 * Digi X brand loading indicator:
 * - Liquid vertical fill: the DX mark fills from bottom to top as percent progress.
 * - Clean percentage progress only (no technical clutter, peers, or speed info).
 */
@Composable
private fun DigiXProgressLogo(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val fillFraction = progress.coerceIn(0f, 1f)
    val pct = (fillFraction * 100).toInt().coerceIn(1, 100)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(108.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 1. Dim translucent base silhouette of the X logo
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Loading",
                tint = Color(0x35FFFFFF),
                modifier = Modifier.fillMaxSize(),
            )

            // 2. Liquid vertical fill: clips the mark from bottom to top
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(
                            left = 0f,
                            top = size.height * (1f - fillFraction),
                            right = size.width,
                            bottom = size.height,
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified, // Keeps the DX mark's own tones
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Clean percentage progress - no detailed technical clutter
        Text(
            text = "$pct%",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ControlChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // Accent-filled surfaces draw black glyphs when the accent is light.
    val m = uiMetrics()
    val bg = if (selected) {
        Accent
    } else {
        Color(0x44FFFFFF)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(m.d(18.dp)))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = m.d(11.dp), vertical = m.d(5.dp)),
    ) {
        Text(
            label,
            color = if (selected) OnAccent else Color.White,
            fontSize = m.f(11f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OptionsPanel(
    title: String,
    items: List<TrackChoice>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        if (selectedIndex in 0..items.lastIndex) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    val m = uiMetrics()
    Column(
        modifier
            // A fixed 380dp panel hangs off the edge of a phone in portrait.
            .widthIn(max = m.d(380.dp))
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(m.d(18.dp)))
            .background(Color(0xF5141414))
            .padding(vertical = m.d(16.dp)),
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = m.f(15f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = m.d(18.dp), vertical = m.d(6.dp)),
        )
        Spacer(Modifier.height(m.d(5.dp)))
        if (items.isEmpty()) {
            Text(
                "Nothing available for this source",
                color = Muted,
                fontSize = m.f(12f),
                modifier = Modifier.padding(horizontal = m.d(18.dp), vertical = m.d(9.dp)),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = m.d(420.dp)),
        ) {
            itemsIndexed(items) { index, item ->
                val selected = index == selectedIndex
                val rowModifier = if (selected) {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = m.d(11.dp), vertical = m.d(3.dp))
                        .clip(RoundedCornerShape(m.d(11.dp)))
                        .background(Brush.horizontalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF))))
                        .padding(horizontal = m.d(13.dp), vertical = m.d(9.dp))
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = m.d(11.dp), vertical = m.d(3.dp))
                        .clip(RoundedCornerShape(m.d(11.dp)))
                        .padding(horizontal = m.d(13.dp), vertical = m.d(9.dp))
                }

                Row(
                    modifier = rowModifier,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.label,
                            color = if (selected) Color.White else Color(0xFFDDDDDD),
                            fontSize = m.f(13f),
                            fontWeight = if (selected || item.selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.detail?.let {
                            Text(it, color = if (selected) Color(0xFFCCCCCC) else Muted, fontSize = m.f(10.5f), maxLines = 1)
                        }
                    }
                    if (item.selected || selected) {
                        Icon(Icons.Filled.Check, contentDescription = "Selected", tint = BrandAccent, modifier = Modifier.size(m.d(13.dp)))
                    }
                }
            }
        }
    }
}

private fun seekBy(player: Player, deltaMs: Long) {
    val target = (player.currentPosition + deltaMs).coerceIn(0, (player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
    player.seekTo(target)
}

private fun trackChoices(tracks: Tracks, type: Int, fallback: String): List<TrackChoice> {
    val out = mutableListOf<TrackChoice>()
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (i in 0 until group.length) {
            if (!runCatching { group.isTrackSupported(i) }.getOrDefault(true)) continue
            val format = group.getTrackFormat(i)
            val label = format.label?.takeIf { it.isNotBlank() }
                ?: format.language?.uppercase()
                ?: "$fallback ${out.size + 1}"
            val detail = listOfNotNull(
                format.language?.let { langName(it) },
                format.channelCount.takeIf { type == C.TRACK_TYPE_AUDIO && it > 0 }?.let { "${it} ch" },
                format.codecs?.split('.')?.firstOrNull()?.uppercase(),
            ).distinct().joinToString(" · ").takeIf { it.isNotBlank() }
            out += TrackChoice(
                label = label,
                detail = detail,
                group = group.mediaTrackGroup,
                index = i,
                selected = runCatching { group.isTrackSelected(i) }.getOrDefault(false),
            )
        }
    }
    return out
}

/**
 * Round transport control: rewind / play / forward.
 *
 * Icon only by design. These three actions are universally understood as vectors,
 * so a text label underneath only added height. A circular hit area also reads
 * more like a premium player than a rectangular card.
 */
@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    selected: Boolean = false,
) {
    val m = uiMetrics()
    var focused by remember { mutableStateOf(false) }
    val diameter = m.d(if (primary) 37.dp else 30.dp)
    val lit = focused || selected
    val scale by animateFloatAsState(
        targetValue = if (lit) 1.08f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "transportScale$label",
    )
    val wash by animateFloatAsState(
        targetValue = if (lit) 0.24f else if (primary) 0.13f else 0.07f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "transportWash$label",
    )
    val ring by animateFloatAsState(
        targetValue = if (lit) 0.5f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "transportRing$label",
    )

    Box(
        modifier = Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .size(diameter)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = wash))
            .border(m.d(1.dp), Color.White.copy(alpha = ring), CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(m.d(if (primary) 19.dp else 16.dp)),
        )
    }
}

/**
 * One-line settings pill: an icon followed by the current value in plain words.
 *
 * "Audio English", "Quality 1080p", "Speed 1.0x" - the value is what the viewer
 * actually wants to know, so the setting's name is carried by the icon and the
 * accessible description rather than by a third line of text.
 */
@Composable
private fun DeckPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    index: Int?,
    controlIndex: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    val m = uiMetrics()
    val selected = active && index != null && controlIndex == index
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "pillScale$label",
    )
    val wash by animateFloatAsState(
        targetValue = if (selected) 0.18f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "pillWash$label",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "pillFg$label",
    )

    Row(
        modifier = Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = wash))
            .border(
                m.d(1.dp),
                Color.White.copy(alpha = if (selected) 0.45f else 0f),
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = m.d(8.dp), vertical = m.d(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = fg.copy(alpha = 0.75f),
            modifier = Modifier.size(m.d(13.dp)),
        )
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(m.d(4.dp)))
            Text(
                text = value,
                color = fg,
                fontSize = m.f(10f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun audioLabel(choices: List<TrackChoice>): String =
    choices.firstOrNull { it.selected }?.label ?: "Default"

private fun subtitleLabel(choices: List<TrackChoice>): String =
    choices.firstOrNull { it.selected }?.label ?: "Off"

/** Compact current-audio name for the control deck ("English", "Hindi", "Default"). */
private fun shortAudio(choices: List<TrackChoice>, preferred: String?): String {
    val picked = choices.firstOrNull { it.selected }?.label
        ?: preferred?.let { langName(it) }
        ?: "Default"
    return picked.take(12)
}

/** Compact subtitle state ("Off", "English"). */
private fun shortSubtitle(choices: List<TrackChoice>): String {
    val picked = choices.firstOrNull { it.selected }?.label ?: return "Off"
    return picked.take(12)
}

/** Live resolution from the decoder when available, else the stream's own label. */
private fun shortQuality(stream: Stream, tracks: Tracks): String {
    val height = tracks.videoFormat()?.height ?: 0
    return when {
        height >= 2000 -> "4K"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        height > 0 -> "${height}p"
        else -> stream.qualityLabel.ifEmpty { "Auto" }.take(10)
    }
}

private fun Tracks.videoFormat(): androidx.media3.common.Format? {
    val group = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return null
    for (i in 0 until group.length) {
        if (runCatching { group.isTrackSelected(i) }.getOrDefault(false)) {
            return group.getTrackFormat(i)
        }
    }
    return null
}

private fun Tracks.selectedAudioFormat(): androidx.media3.common.Format? {
    val group = groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
    for (i in 0 until group.length) {
        if (runCatching { group.isTrackSelected(i) }.getOrDefault(false)) {
            return group.getTrackFormat(i)
        }
    }
    return null
}

/** "4K · DD+ · 5.1" — the badge that tells you the panel is getting the good stuff. */
private fun videoBadge(
    video: androidx.media3.common.Format?,
    audio: androidx.media3.common.Format?,
): String? {
    val height = video?.height ?: 0
    val res = when {
        height >= 2000 -> "4K"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        height > 0 -> "${height}p"
        else -> null
    }
    val codecName = audio?.codecs?.uppercase()?.let { codec ->
        when {
            codec.contains("EAC3") || codec.contains("EC-3") -> "DD+"
            codec.contains("AC3") || codec.contains("AC-3") -> "DD"
            codec.contains("TRUEHD") -> "TrueHD"
            codec.contains("DTS") -> "DTS"
            codec.contains("FLAC") -> "FLAC"
            codec.contains("OPUS") -> "Opus"
            codec.contains("AAC") -> "AAC"
            else -> null
        }
    }
    val channels = audio?.channelCount?.takeIf { it > 2 }?.let { n ->
        when (n) {
            6 -> "5.1"
            8 -> "7.1"
            else -> "${n}ch"
        }
    }
    return listOfNotNull(res, codecName, channels).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun trimSpeed(value: Float): String =
    if (value == value.toInt().toFloat()) "${value.toInt()}.0" else value.toString()

private fun langName(code: String): String = when (code.lowercase()) {
    "en", "eng" -> "English"
    "hi", "hin" -> "Hindi"
    "ta", "tam" -> "Tamil"
    "te", "tel" -> "Telugu"
    "ml", "mal" -> "Malayalam"
    "kn", "kan" -> "Kannada"
    "bn", "ben" -> "Bengali"
    "mr", "mar" -> "Marathi"
    "pa", "pan" -> "Punjabi"
    "gu", "guj" -> "Gujarati"
    "es", "spa" -> "Spanish"
    "fr", "fre", "fra" -> "French"
    "de", "ger", "deu" -> "German"
    "it", "ita" -> "Italian"
    "ja", "jpn" -> "Japanese"
    "ko", "kor" -> "Korean"
    "zh", "chi", "zho" -> "Chinese"
    "ru", "rus" -> "Russian"
    "ar", "ara" -> "Arabic"
    "pt", "por" -> "Portuguese"
    "tr", "tur" -> "Turkish"
    else -> code.uppercase()
}

/**
 * Format a media position/duration as `H:MM:SS` or `MM:SS`.
 *
 * The hours component must be included: the old version computed minutes as
 * `total % 3600`, which silently dropped whole hours and rendered a 2h07m film
 * as "06:07" - a runtime that looked plainly wrong.
 */
private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * Minimal transparent text button — iPhone camera style.
 * No background chip, just the text label with subtle focus scaling.
 */
@Composable
private fun IconButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "iconScale$label",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) Color.White else Color(0xFF888888),
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "iconTint$label",
    )

    Column(
        modifier = Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
