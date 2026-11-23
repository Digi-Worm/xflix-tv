package dev.digi.digix.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.StateChangedAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * libtorrent-backed engine that exposes a torrent as a local HTTP endpoint.
 * Media3 plays http://127.0.0.1:<port>/<fileIdx> and seeks via Range requests.
 */
class TorrentEngine : AlertListener {

    companion object {
        private const val TAG = "TorrentEngine"
        private const val BLOCK = 256 * 1024

        /**
         * How long a prewarm keeps hunting for torrent metadata before giving up.
         * Metadata is only 2-20 KB, so if no peer answers in this window the swarm
         * is effectively dead and the player should move to another source.
         */
        private const val PREWARM_METADATA_BUDGET_MS = 30_000L

        /** Extensions treated as the feature presentation, not a sample or extra. */
        private val VIDEO_EXT = listOf(".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".webm")

        /** Below this, a "video" file is a sample/trailer rather than the title. */
        private const val MIN_FEATURE_BYTES = 60L * 1024 * 1024

        /** Public trackers used to connect to peers across all networks. */
        private val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.bittor.pw:1337/announce",
            "udp://tracker.cyberia.is:6969/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://tracker.coppersurfer.tk:6969/announce",
            "udp://p4p.arenabg.com:1337/announce",
            "udp://movies.zsw.ca:6969/announce",
            "http://tracker.openbittorrent.com:80/announce",
        )

        private val DHT_BOOTSTRAP = listOf(
            "router.bittorrent.com:6881",
            "dht.transmissionbt.com:6881",
            "router.utorrent.com:6881",
            "dht.libtorrent.org:25401",
            "dht.aelitis.com:6881",
        ).joinToString(",")
    }

    data class TorrentUiState(
        val ready: Boolean = false,
        val progress: Float = 0f,
        val bufferProgress: Float = 0f,
        val downloadRate: Int = 0,
        val peers: Int = 0,
        val error: String? = null,
        val localUrl: String? = null,
    )

    private val _state = MutableStateFlow(TorrentUiState())
    val state: StateFlow<TorrentUiState> = _state.asStateFlow()

    private var session: SessionManager? = null

    /**
     * Index of the file the engine resolved as the feature presentation. The
     * torrent's own `so=` hint is unreliable, so the HTTP server and the player
     * must both use this resolved index instead of what the magnet claimed.
     */
    @Volatile private var playableFileIdx: Int? = null
    // Read from the HTTP server threads and the stats thread; written from the player.
    @Volatile private var handle: TorrentHandle? = null
    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private var statsThread: Thread? = null
    private var fetchThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var port: Int = 0
    @Volatile private var multiFile: List<String> = emptyList()
    @Volatile private var rootDir: File? = null

    /** Set when the native libtorrent binary for this CPU can't be loaded. */
    @Volatile private var nativeUnavailable: String? = null

    fun start(): Boolean {
        if (session != null) return true
        if (nativeUnavailable != null) return false

        return try {
            val settings = SettingsPack.defaultSettings().apply {
                // Random unprivileged port prevents ISP traffic shaping on 6881
                listenInterfaces("0.0.0.0:0,[::]:0")
                setEnableDht(true)
                setEnableLsd(true)
                setDhtBootstrapNodes(DHT_BOOTSTRAP)
                connectionsLimit(200)
                activeLimit(200)
                activeDownloads(25)
                activeSeeds(25)
                activeTrackerLimit(300)
                activeDhtLimit(200)
                activeLsdLimit(200)
                maxPeerlistSize(8000)
                setMaxMetadataSize(16 * 1024 * 1024)
                uploadRateLimit(0)
                downloadRateLimit(0)
                tickInterval(100)
                // Default libtorrent inactivity_timeout is 600s. A 15s value dropped
                // peers mid-piece / mid-disk-write and caused endless re-discovery churn.
                inactivityTimeout(300)
                maxQueuedDiskBytes(64 * 1024 * 1024)
                sendBufferWatermark(4 * 1024 * 1024)
            }
            val params = SessionParams(settings)

            session = SessionManager().also {
                it.start(params)
                it.startDht()
                it.addListener(this)
            }
            Log.i(TAG, "session started (dht=${session?.isDhtRunning()})")

            statsThread = Thread({ pollStats() }, "torrent-stats").apply {
                isDaemon = true
                start()
            }
            true
        } catch (t: Throwable) {
            // A 32-bit TV without a matching libtorrent4j ABI throws LinkageError
            // here. That must never take the whole app down, so report it in the
            // UI and leave direct HTTP streams fully usable.
            Log.e(TAG, "torrent engine unavailable on this device", t)
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            nativeUnavailable = "Torrent playback isn't available on this device ($abi)"
            runCatching { session?.stop() }
            session = null
            _state.value = _state.value.copy(error = nativeUnavailable)
            false
        }
    }

    /**
     * Torrents that never produced metadata, keyed by infoHash.
     *
     * Swarm health does not improve over seconds, so retrying a dead hash costs a
     * full timeout on every Play. Remembering it lets prewarm skip the dead source
     * and lets the player fail over straight away. Cleared for a hash once it is
     * seen working again.
     */
    private val deadMetadata = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Prewarmed torrent handles that are not the active playback handle.
     *
     * libtorrent4j 2.1 exposes no way to enumerate a session, so the engine tracks
     * its own additions in order to park them once playback starts.
     */
    private val prewarmedHandles =
        java.util.concurrent.ConcurrentHashMap<String, TorrentHandle>()

