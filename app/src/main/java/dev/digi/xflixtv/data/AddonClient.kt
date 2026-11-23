package dev.digi.xflixtv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Thin HTTP client for the addon protocol. No auth, no account. */
class AddonClient(
    private val http: OkHttpClient = defaultClient()
) {
    companion object {
        private const val TAG = "AddonClient"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val json = JsonConfig.instance

    /**
     * Which addons actually declare a `stream` resource, cached per base URL.
     * A detail screen used to fire a stream request at every installed addon,
     * including catalog-only ones (Cinemeta, TMDB, WatchHub, OpenSubtitles) that
     * always answered 404 — that wasted the first seconds of playback start on
     * requests that could never return a source.
     */
    private val streamCapable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    suspend fun supportsStream(addonUrl: String): Boolean {
        val base = baseUrl(addonUrl)
        if (base.isBlank()) return false
        streamCapable[base]?.let { return it }
        val manifest = fetchManifest(addonUrl)
        // Unknown manifest: keep the addon. Being wrong here costs one HTTP
        // request, whereas dropping a working addon costs the user a source.
        val capable = manifest == null || manifest.resources.any { it.name == "stream" }
        streamCapable[base] = capable
        return capable
    }

    /** Fetch and parse a manifest from any addon URL (with or without /manifest.json). */
    suspend fun fetchManifest(addonUrl: String): AddonManifest? = withContext(Dispatchers.IO) {
        val url = normalizeManifestUrl(addonUrl)
        runCatching {
            val body = get(url) ?: return@runCatching null
            json.decodeFromString<AddonManifest>(body)
        }.onFailure { Log.w(TAG, "manifest failed: $url", it) }.getOrNull()
    }

    suspend fun catalog(addonUrl: String, type: String, id: String, extras: Map<String, String> = emptyMap()): List<Meta> =
        withContext(Dispatchers.IO) {
            val url = AddonUrl(baseUrl(addonUrl)).catalogUrl(type, id, extras) + ".json"
            runCatching {
                val body = get(url) ?: return@runCatching emptyList()
                json.decodeFromString<CatalogResponse>(body).metas
            }.onFailure { Log.w(TAG, "catalog failed: $url", it) }.getOrDefault(emptyList())
        }

    suspend fun meta(addonUrl: String, type: String, id: String): Meta? = withContext(Dispatchers.IO) {
        val base = baseUrl(addonUrl)
        if (base.isBlank()) return@withContext null
        val url = "$base/meta/$type/${java.net.URLEncoder.encode(id, "UTF-8")}.json"
        runCatching {
            val body = get(url) ?: return@runCatching null
            json.decodeFromString<MetaResponse>(body).meta
                ?: json.decodeFromString<CatalogResponse>(body).metas.firstOrNull()
        }.onFailure { Log.w(TAG, "meta failed: $url", it) }.getOrNull()
    }

    suspend fun streams(addonUrl: String, type: String, id: String): List<Stream> = withContext(Dispatchers.IO) {
        val url = AddonUrl(baseUrl(addonUrl)).streamUrl(type, id) + ".json"
        runCatching {
            val body = get(url) ?: return@runCatching emptyList()
            json.decodeFromString<StreamResponse>(body).streams
        }.onFailure { Log.w(TAG, "streams failed: $url", it) }.getOrDefault(emptyList())
    }

    suspend fun subtitles(addonUrl: String, type: String, id: String): List<Subtitle> = withContext(Dispatchers.IO) {
        val url = AddonUrl(baseUrl(addonUrl)).subtitlesUrl(type, id) + ".json"
        runCatching {
            val body = get(url) ?: return@runCatching emptyList()
            json.decodeFromString<SubtitlesResponse>(body).subtitles
        }.onFailure { Log.w(TAG, "subs failed: $url", it) }.getOrDefault(emptyList())
    }

    private fun get(url: String): String? {
        if (!url.startsWith("http", true)) {
            Log.w(TAG, "skipping relative url: $url")
            return null
        }
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
                return null
            }
            return resp.body?.string()
        }
    }

    private fun baseUrl(addonUrl: String): String =
        addonUrl.removeSuffix("/").removeSuffix("/manifest.json")

    private fun normalizeManifestUrl(url: String): String {
        val u = url.trim().removeSuffix("/")
        if (u.endsWith("/manifest.json")) return u
        // Addons are published with a custom URI scheme instead of https://.
        // Any "scheme://host/path" that is not plain http(s) is manifest
        // shorthand, so rewrite it to the equivalent http(s) base URL.
        val schemeSep = u.indexOf("://")
        if (schemeSep > 0) {
            val scheme = u.substring(0, schemeSep).lowercase()
            if (scheme != "http" && scheme != "https") {
                return "https://" + u.substring(schemeSep + 3) + "/manifest.json"
            }
        }
        return "$u/manifest.json"
    }
}

@kotlinx.serialization.Serializable
data class MetaResponse(val meta: Meta?)
