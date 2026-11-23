package dev.digi.digix.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digi.digix.DigiXApp
import dev.digi.digix.cast.CastReceiverServer
import dev.digi.digix.data.*
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * How many catalog pages the shelves at the top of the page pull.
 *
 * TMDB ships 20 items per page and Cinemeta 50, so one request leaves a rail
 * looking obviously short on a 1080p TV. Three pages (~60 posters) fills it
 * without turning the page into an endless scroll.
 */
private const val PRIMARY_PAGES = 3

/** Everything below the fold gets two pages: full enough, half the traffic. */
private const val SECONDARY_PAGES = 2

/** Hard ceiling per shelf — past this nobody scrolls, and RAM is ~1 GB. */
private const val MAX_ROW_ITEMS = 60

private const val TMDB_PAGE_SIZE = 20
private const val CINEMETA_PAGE_SIZE = 50

/**
 * A 1 GB Android TV cannot juggle thirty parallel HTTPS requests: OkHttp
 * buffers every response and the poster strings stay resident, which makes the
 * whole launcher crawl. Four in flight fills the page quickly and still leaves
 * headroom for playback.
 */
private const val MAX_CONCURRENT_CATALOG_FETCHES = 4

/** Shelves re-fetched by [MainViewModel.refreshHomeInBackground] each cycle. */
private const val BACKGROUND_REFRESH_ROWS = 4

/** Cadence the UI should drive the background refresh loop at. */
const val HOME_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

private const val TMDB_MANIFEST = "https://tmdb.elfhosted.com/manifest.json"
private const val CINEMETA_MANIFEST = "https://v3-cinemeta.strem.io/manifest.json"
private const val EMPTY_HOME_MESSAGE = "Couldn't load any catalogs. Check your connection and refresh."

/**
 * Declarative description of one shelf.
 *
 * [slot] is a stable identity that survives shelves being dropped or shifted,
 * which is what lets the background refresh update one row in place instead of
 * rebuilding the whole page.
 */
private data class RowSpec(
    val slot: String,
    val title: String,
    val addonUrl: String,
    val type: String,
    val id: String,
    val extras: Map<String, String> = emptyMap(),
    val pages: Int = SECONDARY_PAGES,
    val pageSize: Int = TMDB_PAGE_SIZE,
    val priority: Int = 2,
)

/** One entry of the rotating discovery pool. */
private data class SpotlightShelf(
    val title: String,
    val type: String,
    val id: String,
    val extras: Map<String, String>,
)

/**
 * Rotation pools for the two discovery shelves near the top of the page.
 *
 * Every refresh cycle advances one slot, so a viewer who leaves the app open
 * all evening still sees new genres/languages — the page feels curated instead
 * of frozen — without re-fetching all ~30 shelves.
 */
private val genreSpotlightPool = listOf(
    SpotlightShelf("Edge-of-Your-Seat Action", "movie", "tmdb.top", mapOf("genre" to "Action")),
    SpotlightShelf("Mystery & Crime Nights", "movie", "tmdb.top", mapOf("genre" to "Mystery")),
    SpotlightShelf("Adventure & Fantasy", "movie", "tmdb.top", mapOf("genre" to "Adventure")),
    SpotlightShelf("Award-Winning Drama", "movie", "tmdb.top", mapOf("genre" to "Drama")),
    SpotlightShelf("Animation Spotlight", "movie", "tmdb.top", mapOf("genre" to "Animation")),
    SpotlightShelf("True Stories & Docs", "movie", "tmdb.top", mapOf("genre" to "Documentary")),
    SpotlightShelf("Sci-Fi & Fantasy Series", "series", "tmdb.top", mapOf("genre" to "Sci-Fi & Fantasy")),
    SpotlightShelf("Bingeable Reality", "series", "tmdb.top", mapOf("genre" to "Reality")),
)

private val languageSpotlightPool = listOf(
    SpotlightShelf("Turkish Dramas", "series", "tmdb.language", mapOf("genre" to "Turkish")),
    SpotlightShelf("Japanese Cinema", "movie", "tmdb.language", mapOf("genre" to "Japanese")),
    SpotlightShelf("German Thrillers", "movie", "tmdb.language", mapOf("genre" to "German")),
    SpotlightShelf("Italian Classics", "movie", "tmdb.language", mapOf("genre" to "Italian")),
    SpotlightShelf("Russian Cinema", "movie", "tmdb.language", mapOf("genre" to "Russian")),
    SpotlightShelf("Arabic & Middle Eastern", "movie", "tmdb.language", mapOf("genre" to "Arabic")),
    SpotlightShelf("Portuguese & Brazilian", "movie", "tmdb.language", mapOf("genre" to "Portuguese")),
    SpotlightShelf("Urdu Stories", "movie", "tmdb.language", mapOf("genre" to "Urdu")),
)

/** Wrap-around index for the rotation pools. */
private fun rotateIndex(cycle: Int, size: Int): Int = if (size <= 0) 0 else ((cycle % size) + size) % size

/** Stable slot ids for the two rotating shelves. */
private const val SLOT_SPOTLIGHT_GENRE = "spotlight-genre"
private const val SLOT_SPOTLIGHT_LANGUAGE = "spotlight-language"