    /**
     * True when the active torrent has metadata, so the local server can serve it
     * (possibly still buffering) rather than this being an invalid source.
     */
    fun metadataReady(): Boolean {
        val h = handle ?: return false
        return runCatching { h.torrentFile() }.getOrNull() != null
    }

    /**
     * Stop every torrent except the one that should be playing.
     *
     * libtorrent shares one connection and peer budget across all its torrents, so
     * leftover prewarm candidates silently starve the torrent that is actually
     * playing. On the TV this looked like a healthy title stuck at one peer and 0%
     * for forty seconds after browsing a few detail pages. Pausing releases the
     * peer slots but keeps downloaded metadata, so re-opening stays instant.
     *
     * Crucially this also parks the *previously playing* handle. Switching sources
     * used to leak it: `handle` was reassigned to the new torrent and the old one
     * kept downloading at full speed forever. With two copies of the same feature
     * running, each got half the bandwidth, the new source looked "too slow" to the
     * cold-start watchdog, and the player moved on again - an endless cascade that
     * showed up on the TV as constant source switching over a black screen.
     */
    private fun pruneInactive(keep: TorrentHandle?) {
        val keepHash = keep?.let { runCatching { it.infoHash() }.getOrNull() }

        // Park the handle that was playing before this call, unless it is the keeper.
        val previous = handle
        if (previous != null && previous !== keep) {
            val prevHash = runCatching { previous.infoHash() }.getOrNull()
            if (prevHash != null && prevHash != keepHash) {
                park(previous)
                Log.i(TAG, "parked previous playback handle ${prevHash.toHex().take(12)}")
            }
        }

        for ((hex, h) in prewarmedHandles.toMap()) {
            val parsed = Sha1Hash.parseHex(hex) ?: continue
            if (keepHash != null && parsed == keepHash) continue
            park(h)
            prewarmedHandles.remove(hex)
            Log.i(TAG, "parked inactive torrent ${hex.take(12)}")
        }
    }

    /**
     * Genuinely stop a torrent.
     *
     * `pause()` alone is not enough: every torrent is added with AUTO_MANAGED, and
     * libtorrent's auto-management immediately resumes any torrent it thinks should
     * be running. Measured on the TV: two directories kept growing at ~0.9 MB/s
     * each after the player had already switched away, so each source only got half
     * the bandwidth, looked "too slow" to the watchdog, and was abandoned in turn -
     * an endless switch loop over a stalled picture.
     *
     * Clearing AUTO_MANAGED first makes the pause stick until we explicitly resume
     * the torrent again. Downloaded data and metadata are kept, so resuming is instant.
     */
    private fun park(h: TorrentHandle) {
        runCatching { h.unsetFlags(TorrentFlags.AUTO_MANAGED) }
        runCatching { h.pause() }
    }

    /** True when this torrent already failed to produce metadata recently. */
    fun isKnownDead(infoHash: String): Boolean = deadMetadata.contains(infoHash.lowercase())

    /**
     * True when metadata is already in the session, i.e. the torrent can be served
     * right now without another peer handshake. Prewarm makes this true while the
     * viewer is still on the detail page.
     */
    fun isReady(infoHash: String): Boolean {
        val hash = Sha1Hash.parseHex(infoHash) ?: return false
        val h = runCatching { session?.find(hash) }.getOrNull() ?: return false
        return runCatching { h.torrentFile() }.getOrNull() != null ||
            runCatching { h.status().hasMetadata() }.getOrDefault(false)
    }

