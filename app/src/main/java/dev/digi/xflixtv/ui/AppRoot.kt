package dev.digi.xflixtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.digi.xflixtv.XflixTvApp
import dev.digi.xflixtv.data.Stream
import dev.digi.xflixtv.ui.player.PlayerScreen
import dev.digi.xflixtv.ui.screens.AddonsScreen
import dev.digi.xflixtv.ui.screens.DetailScreen
import dev.digi.xflixtv.ui.screens.HomeScreen
import dev.digi.xflixtv.ui.screens.LibraryScreen
import dev.digi.xflixtv.ui.screens.SearchScreen
import dev.digi.xflixtv.ui.screens.SettingsScreen
import dev.digi.xflixtv.ui.theme.*
import dev.digi.xflixtv.ui.theme.ResponsiveScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Search("Search", Icons.Default.Search),
    Home("Home", Icons.Default.Home),
    Library("My List", Icons.Default.Bookmark),
    Addons("Addons", Icons.Default.Apps),
    Settings("Settings", Icons.Default.Settings),
}

/**
 * Soft vertical scrim behind the floating chrome.
 *
 * Kept deliberately faint: the tab row should read as a translucent layer over
 * the page, not as a dark band. Content scrolls underneath and stays visible.
 */
private val HeaderScrim = Brush.verticalGradient(
    0f to Color(0x8C0E0E0E),
    0.5f to Color(0x3D0E0E0E),
    1f to Color.Transparent,
)

/** tvOS top bar: wordmark on the left, quiet clock on the right, no heavy chrome. */
@Composable
private fun AppleTopBar() {
    val m = uiMetrics()
    // Tick once a minute. The previous `remember { LocalDateTime.now() }` evaluated
    // exactly once at composition, so the clock froze at whatever time the app was
    // opened and showed a stale time for the whole session.
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        while (true) {
            time = java.time.LocalTime.now().format(fmt)
            // Sleep until the top of the next minute so the display never lags
            // behind the wall clock by more than a few milliseconds.
            val now = java.time.LocalTime.now()
            delay(((60 - now.second) * 1000L - now.nano / 1_000_000L + 50L).coerceAtLeast(500L))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = m.d(TvDimens.contentStart), end = m.d(TvDimens.contentStart), top = m.d(18.dp), bottom = m.d(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "XFLIX",
            color = Color.White,
            fontSize = m.f(15f),
            fontWeight = FontWeight.Black,
            letterSpacing = m.f(3.5f),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = time,
            color = Color.White.copy(alpha = 0.42f),
            fontSize = m.f(12f),
            fontWeight = FontWeight.Medium,
            letterSpacing = m.f(0.8f),
        )
    }
}

/**
 * One tvOS tab: a plain text label on a barely-there translucent pill.
 *
 * Focus lifts it a hair (1.04x) and brightens the label; selection keeps the
 * pill but only at 10% white so the bar stays see-through. Reports its bounds
 * so the shared indicator can glide to it.
 */
@Composable
private fun AppleTabItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val m = uiMetrics()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "appleTabScale${tab.label}",
    )
    val textColor by animateColorAsState(
        targetValue = when {
            focused -> Color.White
            selected -> Color.White.copy(alpha = 0.95f)
            else -> Color.White.copy(alpha = 0.45f)
        },
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "appleTabText${tab.label}",
    )
    val pillAlpha by animateFloatAsState(
        targetValue = when {
            focused -> 0.10f
            selected -> 0.06f
            else -> 0f
        },
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "appleTabPill${tab.label}",
    )

    Box(
        modifier = Modifier
            .onGloballyPositioned { coords -> onBounds(coords.boundsInParent()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = pillAlpha))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = m.d(18.dp), vertical = m.d(9.dp)),
    ) {
        Text(
            text = tab.label,
            color = textColor,
            fontSize = m.f(14f),
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.2.sp,
            maxLines = 1,
        )
    }
}

/**
 * tvOS-style tab row: labels only, evenly spread from the left edge, with a
 * single slim capsule underneath that glides between the selected tabs.
 */