/** Build a rotating spotlight spec from the pool entry for [cycle]. */
private fun spotlightSpec(
    slot: String,
    addonUrl: String,
    cycle: Int,
    pool: List<SpotlightShelf>,
    pageSize: Int,
): RowSpec = pool[rotateIndex(cycle, pool.size)].let { shelf ->
    RowSpec(
        slot = slot,
        title = shelf.title,
        addonUrl = addonUrl,
        type = shelf.type,
        id = shelf.id,
        extras = shelf.extras,
        pages = PRIMARY_PAGES,
        pageSize = pageSize,
        priority = 1,
    )
}

data class CatalogRow(
    val title: String,
    val addonUrl: String,
    val type: String,
    val id: String,
    val extras: Map<String, String> = emptyMap(),
    val items: List<Meta> = emptyList(),
    val loading: Boolean = false,
    /** Stable identity so a shelf can be refreshed without relying on its position. */
    val slot: String = "",
    /** 1 = refreshed every background cycle, 2 = only on a full load. */
    val priority: Int = 2,
)

data class HomeState(
    val rows: List<CatalogRow> = emptyList(),
    val continueWatching: List<Meta> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
)

data class DetailState(
    val meta: Meta? = null,
    val loading: Boolean = true,
    val streams: List<Stream> = emptyList(),
    val streamsLoading: Boolean = false,
    val addonUrl: String = "",
    val type: String = "movie",
    val episodeLabel: String? = null,
    val selectedVideo: Video? = null,
    val error: String? = null,
)

class MainViewModel : ViewModel() {

    private val app get() = DigiXApp.instance
    private val client get() = app.addonClient
    private val addonStore get() = app.addonStore
    private val libraryStore get() = app.libraryStore

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Meta>>(emptyList())
    val searchResults: StateFlow<List<Meta>> = _searchResults.asStateFlow()

    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading.asStateFlow()

    private val _detail = MutableStateFlow(DetailState())
    val detail: StateFlow<DetailState> = _detail.asStateFlow()

    private val _installed = MutableStateFlow<List<String>>(emptyList())
    val installed: StateFlow<List<String>> = _installed.asStateFlow()

    private val _selectedStream = MutableStateFlow<Stream?>(null)
    val selectedStream: StateFlow<Stream?> = _selectedStream.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Long>>(emptyMap())
    val progress: StateFlow<Map<String, Long>> = _progress.asStateFlow()

    // ───────────────────────────── Casting (this device = TV) ─────────────────
    //
    // The receiver lives in the ViewModel, not in a composable, so a cast that
    // arrives while the user is browsing still starts playback, and a
    // configuration change cannot orphan a bound socket.

    private var castReceiver: CastReceiverServer? = null

    private val _incomingCast = MutableStateFlow<IncomingCast?>(null)
    /** Set when a phone asks this TV to play something; cleared on stop. */
    val incomingCast: StateFlow<IncomingCast?> = _incomingCast.asStateFlow()

    private val _castInfo = MutableStateFlow<CastInfo?>(null)
    /** Device name / address to show in Settings so a phone can connect manually. */
    val castInfo: StateFlow<CastInfo?> = _castInfo.asStateFlow()

    init {
        startCastReceiver()
    }

    private fun startCastReceiver() {
        val context = DigiXApp.instance.applicationContext
        val name = buildDeviceName()
        val server = CastReceiverServer(
            context = context,
            deviceName = name,
            onCast = { request ->
                _incomingCast.value = IncomingCast(
                    // A cast carries the stream *descriptor*; the TV resolves it
                    // itself through its own torrent engine. Sending a URL would
                    // only work for direct-HTTP sources.
                    stream = Stream(
                        name = "Cast",
                        title = request.title,
                        url = request.url,
                        infoHash = request.infoHash,
                        fileIdx = request.fileIdx,
                        sources = request.trackers,
                    ),
                    title = request.title,
                    startPositionMs = request.positionMs,
                )
            },
            onStopCast = { _incomingCast.value = null },
        )
        castReceiver = server
        // NSD and the socket can both fail (port taken, no LAN). That must never
        // take the app down — the TV simply is not castable from a phone.
        runCatching { server.start() }

        viewModelScope.launch {
            val host = withContext(Dispatchers.IO) { server.localIp }
            _castInfo.value = CastInfo(name = name, host = host, port = server.port)
        }
    }

    /** Called when the viewer dismisses a cast, so a re-cast replays it. */
    fun consumeIncomingCast() {
        _incomingCast.value = null
    }

