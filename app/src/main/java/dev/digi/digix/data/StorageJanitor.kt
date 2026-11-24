package dev.digi.digix.data

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Environment
import android.util.Log
import coil.Coil
import dev.digi.digix.DigiXApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the TV box from choking on its own cache.
 *
 * Android TV devices ship with 1-2 GB of RAM and a small /data partition, and
 * this app is unusually hungry: a torrent download directory, a Coil image disk
 * cache and a multi-hundred-megabyte decoded bitmap cache. Left alone they grow
 * until the box starts thrashing or the system kills the player mid-episode.
 *
 * The janitor runs on three triggers:
 *  - app start (cheap, bounded),
 *  - a periodic sweep while the app is in the foreground,
 *  - a low-memory callback from the OS, where it trims aggressively.
 *
 * Everything is best-effort: a failure to delete a file must never take the app
 * down, so every filesystem call is wrapped in runCatching.
 */
object StorageJanitor {

    private const val TAG = "StorageJanitor"

    /** Torrent working directories kept on disk; older ones are stream residue. */
    private const val KEEP_TORRENT_DIRS = 2

    /** Torrent working directories older than this are always considered stale. */
    private const val TORRENT_MAX_AGE_MS = 12L * 60 * 60 * 1000

    /** Coil's image cache is capped at 192 MB; reclaim well before that. */
    private const val IMAGE_CACHE_SOFT_LIMIT = 96L * 1024 * 1024

    /**
     * Age after which a downloaded update APK is considered abandoned. Ten
     * minutes is comfortably longer than a 46 MB download plus the time a user
     * needs to confirm the system install dialog.
     */
    private const val UPDATE_INSTALLER_GRACE_MS = 10L * 60 * 1000

    private val sweeping = AtomicBoolean(false)

    /**
     * Directory of the torrent currently being played, if any.
     *
     * The sweeper must never touch this. Deleting a payload directory out from
     * under a live torrent is not a cleanup - libtorrent keeps writing into the
     * now-unlinked inode while the HTTP server's `RandomAccessFile` opens a fresh
     * empty path. The server has already sent its headers by then, so ExoPlayer
     * receives a 200 with a Content-Length and zero bytes of body, which it
     * reports as UnrecognizedInputFormatException and then treats as a dead
     * source. That was one half of the "keeps switching source, seeding but
     * never plays" report.
     */
    @Volatile
    private var protectedDir: File? = null