    /**
     * Add a torrent by infoHash and block (briefly) until metadata arrives so the
     * caller can hand a working URL to the player.
     */
    suspend fun open(
        infoHash: String,
        trackers: List<String> = emptyList(),
        saveDir: File,
        fileIdx: Int = 0,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!start()) return@withContext false
            try {
            val hash = Sha1Hash.parseHex(infoHash)
            if (hash == null) {
                _state.value = _state.value.copy(error = "bad infoHash")
                return@withContext false
            }

            // Re-opening the same torrent (player restart, retry, another episode
            // of the same pack) must not re-add it: libtorrent would re-hash every
            // downloaded byte, which on a TV can take minutes.
            val live = runCatching { session?.find(hash) }.getOrNull()
            val liveStatus = runCatching { live?.status() }.getOrNull()
            if (live != null && runCatching { liveStatus?.hasMetadata() == true }.getOrDefault(false)) {
                // Park whatever was playing (or warming) *before* taking ownership.
                // This fast path used to skip pruning entirely, so switching to a
                // prewarmed source left the old torrent downloading in parallel.
                pruneInactive(keep = live)
                handle = live
                rootDir = File(saveDir, infoHash.lowercase()).apply { mkdirs() }
                StorageJanitor.protect(rootDir)
                val info = runCatching { live.torrentFile() }.getOrNull()
                if (info != null) {
                    val files = info.files()
                    multiFile = (0 until files.numFiles()).map { files.filePath(it) }
                    val target = resolvePlayableIndex(info, fileIdx)
                    playableFileIdx = target
                    for (i in 0 until files.numFiles()) {
                        runCatching {
                            live.filePriority(
                                i,
                                if (i == target) org.libtorrent4j.Priority.DEFAULT
                                else org.libtorrent4j.Priority.IGNORE
                            )
                        }
                    }
                }
                runCatching { live.setFlags(TorrentFlags.AUTO_MANAGED) }
                runCatching { live.resume() }
                runCatching { live.setSequentialRange(0) }
                ensureServer()
                if (info != null) healZeroedPayload(live, info, playableFileIdx ?: 0)
                StorageJanitor.protect(rootDir)
                _state.value = TorrentUiState(
                    ready = true,
                    localUrl = "http://127.0.0.1:$port/",
                    peers = runCatching { live.status().numPeers() }.getOrDefault(0),
                    progress = runCatching { live.status().progress() }.getOrDefault(0f),
                )
                Log.i(TAG, "reused live handle for $infoHash")
                return@withContext true
            }

            val announces = (trackers.filter { it.contains("://") } + FALLBACK_TRACKERS).distinct()
            Log.i(TAG, "open $infoHash with ${trackers.size} stream trackers (${announces.size} total)")

            rootDir = File(saveDir, infoHash.lowercase()).apply { mkdirs() }

            val existing = runCatching { session?.find(hash) }.getOrNull()
            if (existing == null) {
                handle?.let { h -> runCatching { session?.remove(h) } }
                handle = null
                _state.value = TorrentUiState()

                val magnet = buildString {
                    append("magnet:?xt=urn:btih:").append(infoHash)
                    announces.forEach { append("&tr=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
                }

                // NEVER use NO_VERIFY_FILES here. That flag tells libtorrent to
                // trust whatever is already on disk for this torrent without
                // hashing it. Combined with the remove-then-re-add cycle below it
                // was catastrophic: a previous session leaves a pre-allocated but
                // mostly unwritten sparse file, libtorrent marks every piece as
                // present, and the local server then feeds ExoPlayer gigabytes of
                // 0x00 bytes. Measured on the TV: a 775 MB mp4 that occupied only
                // 15 MB on disk and read back as pure zeros at every offset, while
                // libtorrent happily reported the torrent as complete.
                //
                // ExoPlayer's reaction was UnrecognizedInputFormatException (no
                // extractor could read an all-zero stream), which the player treats
                // as a dead source: retry three times, switch source, hit the same
                // zeros, switch again - the endless "keeps switching source and
                // seeding not playing" cascade. Verifying is the only safe default.
                val downloadFlags = TorrentFlags.AUTO_MANAGED
                    .or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                Log.i(TAG, "adding magnet with ${announces.size} trackers immediately at t=0")
                val added = runCatching { session?.download(magnet, rootDir!!, downloadFlags) }
                    .onFailure { Log.w(TAG, "download(magnet) failed", it) }
                    .isSuccess

                if (!added) {
                    _state.value = _state.value.copy(error = "could not add torrent")
                    return@withContext false
                }
            } else {
                Log.i(TAG, "Reusing prewarmed handle for $infoHash")
                _state.value = TorrentUiState(
                    peers = runCatching { existing.status().numPeers() }.getOrDefault(0)
                )
            }

            // Immediately announce to all trackers simultaneously on tier 0
            applyTrackers(hash, announces)

            // Only fetch the file the player actually needs. Season packs otherwise
            // re-hash and re-download everything, which is what made large titles
            // sit in "CHECKING_FILES" for minutes on a TV.
            runCatching { session?.find(hash) }.getOrNull()?.let { h0 ->
                runCatching { h0.torrentFile() }.getOrNull()?.let { info0 ->
                    val files = info0.files()
                    val target = resolvePlayableIndex(info0, fileIdx)
                    for (i in 0 until files.numFiles()) {
                        runCatching {
                            h0.filePriority(
                                i,
                                if (i == target) org.libtorrent4j.Priority.DEFAULT
                                else org.libtorrent4j.Priority.IGNORE
                            )
                        }
                    }
                }
            }

            var h: TorrentHandle? = null
            // If prewarm already fetched metadata, hand the URL over immediately.
            // Otherwise wait for metadata. The old 12s ceiling applied even when
            // metadata was already on disk, which is exactly the case the detail
            // screen's prewarm sets up - so Play sat on a black screen for 12s
            // with nothing left to wait for.
            val prewarmed = runCatching {
                session?.find(hash)?.let { runCatching { it.torrentFile() }.getOrNull() != null }
            }.getOrNull() == true
            val metadataDeadline = System.currentTimeMillis() + if (prewarmed) 1_500 else 8_000
            var ticks = 0
            var lastAnnounce = System.currentTimeMillis()
            while (System.currentTimeMillis() < metadataDeadline) {
                h = runCatching { session?.find(hash) }.getOrNull()
                val st = runCatching { h?.status() }.getOrNull()
                if (st != null) {
                    _state.value = _state.value.copy(
                        peers = st.numPeers(),
                        downloadRate = st.downloadRate(),
                        progress = st.progress(),
                    )
                    if (runCatching { st.hasMetadata() }.getOrDefault(false)) {
                        Log.i(TAG, "metadata ready after ${ticks * 100}ms (peers=${st.numPeers()})")
                        break
                    }
                }
                if (System.currentTimeMillis() - lastAnnounce > 3_000) {
                    runCatching {
                        h?.forceDHTAnnounce()
                        h?.forceLSDAnnounce()
                        h?.forceReannounce()
                    }
                    lastAnnounce = System.currentTimeMillis()
                }
                ticks++
                Thread.sleep(100)
            }
            val resolvedHandle = h
            val info = runCatching { resolvedHandle?.torrentFile() }.getOrNull()
            if (resolvedHandle == null || info == null) {
                Log.w(TAG, "metadata timeout (handle=${resolvedHandle != null})")
                // Remember the failure so the player and prewarm can skip this
                // source next time instead of burning another full timeout.
                deadMetadata.add(infoHash.lowercase())
                _state.value = _state.value.copy(error = "No peers found for this torrent")
                return@withContext false
            }
            deadMetadata.remove(infoHash.lowercase())

            // Drop every other torrent now that playback owns the session.
            //
            // Prewarm adds up to three candidate torrents per detail visit and never
            // removed them. After browsing a few titles the session held a dozen
            // half-live torrents all competing for the same connection budget, so
            // the chosen one could only hold 1-2 peers and timed out on metadata.
            // One active download, full bandwidth, is what makes Play fast.
            //
            // Prune BEFORE reassigning `handle`: pruneInactive parks the previous
            // playback handle, and it can only see it while `handle` still points
            // at it. Assigning first orphaned the old torrent, which then kept
            // downloading alongside the new one at full speed.
            pruneInactive(keep = resolvedHandle)
            handle = resolvedHandle
            resolvedHandle.setFlags(TorrentFlags.AUTO_MANAGED)
            resolvedHandle.resume()
            resolvedHandle.setSequentialRange(0)

            val files = info.files()
            multiFile = (0 until files.numFiles()).map { files.filePath(it) }
            val target = resolvePlayableIndex(info, fileIdx)
            playableFileIdx = target
            for (i in 0 until files.numFiles()) {
                runCatching {
                    resolvedHandle.filePriority(
                        i,
                        if (i == target) org.libtorrent4j.Priority.DEFAULT
                        else org.libtorrent4j.Priority.IGNORE
                    )
                }
            }

            // Immediately prioritize head pieces (first + 3) and container tail piece for instant playback!
            val pieceLen = info.pieceLength()
            val fileOffset = files.fileOffset(target)
            val fileSize = files.fileSize(target)
            val firstPiece = (fileOffset / pieceLen).toInt()
            val fileEndPiece = ((fileOffset + fileSize - 1) / pieceLen).toInt()
            for (p in firstPiece..minOf(firstPiece + 3, fileEndPiece)) {
                runCatching {
                    resolvedHandle.piecePriority(p, org.libtorrent4j.Priority.TOP_PRIORITY)
                    resolvedHandle.setPieceDeadline(p, 0)
                }
            }
            runCatching {
                resolvedHandle.piecePriority(fileEndPiece, org.libtorrent4j.Priority.TOP_PRIORITY)
                resolvedHandle.setPieceDeadline(fileEndPiece, 0)
            }

            ensureServer()
            healZeroedPayload(resolvedHandle, info, target)
            StorageJanitor.protect(rootDir)
            _state.value = TorrentUiState(
                ready = true,
                localUrl = "http://127.0.0.1:$port/",
                peers = runCatching { resolvedHandle.status().numPeers() }.getOrDefault(0),
            )
            true
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                _state.value = _state.value.copy(
                    error = t.message?.take(140) ?: "Torrent engine error",
                )
                false
            }
        }

    /**
     * Pre-connect to DHT, trackers and peer swarm in the background while the user
     * is browsing the DetailScreen so metadata and piece 0 are already hot when Play is clicked.
     */
    fun prewarm(infoHash: String, trackers: List<String>, saveDir: File, fileIdx: Int = 0) {
        if (!start()) return
        // Don't spend bandwidth on a source that already proved dead.
        if (deadMetadata.contains(infoHash.lowercase())) return
        val hash = Sha1Hash.parseHex(infoHash) ?: return
        val existing = runCatching { session?.find(hash) }.getOrNull()
        // Already warming or already served: nothing to add.
        if (existing != null) {
            val hasMeta = runCatching { existing.torrentFile() }.getOrNull() != null
            if (hasMeta || runCatching { existing.status().hasMetadata() }.getOrDefault(false)) return
        }

        val announces = (trackers.filter { it.contains("://") } + FALLBACK_TRACKERS).distinct()
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(infoHash)
            announces.forEach { append("&tr=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        }
        val targetDir = File(saveDir, infoHash.lowercase()).apply { mkdirs() }
        // Same reasoning as open(): NO_VERIFY_FILES would trust a stale sparse
        // file left by a previous session, so prewarm would "succeed" instantly
        // and hand the player a URL that serves zeros.
        val downloadFlags = TorrentFlags.AUTO_MANAGED
            .or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val added = runCatching { session?.download(magnet, targetDir, downloadFlags) }.isSuccess
        if (!added) return
        runCatching { session?.find(hash) }.getOrNull()
            ?.let { prewarmedHandles[infoHash.lowercase()] = it }

        applyTrackers(hash, announces)
        Log.i(TAG, "prewarm active for $infoHash with ${announces.size} trackers")
        // Metadata is 2-20 KB. Pulling it here - while the viewer is still reading
        // the detail page - is what lets Play resolve instantly: open() would
        // otherwise spend its whole timeout discovering peers from scratch.
        Thread({
            val deadline = System.currentTimeMillis() + PREWARM_METADATA_BUDGET_MS
            var announced = System.currentTimeMillis()
            while (System.currentTimeMillis() < deadline && running) {
                val h = runCatching { session?.find(hash) }.getOrNull() ?: break
                if (runCatching { h.status().hasMetadata() }.getOrDefault(false)) {
                    val info = runCatching { h.torrentFile() }.getOrNull() ?: break
                    val files = info.files()
                    val target = fileIdx.coerceIn(0, (files.numFiles() - 1).coerceAtLeast(0))
                    for (i in 0 until files.numFiles()) {
                        runCatching {
                            h.filePriority(
                                i,
                                if (i == target) org.libtorrent4j.Priority.DEFAULT
                                else org.libtorrent4j.Priority.IGNORE
                            )
                        }
                    }
                    val pieceLen = info.pieceLength()
                    val fileOffset = files.fileOffset(target)
                    val fileSize = files.fileSize(target)
                    val firstPiece = (fileOffset / pieceLen).toInt()
                    val fileEndPiece = ((fileOffset + fileSize - 1) / pieceLen).toInt()
                    for (p in firstPiece..minOf(firstPiece + 3, fileEndPiece)) {
                        runCatching {
                            h.piecePriority(p, org.libtorrent4j.Priority.TOP_PRIORITY)
                            h.setPieceDeadline(p, 0)
                        }
                    }
                    runCatching {
                        h.piecePriority(fileEndPiece, org.libtorrent4j.Priority.TOP_PRIORITY)
                        h.setPieceDeadline(fileEndPiece, 0)
                    }
                    Log.i(TAG, "prewarm metadata + head pieces ready for $infoHash")
                    deadMetadata.remove(infoHash.lowercase())
                    break
                }
                // Keep the DHT/tracker search alive; a single announce often goes
                // out before any DHT node answers, and then nothing is retried.
                if (System.currentTimeMillis() - announced > 2_500) {
                    runCatching {
                        h.forceDHTAnnounce()
                        h.forceLSDAnnounce()
                        h.forceReannounce()
                    }
                    announced = System.currentTimeMillis()
                }
                Thread.sleep(150)
            }
            // Timed out without metadata: this source is dead, don't retry it.
            if (running && runCatching { session?.find(hash)?.status()?.hasMetadata() }.getOrNull() != true) {
                deadMetadata.add(infoHash.lowercase())
                Log.w(TAG, "prewarm could not find metadata for $infoHash - marking dead")
            }
        }, "prewarm-worker").apply { isDaemon = true; start() }
    }

    /** Add announce URLs to the live handle and announce now. */
    private fun applyTrackers(hash: Sha1Hash, trackers: List<String>) {
        repeat(15) {
            val h = runCatching { session?.find(hash) }.getOrNull() ?: return@repeat
            val existing = runCatching { h.trackers().map { it.url() } }.getOrDefault(emptyList())
            val missing = trackers.filterNot { existing.contains(it) }
            missing.forEach { url ->
                // Use tier 0 for all trackers so libtorrent announces to ALL trackers in parallel!
                runCatching { h.addTracker(AnnounceEntry(url).apply { tier(0.toShort()) }) }
            }
            if (missing.isNotEmpty()) Log.i(TAG, "added ${missing.size} tier-0 trackers to handle")
            runCatching {
                h.forceDHTAnnounce()
                h.forceLSDAnnounce()
                h.forceReannounce(0, -1, TorrentHandle.IGNORE_MIN_INTERVAL)
            }
            return
        }
    }

    fun urlFor(fileIdx: Int = 0): String? =
        if (port == 0) null else "http://127.0.0.1:$port/${playableFileIdx ?: fileIdx}"

    /**
     * Pick the file that is actually the movie/episode inside a torrent.
     *
     * A magnet's `so=` / file index is only a hint and is frequently stale or
     * points at a featurette, sample or subtitle track. On the TV this showed up
     * as a 143-minute film reporting a 21-minute duration, because the index
     * landed on a bonus clip. The real video is reliably the largest media file,
     * so that wins; the requested index is honoured only when it already is the
     * largest, or when the torrent holds a single playable file.
     */
    private fun resolvePlayableIndex(info: TorrentInfo, requested: Int): Int {
        val files = info.files()
        val count = files.numFiles()
        if (count <= 1) return 0

        val playable = (0 until count).mapNotNull { i ->
            val path = runCatching { files.filePath(i) }.getOrNull() ?: return@mapNotNull null
            val lower = path.lowercase()
            val isMedia = VIDEO_EXT.any { lower.endsWith(it) }
            if (!isMedia) null
            else i to runCatching { files.fileSize(i) }.getOrDefault(0L)
        }
        if (playable.isEmpty()) {
            // Nothing recognisably video: fall back to the largest file by size.
            val best = (0 until count).maxByOrNull { runCatching { files.fileSize(it) }.getOrDefault(0L) }
            return (best ?: requested).coerceIn(0, count - 1)
        }

        val largest = playable.maxByOrNull { it.second }!!.first
        // Trust the caller only when their pick is itself a substantial media file.
        // Anything under 60 MB at this point is a sample or extra, not the feature.
        val requestedOk = playable.firstOrNull { it.first == requested }
        if (requestedOk != null && requestedOk.second >= MIN_FEATURE_BYTES) return requestedOk.first
        return largest
    }

    /**
     * True when the first 64 bytes of [file] contain real container data.
     *
     * Every video container starts with a non-zero magic number (Matroska begins
     * 1A 45 DF A3, MP4 with a box length plus "ftyp"). A pre-allocated but
     * undownloaded payload reads back as pure zeros instead.
     */
    private fun headLooksReal(file: File): Boolean = runCatching {
        if (!file.exists() || file.length() < 64L) return@runCatching false
        RandomAccessFile(file, "r").use { ra ->
            val probe = ByteArray(64)
            val n = ra.read(probe, 0, 64)
            n >= 64 && probe.any { it != 0.toByte() }
        }
    }.getOrDefault(false)

    /**
     * Repair the "libtorrent says it has the bytes, the disk says otherwise" state.
     *
     * This is the bug behind "keeps switching source and seeding but not playing".
     * When a torrent is removed and re-added while its pre-allocated sparse file
     * survives on disk, libtorrent can resume with the piece bitmap believing the
     * head is already complete. `havePiece()` returns true, the HTTP server streams
     * immediately, and ExoPlayer receives a valid 200 with 775 MB of 0x00 - which it
     * reports as UnrecognizedInputFormatException. The player treats that as a dead
     * source, retries, then switches to the next source, which is equally hollowed
     * out, and the loop never ends. Measured on the TV: a 775 MB mp4 occupying only
     * 15 MB of real blocks and reading as zeros at offsets 0, 1 MB, 100 MB, 300 MB,
     * 600 MB and 700 MB.
     *
     * Only act when libtorrent *claims* the head piece is present while the disk
     * disagrees; a genuinely still-downloading head must be left alone.
     */
    private fun healZeroedPayload(h: TorrentHandle, info: TorrentInfo, target: Int) {
        val files = info.files()
        val firstPiece = (files.fileOffset(target) / info.pieceLength()).toInt()
        if (!runCatching { h.havePiece(firstPiece) }.getOrDefault(false)) return

        val file = File(rootDir, files.filePath(target))
        if (headLooksReal(file)) return

        Log.w(
            TAG,
            "payload head is all zeros although piece $firstPiece is marked present " +
                "(${file.length()} bytes apparent) - forcing recheck",
        )
        runCatching { h.forceRecheck() }
    }

    @Synchronized
    private fun ensureServer() {
        if (server != null) return
        server = ServerSocket(0).also { sock ->
            port = sock.localPort
            running = true
            serverThread = Thread({
                while (running) {
                    val client = runCatching { sock.accept() }.getOrNull() ?: continue
                    Thread { serve(client) }.start()
                }
            }, "torrent-http").apply { isDaemon = true; start() }
        }
    }

    /** Minimal HTTP/1.1 server with Range support, backed by torrent piece availability. */
    private fun serve(client: Socket) {
        runCatching { serveInner(client) }
            .onFailure { Log.w(TAG, "serve failed: ${it.message}") }
        runCatching { client.close() }
    }

    private fun serveInner(client: Socket) {
        client.use { c ->
            c.soTimeout = 60_000
            val input = c.getInputStream()
            val out = c.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
            }

            val h = handle ?: return
            val info = runCatching { h.torrentFile() }.getOrNull() ?: return
            val files = info.files()

            // "/" -> playlist-ish list of files
            if (path == "/" || path == "/index.json") {
                val body = multiFile.joinToString(",", "[", "]") { "\"$it\"" }.toByteArray()
                writeHead(out, 200, "OK", "application/json", body.size.toLong(), null)
                out.write(body)
                return
            }

            // `playableFileIdx` wins whenever it is set: it is the file the engine
            // determined is the feature presentation. A magnet's own hint (which
            // becomes a bare "/0" request) is unreliable and on this title pointed
            // at a 21-minute extra instead of the 143-minute movie.
            val idx = playableFileIdx
                ?: path.trimStart('/').substringBefore('?').toIntOrNull()
                ?: 0
            if (idx !in 0 until files.numFiles()) {
                writeHead(out, 404, "Not Found", "text/plain", 0, null); return
            }

            val size = files.fileSize(idx)
            val fileOffset = files.fileOffset(idx)
            val start: Long
            val end: Long
            val status: String
            if (rangeHeader != null) {
                // Reject suffix ranges ("bytes=-N"): the regex below doesn't match them,
                // so start would silently fall back to 0 while we claimed a 206.
                val m = Regex("""bytes=(\d+)-(\d*)""").find(rangeHeader)
                if (m == null) {
                    writeHead(out, 416, "Range Not Satisfiable", "text/plain", 0, "Content-Range: bytes */$size\r\n"); return
                }
                start = m.groupValues[1].toLongOrNull() ?: 0L
                val rawEnd = m.groupValues[2].toLongOrNull()
                end = (rawEnd ?: (size - 1)).coerceAtMost(size - 1)
                if (start > end || start >= size) {
                    writeHead(out, 416, "Range Not Satisfiable", "text/plain", 0, "Content-Range: bytes */$size\r\n"); return
                }
                status = "Partial Content"
            } else {
                start = 0L
                end = size - 1
                status = "OK"
            }
            val contentLength = (end - start + 1).coerceAtLeast(0)

            val extraHeaders = buildString {
                append("Accept-Ranges: bytes\r\n")
                if (rangeHeader != null) append("Content-Range: bytes $start-$end/$size\r\n")
            }

            // Byte offsets are file-relative; libtorrent pieces are torrent-relative.
            val pieceLen = info.pieceLength()
            val file = File(rootDir, files.filePath(idx))

            // Refuse to serve a hollowed-out payload. If libtorrent claims the head
            // piece is present but the file on disk opens with zeros, sending it
            // would hand ExoPlayer an unparseable stream (it reports
            // UnrecognizedInputFormatException) and the player would spend the next
            // minute cycling through sources that fail the same way. Ask for a
            // recheck instead, and fail the request so the player retries later.
            if (start == 0L && !headLooksReal(file)) {
                Log.w(TAG, "refusing to serve: ${file.name} head is zeros despite piece being present")
                if (runCatching { h.havePiece((fileOffset / pieceLen).toInt()) }.getOrDefault(false)) {
                    runCatching { h.forceRecheck() }
                }
                writeHead(out, 503, "Service Unavailable", "text/plain", 0, null)
                return
            }

            // Send the HTTP header immediately so ExoPlayer does not time-out waiting for headers.
            // Then wait for each piece on-demand while streaming; if a piece never arrives just
            // close the connection — ExoPlayer will reconnect with a Range request for the same
            // bytes, giving the torrent engine another chance to deliver them.
            writeHead(
                out,
                if (rangeHeader != null) 206 else 200,
                status,
                mimeFor(files.filePath(idx)),
                contentLength,
                extraHeaders,
            )
            // HEAD must return headers only — no body.
            if (method == "HEAD") return

            var remaining = contentLength
            val buf = ByteArray(BLOCK)
            var pos = start
            runCatching {
                RandomAccessFile(file, "r").use { ra ->
                    while (remaining > 0 && running) {
                        // Never read past the end of the piece we just waited for: bytes
                        // in an undownloaded neighbouring piece are sparse zeros on disk
                        // and would corrupt the stream at every piece boundary.
                        val globalPos = fileOffset + pos
                        val pieceIndex = (globalPos / pieceLen).toLong()
                        val pieceEnd = (pieceIndex + 1).toLong() * pieceLen
                        val availInPiece = (pieceEnd - globalPos).coerceAtLeast(1L)
                        val want = minOf(buf.size.toLong(), remaining, availInPiece).toInt()
                        // Wait for the covering piece; if it never arrives close the connection cleanly
                        // so ExoPlayer issues a Range-retry (not EOF which would signal end-of-stream).
                        if (!awaitBytes(h, pieceLen, globalPos)) break
                        ra.seek(pos)
                        val n = ra.read(buf, 0, want)
                        if (n <= 0) { Thread.sleep(50); continue }
                        out.write(buf, 0, n)
                        out.flush()
                        pos += n
                        remaining -= n
                    }
                }
            }
        }
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "mp3" -> "audio/mpeg"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

    /** Block until the piece covering the torrent-relative [offset] is downloaded, with lookahead window. */
    private fun awaitBytes(h: TorrentHandle, pieceLen: Int, offset: Long): Boolean {
        val piece = (offset / pieceLen).toInt()
        if (runCatching { h.havePiece(piece) }.getOrDefault(false)) return true

        val numPieces = runCatching { h.torrentFile().numPieces() }.getOrDefault(piece + 1)
        // Sliding lookahead window: prioritize the current piece and the next 8 so the
        // download stays ahead of the player's read head.
        runCatching {
            h.piecePriority(piece, org.libtorrent4j.Priority.TOP_PRIORITY)
            h.setPieceDeadline(piece, 0)
            for (ahead in 1..8) {
                val nextP = piece + ahead
                if (nextP < numPieces && !h.havePiece(nextP)) {
                    h.piecePriority(nextP, org.libtorrent4j.Priority.DEFAULT)
                    h.setPieceDeadline(nextP, ahead * 800)
                }
            }
        }

        val deadline = System.currentTimeMillis() + 15_000
        var nextReset = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline && running) {
            if (runCatching { h.havePiece(piece) }.getOrDefault(false)) return true
            // Re-arm the deadline sparingly: hammering it every poll contended
            // libtorrent's session mutex hundreds of times per second.
            val now = System.currentTimeMillis()
            if (now >= nextReset) {
                runCatching { h.setPieceDeadline(piece, 0) }
                nextReset = now + 500
            }
            Thread.sleep(50)
        }
        return false
    }

    /**
     * Wait until contiguous bytes from the start of [fileIdx] are on disk, so
     * ExoPlayer can sniff the container without stalling mid-probe.
     * Enforces smart swarm failover: aborts at 4s if 0 peers, otherwise allows fast prefetch.
     */
    fun awaitPrefetch(
        fileIdx: Int,
        count: Long,
        onProgress: (Float) -> Unit = {},
        timeoutMs: Long = 20_000L,
    ): Boolean {
        val startMs = System.currentTimeMillis()
        while (running) {
            val elapsed = System.currentTimeMillis() - startMs
            val info = runCatching { handle?.torrentFile() }.getOrNull()
            val h = handle
            if (h == null) return false

            val peers = runCatching { h.status().numPeers() }.getOrDefault(0)

            // Only bail if zero peers after a long time (20s)
            if (peers == 0 && elapsed > 20_000) {
                Log.w(TAG, "Prefetch swarm stall: 0 peers after ${elapsed}ms — bailing")
                return false
            }
            // If we have active peers downloading, don't abort! If 20s elapsed with peers, proceed to hand URL to ExoPlayer
            if (elapsed > 20_000) {
                Log.i(TAG, "Prefetch continuing to player with active swarm (${elapsed}ms, peers=$peers)")
                return peers > 0
            }

            // Guard against a stalled swarm holding the player hostage: without this
            // the UI waits the full 20s whenever peers exist but deliver nothing.
            if (elapsed > timeoutMs) {
                Log.i(TAG, "Prefetch timeout after ${elapsed}ms (peers=$peers) - handing over to player")
                return peers > 0
            }

            if (info != null) {
                val pieceLen = info.pieceLength()
                val files = info.files()
                val fIdx = fileIdx.coerceIn(0, (files.numFiles() - 1).coerceAtLeast(0))
                val fileOffset = files.fileOffset(fIdx)
                val fileSize = files.fileSize(fIdx)
                val firstPiece = (fileOffset / pieceLen).toInt()
                val fileEndPiece = ((fileOffset + fileSize - 1) / pieceLen).toInt()

                // Prioritize head pieces (first + 3) and container tail piece
                for (p in firstPiece..minOf(firstPiece + 3, fileEndPiece)) {
                    runCatching { h.setPieceDeadline(p, 0) }
                }
                runCatching { h.setPieceDeadline(fileEndPiece, 0) }

                val haveTail = h.havePiece(fileEndPiece)
                // Require the whole [count]-byte window from the file start to be on
                // disk, not just the first piece — otherwise ExoPlayer starts with
                // ~one piece and stalls immediately when it runs past it.
                val wantBytes = minOf(count, fileSize).coerceAtLeast(1)
                val lastNeededPiece = ((fileOffset + wantBytes - 1) / pieceLen).toInt()
                var contiguous = true
                for (p in firstPiece..lastNeededPiece) {
                    if (!h.havePiece(p)) { contiguous = false; break }
                }

                val pct = if (contiguous) 0.92f else (0.15f + (peers.toFloat() / 15f).coerceAtMost(0.65f))
                _state.value = _state.value.copy(bufferProgress = pct)
                onProgress(pct)

                if (contiguous) {
                    Log.i(TAG, "Prefetch ready for fileIdx=$fIdx in ${elapsed}ms (head..$lastNeededPiece tail=$haveTail peers=$peers)")
                    return true
                }
            }
            Thread.sleep(40)
        }
        return false
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun writeHead(
        out: java.io.OutputStream,
        code: Int,
        status: String,
        mime: String,
        length: Long,
        extra: String?,
    ) {
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $length\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            extra?.let { append(it) }
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.flush()
    }

    private fun pollStats() {
        var ticks = 0
        while (session != null) {
            val s = session ?: break
            runCatching {
                val h = handle
                val st = h?.status()
                if (st != null) {
                    _state.value = _state.value.copy(
                        progress = st.progress(),
                        downloadRate = st.downloadRate(),
                        peers = st.numPeers(),
                    )
                }
                // Diagnostics: peer discovery is the whole game here.
                if (ticks % 5 == 0) {
                    val trackers = runCatching { h?.trackers() }.getOrNull().orEmpty()
                    Log.i(
                        TAG,
                        "stats dht=${runCatching { s.isDhtRunning() }.getOrDefault(false)} " +
                            "nodes=${runCatching { s.dhtNodes() }.getOrDefault(-1)} " +
                            "peers=${st?.numPeers() ?: -1} seeds=${st?.numSeeds() ?: -1} " +
                            "trackers=${trackers.size} down=${st?.downloadRate() ?: -1} " +
                            "state=${st?.state()}",
                    )
                    if (trackers.isNotEmpty()) {
                        Log.i(TAG, "trackers: " + trackers.take(4).joinToString(", ") { it.url() })
                    }
                }
                ticks++
            }
            Thread.sleep(1000)
        }
    }

    override fun types(): IntArray = intArrayOf(
        AlertType.ADD_TORRENT.swig(),
        AlertType.METADATA_RECEIVED.swig(),
        AlertType.TORRENT_FINISHED.swig(),
        AlertType.STATE_CHANGED.swig(),
        AlertType.PIECE_FINISHED.swig(),
    )

    override fun alert(alert: Alert<*>) {
        when (alert) {
            is AddTorrentAlert -> {
                val err = alert.error()
                Log.d(TAG, "add torrent isError=${err.isError} msg=${err.message}")
            }
            is MetadataReceivedAlert -> Log.d(TAG, "metadata received")
            is TorrentFinishedAlert -> Log.d(TAG, "torrent finished")
            is PieceFinishedAlert -> Unit
            is StateChangedAlert -> {
                val h = runCatching { alert.handle() }.getOrNull() ?: return
                val st = runCatching { h.status() }.getOrNull() ?: return
                _state.value = _state.value.copy(
                    progress = st.progress(),
                    downloadRate = st.downloadRate(),
                    peers = st.numPeers(),
                )
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        serverThread = null
        statsThread = null
        handle?.let { runCatching { session?.remove(it) } }
        handle = null
        runCatching { session?.stop() }
        session = null
        _state.value = TorrentUiState()
    }
}