    /** Mirrors playback state to the phone's status poll. */
    fun publishCastStatus(title: String?, positionMs: Long, durationMs: Long, playing: Boolean) {
        castReceiver?.publishStatus(
            CastStatus(
                deviceName = _castInfo.value?.name ?: "Digi X TV",
                title = title,
                positionMs = positionMs,
                durationMs = durationMs,
                playing = playing,
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { castReceiver?.stop() }
        castReceiver = null
    }

    /** Bounds in-flight catalog HTTP calls so the TV's heap and socket pool survive. */
    private val catalogSemaphore = Semaphore(MAX_CONCURRENT_CATALOG_FETCHES)

    /** Monotonic counter that drives the rotating discovery shelves. */
    private var refreshCycle = 0

    /** The shelves currently on screen, kept so a background refresh can re-fetch in place. */
    private var homeSpecs: List<RowSpec> = emptyList()

    init {
        viewModelScope.launch {
            addonStore.installed().collect { _installed.value = it }
        }
        viewModelScope.launch {
            libraryStore.progress().collect { _progress.value = it }
        }
        viewModelScope.launch {
            // Seed defaults before first fetch
            addonStore.seedDefaultsIfEmpty()
            loadHome()
        }
        // Items persist their Meta verbatim, so a library entry saved by an older
        // build keeps its stale/incorrect fields forever - that is how a
        // 2h53min film showed "2 min" in the hero. Refresh Continue Watching in
        // the background and rewrite the cached copy with the corrected values.
        viewModelScope.launch {
            runCatching { repairLibraryMetadata() }
        }
    }

    private suspend fun repairLibraryMetadata() {
        val cached = libraryStore.library().firstOrNull().orEmpty()
        if (cached.isEmpty()) return
        var changed = false
        val repaired = cached.map { meta ->
            val type = meta.sourceType ?: if (!meta.videos.isNullOrEmpty()) "series" else "movie"
            // Try the addon it came from, then the universal metadata addons.
            val candidates = listOfNotNull(
                meta.sourceAddon?.takeIf { it.isNotBlank() },
                if (meta.id.startsWith("tmdb:")) TMDB_MANIFEST else null,
                CINEMETA_MANIFEST,
            )
            val fresh = candidates.firstNotNullOfOrNull { url ->
                runCatching { client.meta(url, type, meta.id) }.getOrNull()
            } ?: return@map meta
            if (fresh == meta) return@map meta
            changed = true
            meta.copy(
                runtime = fresh.runtime ?: meta.runtime,
                releaseInfo = fresh.releaseInfo ?: meta.releaseInfo,
                description = fresh.description ?: meta.description,
                genre = fresh.genre.ifEmpty { meta.genre },
            )
        }
        if (changed) {
            libraryStore.replaceAll(repaired)
            _home.value = _home.value.copy(continueWatching = repaired)
        }
    }

    fun setSearchQuery(query: String) = viewModelScope.launch {
        _searchQuery.value = query
    }

    fun refreshHome() = viewModelScope.launch { loadHome() }

    /**
     * Cheap periodic refresh the UI can drive from a `LaunchedEffect`.
     *
     * Only the few shelves above the fold are re-fetched (they are what a
     * viewer actually looks at every session), plus one rotating discovery
     * shelf so the page visibly changes. Results are merged in place, so the
     * screen never blanks out while the network is working.
     */
    fun refreshHomeInBackground() = viewModelScope.launch {
        val current = _home.value
        if (current.loading) return@launch
        if (current.rows.isEmpty()) {
            // Nothing survived the initial load; a background merge has nothing
            // to merge into, so do a real (but still non-blocking) reload.
            loadHome()
            return@launch
        }
        refreshCycle++
        cycleSpotlights()
        refreshPriorityRows()
    }

    private suspend fun loadHome() {
        _home.value = _home.value.copy(loading = true, error = null)
        val addons = addonStore.snapshot()
        val continueWatching = libraryStore.library().firstOrNull().orEmpty()

        val specs = buildSpecs(addons, refreshCycle)
        homeSpecs = specs

        // Publish the shelf skeletons now so the page has its final shape while
        // items stream in. `loading` stays true until the first shelf resolves
        // (see publishRow): HomeScreen paints nothing for an items-less row, so
        // clearing the flag here would show a blank page instead of a spinner.
        _home.value = _home.value.copy(
            rows = specs.map { it.toRow() },
            continueWatching = continueWatching,
            error = null,
        )

        loadRows(specs)
    }

    /**
     * Fetches every spec concurrently (bounded by [catalogSemaphore]) and
     * publishes each row the moment it resolves.
     *
     * Progressive fill matters on a TV: the first shelf appears in ~200 ms
     * instead of after all 30 requests finish, and a slow addon can never hold
     * the whole page hostage.
     */
    private suspend fun loadRows(specs: List<RowSpec>) = coroutineScope {
        specs.map { spec ->
            async {
                val items = fetchRow(spec)
                publishRow(spec, items)
            }
        }.awaitAll()
        finishLoad()
    }

    /** Fetch a shelf worth of catalog items, walking `skip` pages until the cap. */
    private suspend fun fetchRow(spec: RowSpec): List<Meta> {
        val pages = spec.pages.coerceIn(1, PRIMARY_PAGES)
        val collected = ArrayList<Meta>(pages * spec.pageSize)
        val seen = HashSet<String>(pages * spec.pageSize)

        for (page in 0 until pages) {
            val extras = if (page == 0) {
                spec.extras
            } else {
                spec.extras + mapOf("skip" to (page * spec.pageSize).toString())
            }
            val pageItems = catalogSemaphore.withPermit {
                // AddonClient already swallows transport errors; no runCatching
                // here so CancellationException is not accidentally swallowed.
                client.catalog(spec.addonUrl, spec.type, spec.id, extras)
            }
            if (pageItems.isEmpty()) break // no more pages upstream; stop early
            for (meta in pageItems) {
                // Pages can overlap when an addon re-ranks between requests.
                if (seen.add(meta.id)) collected += meta
                if (collected.size >= MAX_ROW_ITEMS) break
            }
            if (collected.size >= MAX_ROW_ITEMS) break
        }

        return collected.map { it.copy(sourceAddon = spec.addonUrl, sourceType = spec.type) }
    }

    /**
     * Replace a single row's items in place.
     *
     * Uses an atomic [MutableStateFlow.update]: thirty rows resolve concurrently
     * and a read-then-write would silently lose whichever shelf finished second.
     *
     * @param allowInsert re-attach a shelf that was dropped for being empty —
     *   a rotating shelf may simply have had a bad cycle and deserves to return.
     */
    private fun publishRow(spec: RowSpec, items: List<Meta>, allowInsert: Boolean = false) {
        _home.update { state ->
            val index = state.rows.indexOfFirst { it.slot == spec.slot }

            when {
                index < 0 && !allowInsert -> state

                index < 0 && items.isNotEmpty() -> {
                    // Re-insert at its curated position: find the nearest spec
                    // above it that is still on screen and sit directly after it.
                    val order = homeSpecs.indexOfFirst { it.slot == spec.slot }
                    val anchorSlot = if (order > 0) {
                        homeSpecs.take(order).lastOrNull { prev ->
                            state.rows.any { it.slot == prev.slot }
                        }?.slot
                    } else {
                        null
                    }
                    val at = if (anchorSlot != null) {
                        state.rows.indexOfFirst { it.slot == anchorSlot }
                    } else {
                        -1
                    }
                    val pos = if (at >= 0) at + 1 else state.rows.size
                    val restored = spec.toRow().copy(items = items, loading = false)
                    state.copy(rows = state.rows.toMutableList().also { it.add(pos, restored) })
                }

                index < 0 -> state

                items.isEmpty() -> {
                    // An empty shelf is worse than no shelf: drop it rather than
                    // leave a blank strip of whitespace in the page.
                    state.copy(rows = state.rows.filterIndexed { i, _ -> i != index })
                }

                else -> state.copy(
                    // The first shelf to land is enough to paint a real page, so
                    // drop the full-screen spinner as soon as it arrives.
                    loading = false,
                    rows = state.rows.toMutableList().also { list ->
                        // Title/extras are applied together with the items so a
                        // rotated shelf never shows new art under the old heading.
                        val old = list[index]
                        list[index] = old.copy(
                            title = spec.title,
                            type = spec.type,
                            id = spec.id,
                            extras = spec.extras,
                            items = items,
                            loading = false,
                        )
                    },
                )
            }
        }
    }

    /** Never leave `loading` stuck on, and surface one short message if nothing at all arrived. */
    private fun finishLoad() {
        _home.update { state ->
            val hasContent = state.rows.any { it.items.isNotEmpty() }
            state.copy(
                loading = false,
                error = if (!hasContent && state.rows.isEmpty()) EMPTY_HOME_MESSAGE else null,
            )
        }
    }

    /**
     * Re-fetch the highest-priority shelves and merge the results into the
     * page as each one lands. Nothing is cleared first, so the viewer keeps
     * watching a stable page while the network works behind it.
     */
    private suspend fun refreshPriorityRows() = coroutineScope {
        val rotated = setOf(SLOT_SPOTLIGHT_GENRE, SLOT_SPOTLIGHT_LANGUAGE)

        // The two rotating shelves must always be in the cycle, otherwise their
        // header would change while the posters stayed frozen.
        val candidates = homeSpecs.filter { it.priority == 1 }
        val spotlights = candidates.filter { it.slot in rotated }
        val core = candidates
            .filterNot { it.slot in rotated }
            .take((BACKGROUND_REFRESH_ROWS - spotlights.size).coerceAtLeast(1))
        val specs = core + spotlights
        if (specs.isEmpty()) return@coroutineScope

        specs.map { spec ->
            async {
                val items = fetchRow(spec)
                if (items.isNotEmpty()) publishRow(spec, items, allowInsert = true)
            }
        }.awaitAll()
    }

    /**
     * Advance both spotlight shelves by one step and rebuild their specs so the
     * next background cycle fetches a different genre/language.
     *
     * The existing posters are deliberately left in place: clearing them here
     * would blank two shelves for the whole fetch, which looks like a bug. The
     * new items land in [publishRow], which swaps title and art together.
     */
    private fun cycleSpotlights() {
        if (homeSpecs.isEmpty()) return
        homeSpecs = homeSpecs.map { spec ->
            when (spec.slot) {
                SLOT_SPOTLIGHT_GENRE ->
                    spec.fromSpotlight(genreSpotlightPool[rotateIndex(refreshCycle, genreSpotlightPool.size)])
                SLOT_SPOTLIGHT_LANGUAGE ->
                    spec.fromSpotlight(languageSpotlightPool[rotateIndex(refreshCycle, languageSpotlightPool.size)])
                else -> spec
            }
        }
    }

    private fun RowSpec.fromSpotlight(shelf: SpotlightShelf) = copy(
        title = shelf.title,
        type = shelf.type,
        id = shelf.id,
        extras = shelf.extras,
    )

    private fun RowSpec.toRow() = CatalogRow(
        title = title,
        addonUrl = addonUrl,
        type = type,
        id = id,
        extras = extras,
        slot = slot,
        priority = priority,
        loading = true,
    )

    /**
     * The curated shelf list, in display order.
     *
     * The first entry is deliberately the trending shelf because the home
     * screen renders row 0 as its ranked "Top 10 Today" rail.
     */
    private suspend fun buildSpecs(addons: List<String>, cycle: Int): List<RowSpec> {
        val tmdbUrl = addons.firstOrNull { it.contains("tmdb") } ?: TMDB_MANIFEST
        val cinemetaUrl = addons.firstOrNull { it.contains("cinemeta") } ?: CINEMETA_MANIFEST

        fun tmdb(
            slot: String,
            title: String,
            type: String,
            id: String,
            extras: Map<String, String> = emptyMap(),
            pages: Int = SECONDARY_PAGES,
            priority: Int = 2,
        ) = RowSpec(slot, title, tmdbUrl, type, id, extras, pages, TMDB_PAGE_SIZE, priority)

        fun cinemeta(
            slot: String,
            title: String,
            type: String,
            id: String,
            pages: Int = SECONDARY_PAGES,
            priority: Int = 2,
        ) = RowSpec(slot, title, cinemetaUrl, type, id, emptyMap(), pages, CINEMETA_PAGE_SIZE, priority)

        val rows = mutableListOf<RowSpec>()

        // --- Above the fold: refreshed every cycle -------------------------
        rows += tmdb(
            slot = "trending",
            title = "Top 10 Today",
            type = "movie",
            id = "tmdb.trending",
            extras = mapOf("genre" to "Week"),
            pages = PRIMARY_PAGES,
            priority = 1,
        )
        rows += cinemeta("popular-movies", "Popular Movies", "movie", "top", PRIMARY_PAGES, priority = 1)
        rows += cinemeta("popular-series", "Popular Series", "series", "top", PRIMARY_PAGES, priority = 1)
        rows += spotlightSpec(SLOT_SPOTLIGHT_GENRE, tmdbUrl, cycle, genreSpotlightPool, TMDB_PAGE_SIZE)

        // --- Indian cinema, shelf by shelf ---------------------------------
        rows += tmdb("hindi", "Bollywood & Hindi", "movie", "tmdb.language", mapOf("genre" to "Hindi"), PRIMARY_PAGES)
        rows += tmdb("punjabi", "Punjabi Cinema", "movie", "tmdb.language", mapOf("genre" to "Punjabi"))
        rows += tmdb("tamil", "Tamil Cinema", "movie", "tmdb.language", mapOf("genre" to "Tamil"))
        rows += tmdb("telugu", "Telugu Cinema", "movie", "tmdb.language", mapOf("genre" to "Telugu"))
        rows += tmdb("malayalam", "Malayalam Cinema", "movie", "tmdb.language", mapOf("genre" to "Malayalam"))
        rows += tmdb("kannada", "Kannada Cinema", "movie", "tmdb.language", mapOf("genre" to "Kannada"))
        rows += tmdb("marathi-bengali", "Marathi & Bengali Cinema", "movie", "tmdb.language", mapOf("genre" to "Marathi"))
        rows += tmdb("bengali", "Bengali Cinema", "movie", "tmdb.language", mapOf("genre" to "Bengali"))

        // --- Asia ----------------------------------------------------------
        rows += tmdb("kdrama", "K-Dramas & Korean Hits", "series", "tmdb.language", mapOf("genre" to "Korean"))
        rows += tmdb("anime", "Anime & Japanese", "series", "tmdb.language", mapOf("genre" to "Japanese"))
        rows += spotlightSpec(SLOT_SPOTLIGHT_LANGUAGE, tmdbUrl, cycle, languageSpotlightPool, TMDB_PAGE_SIZE)

        // --- Family, kids & cartoons ---------------------------------------
        rows += tmdb("kids-movies", "Kids & Family Movies", "movie", "tmdb.top", mapOf("genre" to "Family"))
        rows += tmdb("kids-tv", "Kids TV & Cartoons", "series", "tmdb.top", mapOf("genre" to "Kids"))

        // --- Fresh releases -------------------------------------------------
        rows += tmdb("new-releases", "New Releases", "movie", "tmdb.latest", pages = PRIMARY_PAGES)
        rows += tmdb("year-2026", "2026 Releases", "movie", "tmdb.year", mapOf("genre" to "2026"))
        rows += tmdb("year-2025", "2025 Releases", "movie", "tmdb.year", mapOf("genre" to "2025"))

        // --- Genre rails ----------------------------------------------------
        rows += tmdb("action", "Action & Adventure", "movie", "tmdb.top", mapOf("genre" to "Action"))
        rows += tmdb("comedy", "Comedy", "movie", "tmdb.top", mapOf("genre" to "Comedy"))
        rows += tmdb("thriller", "Thrillers", "movie", "tmdb.top", mapOf("genre" to "Thriller"))
        rows += tmdb("horror", "Horror", "movie", "tmdb.top", mapOf("genre" to "Horror"))
        rows += tmdb("romance", "Romance", "movie", "tmdb.top", mapOf("genre" to "Romance"))
        rows += tmdb("scifi", "Sci-Fi", "movie", "tmdb.top", mapOf("genre" to "Science Fiction"))

        // --- World cinema ---------------------------------------------------
        rows += tmdb("spanish", "Spanish & Latin Hits", "movie", "tmdb.language", mapOf("genre" to "Spanish"))
        rows += tmdb("french", "French Cinema", "movie", "tmdb.language", mapOf("genre" to "French"))
        // Italian, not German: German already appears in the rotating pool, so
        // using it here would show the same titles twice on some cycles.
        rows += tmdb("european", "European Cinema", "movie", "tmdb.language", mapOf("genre" to "Italian"))
        rows += tmdb("documentaries", "Documentaries", "movie", "tmdb.top", mapOf("genre" to "Documentary"))

        // --- Whatever else the viewer installed ------------------------------
        for (url in addons) {
            if (url.contains("tmdb") || url.contains("cinemeta") || url.contains("torrentio") ||
                url.contains("watchhub") || url.contains("opensubtitles")
            ) continue
            val manifest = catalogSemaphore.withPermit {
                runCatching { client.fetchManifest(url) }.getOrNull()
            } ?: continue
            for (cat in manifest.catalogs) {
                if (cat.type !in setOf("movie", "series", "anime")) continue
                val title = cat.name?.takeIf { it.isNotBlank() }
                    ?: cat.id.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                rows += RowSpec(
                    slot = "addon-${cat.type}-${cat.id}",
                    title = title,
                    addonUrl = url,
                    type = cat.type,
                    id = cat.id,
                    extras = cat.defaults.ifEmpty { AddonRepository.catalogDefaults[cat.id] ?: emptyMap() },
                    pages = 1,
                    pageSize = TMDB_PAGE_SIZE,
                    priority = 2,
                )
            }
        }

        return rows
    }

    private fun detailType(meta: Meta): String =
        meta.sourceType?.takeIf { it.isNotBlank() }
            ?: meta.releaseInfo?.type?.takeIf { it.isNotBlank() }
            ?: if (!meta.videos.isNullOrEmpty()) "series" else "movie"

    private suspend fun resolveImdbId(meta: Meta, type: String): String? {
        if (meta.id.startsWith("tt")) return meta.id
        if (meta.imdbId?.startsWith("tt") == true) return meta.imdbId

        // 1. Try TMDB meta lookup if id is tmdb:...
        if (meta.id.startsWith("tmdb:")) {
            val tmdbUrl = addonStore.snapshot().firstOrNull { it.contains("tmdb") }
                ?: "https://tmdb.elfhosted.com/manifest.json"
            val tmdbMeta = client.meta(tmdbUrl, type, meta.id)
            if (tmdbMeta?.imdbId?.startsWith("tt") == true) return tmdbMeta.imdbId
            if (tmdbMeta?.id?.startsWith("tt") == true) return tmdbMeta.id
        }

        // 2. Search Cinemeta by name as fallback
        val name = meta.name?.takeIf { it.isNotBlank() } ?: return null
        val cinemetaUrl = "https://v3-cinemeta.strem.io/manifest.json"
        val searchResults = client.catalog(cinemetaUrl, type, "top", mapOf("search" to name))
        return searchResults.firstOrNull { it.id.startsWith("tt") }?.id
            ?: searchResults.firstOrNull { it.imdbId?.startsWith("tt") == true }?.imdbId
    }

    fun openDetail(meta: Meta, addonUrl: String) = viewModelScope.launch {
        val base = meta.sourceAddon?.takeIf { it.isNotBlank() }
            ?: addonUrl.takeIf { it.isNotBlank() }
            ?: addonStore.snapshot().firstOrNull().orEmpty()
        val type = detailType(meta)

        _detail.value = DetailState(
            meta = meta,
            loading = false,
            addonUrl = base,
            type = type,
            streamsLoading = true,
        )

        val full = if (base.isNotBlank()) {
            client.meta(base, type, meta.id) ?: meta
        } else meta

        val resolvedType = when {
            type != "movie" -> type
            !full.videos.isNullOrEmpty() -> "series"
            else -> "movie"
        }

        var streamId = full.id
        val resolvedImdb = resolveImdbId(full, resolvedType)
        if (resolvedImdb != null) {
            streamId = resolvedImdb
        }

        var effectiveMeta = full
        if (resolvedType == "series") {
            // If series has no episode guide (e.g. from TMDB catalog), fetch from Cinemeta using resolved IMDb ID
            if (effectiveMeta.videos.isNullOrEmpty() && resolvedImdb != null) {
                val cinemetaMeta = client.meta("https://v3-cinemeta.strem.io/manifest.json", "series", resolvedImdb)
                if (cinemetaMeta != null) {
                    effectiveMeta = cinemetaMeta.copy(
                        name = full.name ?: cinemetaMeta.name,
                        poster = full.poster ?: cinemetaMeta.poster,
                        background = full.background ?: cinemetaMeta.background,
                        description = full.description ?: cinemetaMeta.description,
                        imdbId = resolvedImdb,
                    )
                }
            }

            val episode = effectiveMeta.videos
                ?.filter { (it.season ?: 0) >= 1 && (it.episode ?: 0) >= 1 }
                ?.minWithOrNull(compareBy<Video> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
                ?: effectiveMeta.videos?.firstOrNull()

            if (episode != null) {
                val epSeason = episode.season ?: 1
                val epNumber = episode.episode ?: 1
                streamId = if (episode.id.startsWith("tt")) {
                    episode.id
                } else if (resolvedImdb != null) {
                    "$resolvedImdb:$epSeason:$epNumber"
                } else {
                    episode.id
                }
                _detail.value = _detail.value.copy(
                    meta = effectiveMeta,
                    selectedVideo = episode,
                    episodeLabel = episode.episodeLabel(),
                )
            }
        }

        _detail.value = _detail.value.copy(meta = effectiveMeta, addonUrl = base, type = resolvedType)

        android.util.Log.i(
            "DigiXTV",
            "detail type=$resolvedType streamId=$streamId videos=${effectiveMeta.videos?.size ?: 0}",
        )

        // Publish the first ranked batch as soon as the fastest addon answers so
        // the visible source list and Play button are live without waiting for
        // the slowest addon (Torrentio alone can take several seconds).
        val allStreams = fetchStreams(resolvedType, streamId) { partial ->
            _detail.value = _detail.value.copy(streams = partial, streamsLoading = true)
        }
        _detail.value = _detail.value.copy(streams = allStreams, streamsLoading = false)
    }

    /** Switch the detail page to another episode of the open series and optionally autoplay. */
    fun selectEpisode(video: Video, onReadyToPlay: ((Stream) -> Unit)? = null) = viewModelScope.launch {
        val current = _detail.value
        val meta = current.meta ?: return@launch
        val type = if (current.type == "movie") "series" else current.type
        _detail.value = current.copy(
            streams = emptyList(),
            streamsLoading = true,
            selectedVideo = video,
            episodeLabel = video.episodeLabel(),
        )

        val epSeason = video.season ?: 1
        val epNumber = video.episode ?: 1
        val resolvedImdb = meta.imdbId?.takeIf { it.startsWith("tt") }
            ?: meta.id.takeIf { it.startsWith("tt") }
            ?: resolveImdbId(meta, type)

        val streamId = if (video.id.startsWith("tt")) {
            video.id
        } else if (resolvedImdb != null) {
            "$resolvedImdb:$epSeason:$epNumber"
        } else {
            video.id
        }

        android.util.Log.i("DigiXTV", "selectEpisode -> $streamId ($type)")
        val allStreams = fetchStreams(type, streamId)
        _detail.value = _detail.value.copy(streams = allStreams, streamsLoading = false)
        allStreams.firstOrNull()?.let { stream ->
            onReadyToPlay?.invoke(stream)
        }
    }

    /**
     * Ask every stream-capable addon for sources and publish each answer the
     * moment it lands, so the "Play" button becomes usable after the *fastest*
     * addon replies instead of after the slowest one timed out.
     */
    private suspend fun fetchStreams(
        type: String,
        streamId: String,
        onPartial: ((List<Stream>) -> Unit)? = null,
    ): List<Stream> = withContext(Dispatchers.IO) {
        val urls = (addonStore.snapshot() + AddonRepository.defaultAddons).distinct()

        val collected = java.util.Collections.synchronizedList(mutableListOf<Stream>())
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        coroutineScope {
            urls.forEach { url ->
                launch {
                    // Capability check happens *inside* the concurrent block. Doing it
                    // up front made the six manifest round-trips sequential, which
                    // pushed the first source several seconds later on a cold start.
                    if (!client.supportsStream(url)) return@launch
                    val found = client.streams(url, type, streamId)
                    android.util.Log.i(
                        "DigiXTV",
                        "${url.substringAfter("//")} -> ${found.size} streams ($type/$streamId)",
                    )
                    if (found.isEmpty()) return@launch
                    val fresh = synchronized(seen) {
                        found.filter { seen.add(it.url ?: "${it.infoHash}/${it.fileIdx ?: 0}") }
                    }
                    if (fresh.isEmpty()) return@launch
                    collected.addAll(fresh)
                    // Ranked snapshot: the player can start on the best source now.
                    onPartial?.invoke(
                        synchronized(collected) {
                            collected.toList().sortedByDescending { it.score(preferFallback = false) }
                        }
                    )
                }
            }
        }

        synchronized(collected) {
            collected.toList().sortedByDescending { it.score(preferFallback = false) }
        }
    }

    fun selectStream(stream: Stream) {
        _selectedStream.value = stream
    }

    fun clearStream() {
        _selectedStream.value = null
    }

    /** Keep played titles in the library so Continue Watching has something to show. */
    fun rememberPlayed(meta: Meta?) {
        val m = meta ?: return
        viewModelScope.launch {
            runCatching { libraryStore.addToLibrary(m) }
            libraryStore.library().firstOrNull()?.let { lib ->
                _home.value = _home.value.copy(continueWatching = lib)
            }
        }
    }

    fun installAddon(url: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val manifest = client.fetchManifest(url)
        if (manifest != null && AddonRepository.validateManifest(manifest)) {
            addonStore.add(url)
            refreshHome()
            onDone(true)
        } else {
            onDone(false)
        }
    }

    fun removeAddon(url: String) = viewModelScope.launch {
        addonStore.remove(url)
        refreshHome()
    }

    fun toggleLibrary(meta: Meta) = viewModelScope.launch {
        libraryStore.addToLibrary(meta)
    }

    fun saveProgress(id: String, ms: Long) = viewModelScope.launch {
        libraryStore.saveProgress(id, ms)
    }

    fun search(query: String) = viewModelScope.launch {
        _searchQuery.value = query
        val trimmed = query.trim()
        if (trimmed.isBlank() || trimmed.equals("Trending", ignoreCase = true)) {
            _searchLoading.value = true
            val tmdbUrl = "https://tmdb.elfhosted.com/manifest.json"
            val recs = client.catalog(tmdbUrl, "movie", "tmdb.trending", mapOf("genre" to "Week"))
                .map { it.copy(sourceAddon = tmdbUrl, sourceType = "movie") }
            _searchResults.value = recs
            _searchLoading.value = false
            return@launch
        }

        _searchLoading.value = true
        val cinemetaUrl = "https://v3-cinemeta.strem.io/manifest.json"
        val tmdbUrl = "https://tmdb.elfhosted.com/manifest.json"

        val lower = trimmed.lowercase().removeSuffix(" audio").trim()
        val languageFilter = when (lower) {
            "hindi", "bollywood" -> "Hindi"
            "tamil" -> "Tamil"
            "telugu" -> "Telugu"
            "south indian" -> "Tamil"
            "malayalam" -> "Malayalam"
            "kannada" -> "Kannada"
            "korean", "k-drama" -> "Korean"
            "japanese", "anime" -> "Japanese"
            "spanish" -> "Spanish"
            "french" -> "French"
            "german" -> "German"
            "italian" -> "Italian"
            "russian" -> "Russian"
            else -> null
        }

        val found = coroutineScope {
            val d1 = async {
                client.catalog(cinemetaUrl, "movie", "top", mapOf("search" to trimmed))
                    .map { it.copy(sourceAddon = cinemetaUrl, sourceType = "movie") }
            }
            val d2 = async {
                client.catalog(cinemetaUrl, "series", "top", mapOf("search" to trimmed))
                    .map { it.copy(sourceAddon = cinemetaUrl, sourceType = "series") }
            }
            val d3 = async {
                client.catalog(tmdbUrl, "movie", "tmdb.top", mapOf("search" to trimmed))
                    .map { it.copy(sourceAddon = tmdbUrl, sourceType = "movie") }
            }
            val d4 = async {
                client.catalog(tmdbUrl, "series", "tmdb.top", mapOf("search" to trimmed))
                    .map { it.copy(sourceAddon = tmdbUrl, sourceType = "series") }
            }
            val d5 = if (languageFilter != null) {
                async {
                    client.catalog(tmdbUrl, "movie", "tmdb.language", mapOf("genre" to languageFilter))
                        .map { it.copy(sourceAddon = tmdbUrl, sourceType = "movie") }
                }
            } else null
            val d6 = if (languageFilter != null) {
                async {
                    client.catalog(tmdbUrl, "series", "tmdb.language", mapOf("genre" to languageFilter))
                        .map { it.copy(sourceAddon = tmdbUrl, sourceType = "series") }
                }
            } else null

            listOfNotNull(d1, d2, d3, d4, d5, d6).awaitAll().flatten()
        }

        val cleaned = found
            .filter { !it.name.isNullOrBlank() && (!it.poster.isNullOrBlank() || !it.background.isNullOrBlank()) }
            .distinctBy { it.name?.lowercase()?.trim() ?: it.id }

        _searchResults.value = cleaned
        _searchLoading.value = false
    }
}

private fun Video.episodeLabel(): String? {
    val s = season
    val e = episode
    return when {
        s != null && e != null -> "S${s}E${e}"
        title != null -> title
        else -> null
    }
}

/**
 * A cast request that has arrived on this TV and is waiting to be played.
 *
 * [stream] is only a descriptor: the player resolves the torrent here, on the
 * TV, exactly as if the viewer had picked it from the detail screen.
 */
data class IncomingCast(
    val stream: Stream,
    val title: String,
    val startPositionMs: Long,
)

/** What this TV tells the phone it is, so manual connections can find it. */
data class CastInfo(
    val name: String,
    val host: String?,
    val port: Int,
)

/**
 * The name a phone sees in its cast list. Prefers the room/device name the
 * viewer set on the TV, then the model, so two identical boxes are still
 * distinguishable.
 */
internal fun buildDeviceName(): String {
    val model = Build.MODEL?.trim().orEmpty()
    val brand = Build.MANUFACTURER?.trim().orEmpty()
    val name = when {
        model.isBlank() -> "Digi X TV"
        model.startsWith(brand, ignoreCase = true) -> model
        brand.isBlank() -> model
        else -> "$brand $model"
    }
    return name.replace(Regex("[^A-Za-z0-9 ._-]"), " ").trim().take(40)
        .ifBlank { "Digi X TV" }
}
