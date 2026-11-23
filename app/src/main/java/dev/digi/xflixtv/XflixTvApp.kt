package dev.digi.xflixtv

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dev.digi.xflixtv.data.AddonClient
import dev.digi.xflixtv.data.AddonStore
import dev.digi.xflixtv.data.LibraryStore
import dev.digi.xflixtv.data.StorageJanitor
import dev.digi.xflixtv.data.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class XflixTvApp : Application(), ImageLoaderFactory {

    lateinit var addonClient: AddonClient
        private set
    lateinit var torrentEngine: TorrentEngine
        private set
    lateinit var addonStore: AddonStore
        private set
    lateinit var libraryStore: LibraryStore
        private set

    /** Stream requested from outside (xflix:// or a launch intent). */
    var deepLinkStream: dev.digi.xflixtv.data.Stream? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        addonClient = AddonClient()
        torrentEngine = TorrentEngine()
        addonStore = AddonStore(this)
        libraryStore = LibraryStore(this)

        // First sweep after start: clears residue from the previous session, then
        // settles into a slow periodic pass so a long binge never fills the box.
        appScope.launch {
            delay(4_000)
            StorageJanitor.sweep(this@XflixTvApp)
        }
        appScope.launch {
            while (isActive) {
                delay(15L * 60 * 1000)
                StorageJanitor.sweep(this@XflixTvApp)
            }
        }

        // Bring up libtorrent and its DHT/tracker bootstrap at launch instead of on
        // the first Play tap. Session startup plus DHT bootstrap is several seconds
        // of pure latency that used to sit between the tap and the first frame.
        appScope.launch {
            runCatching { torrentEngine.start() }
        }
    }

    /**
     * The OS tells us when the box is short on memory. Trimming Coil's bitmap
     * cache here is what keeps the player from being killed mid-stream on the
     * 1 GB TCL models.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        StorageJanitor.onTrim(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        StorageJanitor.onTrim(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A density/locale change invalidates every decoded bitmap; drop them.
        StorageJanitor.trimMemory(this)
    }

    override fun onTerminate() {
        torrentEngine.stop()
        super.onTerminate()
    }

    /**
     * TV boxes have little memory and slow decoders. Coil already sizes requests
     * to the layout, so the win here is bounded caches and no crossfade (which
     * repaints large bitmaps every frame while scrolling).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(false)
        .respectCacheHeaders(false)
        .bitmapConfig(Bitmap.Config.ARGB_8888)
        .memoryCache {
            MemoryCache.Builder(this).maxSizePercent(0.20).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(192L * 1024 * 1024)
                .build()
        }
        .build()

    companion object {
        lateinit var instance: XflixTvApp
            private set

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