    /** Called by the torrent engine whenever the active payload directory changes. */
    fun protect(dir: File?) {
        protectedDir = dir?.absoluteFile
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastSweepMs = 0L

    /**
     * Reclaim storage. [aggressive] is used when the OS reports memory pressure
     * and drops every cache it can, including the in-memory bitmap cache.
     */
    suspend fun sweep(context: Context, aggressive: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (!sweeping.compareAndSet(false, true)) return@withContext
            try {
                val now = System.currentTimeMillis()
                if (!aggressive && now - lastSweepMs < 60_000L) return@withContext
                lastSweepMs = now

                val freedTorrent = pruneTorrents(context, aggressive)
                val freedImages = pruneImageCache(context, aggressive)
                val freedCode = pruneCodeCache(context)
                val freedUpdates = pruneUpdateInstallers(context)

                if (aggressive) trimMemory(context)

                val total = freedTorrent + freedImages + freedCode + freedUpdates
                if (total > 0) {
                    Log.i(TAG, "reclaimed ${total / 1024} KB (aggressive=$aggressive)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sweep failed", t)
            } finally {
                sweeping.set(false)
            }
        }

    /**
     * Deletes torrent payload directories that are no longer being played.
     * The player always works out of `filesDir/torrents/<infoHash>`, so anything
     * besides the most recent entries is dead weight.
     */
    private fun pruneTorrents(context: Context, aggressive: Boolean): Long {
        val root = File(context.filesDir, "torrents")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return 0L
        if (dirs.isEmpty()) return 0L

        val now = System.currentTimeMillis()
        // The active torrent is the most recently touched directory; never delete it.
        val ordered = dirs.sortedByDescending { it.lastModified() }
        val keep = if (aggressive) 1 else KEEP_TORRENT_DIRS
        val live = protectedDir

        var freed = 0L
        ordered.forEachIndexed { index, dir ->
            // Never evict the payload the player is streaming from, however old
            // it looks: its mtime stops advancing while playback reads backwards.
            if (live != null && dir.absoluteFile == live) return@forEachIndexed
            val tooOld = now - dir.lastModified() > TORRENT_MAX_AGE_MS
            if (index < keep && !tooOld) return@forEachIndexed
            freed += dirSize(dir)
            runCatching { dir.deleteRecursively() }
                .onFailure { Log.w(TAG, "torrent prune failed: ${dir.name}", it) }
        }
        return freed
    }

    /** Trims Coil's disk cache once it crosses the soft limit. */
    private fun pruneImageCache(context: Context, aggressive: Boolean): Long {
        val dir = File(context.cacheDir, "images")
        val size = dirSize(dir)
        val limit = if (aggressive) IMAGE_CACHE_SOFT_LIMIT / 2 else IMAGE_CACHE_SOFT_LIMIT
        if (size <= limit) return 0L

        // Coil rebuilds the disk cache lazily, so clearing the directory is safe;
        // the loader simply refetches posters it still needs.
        val freed = size
        runCatching {
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }.onFailure { Log.w(TAG, "image cache prune failed", it) }

        // Ask Coil to rebuild its bookkeeping against the now-empty directory.
        runCatching { Coil.imageLoader(context).diskCache?.clear() }
        return freed
    }

    /** Android's own code/HTTP caches inside the app sandbox. */
    private fun pruneCodeCache(context: Context): Long {
        var freed = 0L
        listOf("http", "code_cache").forEach { name ->
            val dir = File(context.cacheDir, name)
            if (dir.exists()) {
                freed += dirSize(dir)
                runCatching { dir.listFiles()?.forEach { it.deleteRecursively() } }
            }
        }
        return freed
    }

    /**
     * Deletes APKs left behind by the self-update flow.
     *
     * DownloadManager never overwrites a destination, so it saves alongside it as
     * `name-1.apk`, `name-2.apk`. Before the filename carried the build number,
     * every update left another ~46 MB installer in
     * `Android/data/.../files/Download/` - on the TV there were six of them,
     * 280 MB, the oldest being a build four versions behind the installed one.
     *
     * That stale file is what the installer picked up, producing a downgrade and
     * the opaque "package appears to be invalid" dialog. Removing them is
     * therefore a correctness fix, not just housekeeping.
     *
     * Files touched in the last [UPDATE_INSTALLER_GRACE_MS] are left alone so a
     * download that is still running or about to be installed is never deleted
     * out from under the installer.
     */
    private fun pruneUpdateInstallers(context: Context): Long {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return 0L
        val files = dir.listFiles() ?: return 0L
        val cutoff = System.currentTimeMillis() - UPDATE_INSTALLER_GRACE_MS
        var freed = 0L
        files.forEach { file ->
            if (!file.isFile) return@forEach
            if (!file.name.startsWith("DigiX-TV-update") || file.extension != "apk") return@forEach
            if (file.lastModified() > cutoff) return@forEach
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                freed += size
                Log.i(TAG, "removed stale installer ${file.name}")
            }
        }
        return freed
    }

    /** Drops Coil's decoded-bitmap cache and lets the GC reclaim it. */
    fun trimMemory(context: Context) {
        runCatching { Coil.imageLoader(context).memoryCache?.clear() }
        runCatching { System.gc() }
    }

    /** Called from the Application when the OS flags memory pressure. */
    fun onTrim(level: Int) {
        val context = runCatching { DigiXApp.instance }.getOrNull() ?: return
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        scope.launch { sweep(context, aggressive = true) }
    }

    /** Total bytes used by a directory tree. */
    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    /** Human-readable size for the Settings screen. */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