@Composable
private fun AppleTabBar(
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Layout bounds of each tab, in row coordinates. Written from the layout
    // phase only, and only when they actually change.
    val tabBounds = remember { mutableStateMapOf<Tab, Rect>() }
    val indicatorX = remember { Animatable(0f) }
    val indicatorW = remember { Animatable(0f) }
    var indicatorPlaced by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val target = tabBounds[current]

    LaunchedEffect(target) {
        val bounds = target ?: return@LaunchedEffect
        if (!indicatorPlaced) {
            // First layout: appear in place rather than sliding in from zero.
            indicatorX.snapTo(bounds.left)
            indicatorW.snapTo(bounds.width)
            indicatorPlaced = true
        } else {
            coroutineScope {
                launch {
                    indicatorX.animateTo(
                        targetValue = bounds.left,
                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    indicatorW.animateTo(
                        targetValue = bounds.width,
                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        val m = uiMetrics()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = m.d(TvDimens.contentStart - 14.dp), end = m.d(TvDimens.contentStart), bottom = m.d(12.dp)),
            horizontalArrangement = Arrangement.spacedBy(m.d(2.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                AppleTabItem(
                    tab = tab,
                    selected = current == tab,
                    onClick = { onSelect(tab) },
                    onBounds = { bounds ->
                        if (tabBounds[tab] != bounds) tabBounds[tab] = bounds
                    },
                )
            }
        }

        // Shared selection indicator — one 2dp capsule for the whole row.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 3.dp)
                .offset { IntOffset(indicatorX.value.roundToInt(), 0) }
                .width(with(density) { indicatorW.value.toDp() })
                .height(2.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.55f)),
        )
    }
}

/** Top bar + tab row, drawn as one translucent chrome layer. */
@Composable
private fun AppleHeader(
    current: Tab,
    onSelect: (Tab) -> Unit,
    onHeightChanged: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                val height = with(density) { size.height.toDp() }
                if (height != 0.dp) onHeightChanged(height)
            },
    ) {
        AppleTopBar()
        AppleTabBar(
            current = current,
            onSelect = onSelect,
            modifier = Modifier.zIndex(60f),
        )
    }
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val app = XflixTvApp.instance
    val home by vm.home.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val selectedStream by vm.selectedStream.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.Home) }
    var inDetail by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<Stream?>(null) }

    // Height of the floating chrome; the page is inset by exactly this much so
    // nothing important is ever hidden underneath it. Starts at a close
    // estimate to avoid a first-frame jump, then tracks the real measurement.
    var headerHeight by remember { mutableStateOf(92.dp) }

    val library by app.libraryStore.library()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val progressMap by app.libraryStore.progress()
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    BackHandler(enabled = playing != null || inDetail) {
        when {
            playing != null -> {
                vm.clearStream()
                playing = null
            }
            inDetail -> inDetail = false
        }
    }

    // Keep the catalogue feeling alive while the TV sits on the home screen.
    // Only the top shelves are re-fetched, and publishing is per-row, so the page
    // never blanks or jumps; the viewer just sees newer titles drift in.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(HOME_REFRESH_INTERVAL_MS)
            if (playing == null && !inDetail) vm.refreshHomeInBackground()
        }
    }

    // Player takes full screen
    playing?.let { stream ->
        val meta = detail.meta
        val startMs = meta?.let { progressMap[it.id] } ?: 0L
        PlayerScreen(
            stream = stream,
            allStreams = detail.streams,
            torrentEngine = app.torrentEngine,
            title = listOfNotNull(meta?.name, detail.episodeLabel).joinToString(" · "),
            startPositionMs = startMs,
            onProgress = { pos -> meta?.let { vm.saveProgress(it.id, pos) } },
            onEnded = {
                vm.clearStream()
                playing = null
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
      ResponsiveScope {
        Column(Modifier.fillMaxSize()) {
            // Space owned by the floating chrome above us.
            Spacer(Modifier.height(headerHeight))

            // ============ CONTENT AREA (edge-to-edge; screens own their padding) ============
            Box(Modifier.weight(1f)) {
                if (inDetail) {
                    DetailScreen(
                        state = detail,
                        onPlay = { stream ->
                            vm.rememberPlayed(detail.meta)
                            vm.selectStream(stream)
                            playing = stream
                        },
                        onEpisodeSelect = { video ->
                            vm.rememberPlayed(detail.meta)
                            vm.selectEpisode(video) { stream ->
                                vm.selectStream(stream)
                                playing = stream
                            }
                        },
                        onToggleLibrary = { vm.toggleLibrary(it) },
                    )
                } else {
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            // Only a whisper of movement: fade through, drift a
                            // few pixels in the direction the viewer travelled.
                            val fromIndex = Tab.entries.indexOf(initialState)
                            val toIndex = Tab.entries.indexOf(targetState)
                            val direction = if (toIndex >= fromIndex) 1 else -1

                            val enter = fadeIn(
                                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                            ) + slideInHorizontally(
                                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
                            ) { direction * 28 }

                            val exit = fadeOut(
                                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                            ) + slideOutHorizontally(
                                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                            ) { -direction * 18 }

                            (enter togetherWith exit).using(SizeTransform(clip = false))
                        },
                        label = "tabContent",
                    ) {
                        when (it) {
                            Tab.Search -> SearchScreen(
                                vm = vm,
                                onMetaClick = { meta, addonUrl ->
                                    inDetail = true
                                    vm.openDetail(meta, addonUrl)
                                },
                            )
                            Tab.Home -> HomeScreen(
                                state = home,
                                onMetaClick = { meta, addonUrl ->
                                    inDetail = true
                                    vm.openDetail(meta, addonUrl)
                                },
                                onSearchClick = {
                                    tab = Tab.Search
                                },
                            )
                            Tab.Library -> LibraryScreen(
                                items = library,
                                onMetaClick = { meta ->
                                    inDetail = true
                                    vm.openDetail(meta, meta.addonId ?: installed.firstOrNull().orEmpty())
                                },
                            )
                            Tab.Addons -> AddonsScreen(
                                installed = installed,
                                onInstall = { url, cb -> vm.installAddon(url, cb) },
                                onRemove = { vm.removeAddon(it) },
                            )
                            Tab.Settings -> SettingsScreen()
                        }
                    }
                }
            }
        }

        // Scrim: sits above the page but under the chrome, so the chrome fades
        // into whatever the viewer is looking at — and bleeds a few dp past the
        // bar so there is no hard seam.
        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight + 22.dp)
                .background(HeaderScrim)
                .zIndex(65f)
        )

        AppleHeader(
            current = tab,
            onSelect = { tab = it; inDetail = false },
            onHeightChanged = { measured ->
                if (measured != headerHeight) headerHeight = measured
            },
            modifier = Modifier.zIndex(70f),
        )
      }
    }
}
