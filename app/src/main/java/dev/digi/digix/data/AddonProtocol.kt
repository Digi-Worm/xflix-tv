package dev.digi.digix.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Addon protocol constants
object Protocol {
    const val ADDON_BASE = "https://cdn.strem.io/addons"
    const val MANIFEST_PATH = "/manifest.json"
    const val CATALOG_PATH = "/catalog/{type}/{id}"
    const val META_PATH = "/meta/{type}/{id}"
    const val STREAM_PATH = "/stream/{type}/{id}"
    const val SUBTITLES_PATH = "/subtitles/{type}/{id}"
    const val PLAYER_EVENT_PATH = "/player/{type}/{id}"

    // Supported types
    val MOVIE_TYPES = listOf("movie", "short")
    val SERIES_TYPES = listOf("series", "season", "episode")
    val LIVE_TV_TYPES = listOf("channel", "tv")
}

// Manifest per the public addon protocol specification
@Serializable
data class AddonManifest(
    val id: String,  // e.g. "org.example.movies"
    val name: String,
    val description: String = "",
    val version: String,
    val resources: List<Resource>,
    val catalogs: List<CatalogDefinition> = emptyList(),
    val types: List<String> = emptyList(),
    val behaviorHints: BehaviorHints = BehaviorHints()
)

@Serializable(with = ResourceSerializer::class)
data class Resource(
    val name: String,  // "catalog", "meta", "stream", etc.
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList()
)

/**
 * The protocol allows `resources` entries to be either a bare string
 * ("catalog") or an object ({ "name": "catalog", "types": [...] }).
 */
object ResourceSerializer : KSerializer<Resource> {
    @OptIn(kotlinx.serialization.InternalSerializationApi::class)
    override val descriptor = buildSerialDescriptor("Resource", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Resource) {
        val obj = buildJsonObject {
            put("name", JsonPrimitive(value.name))
            if (value.types.isNotEmpty()) {
                put("types", buildJsonArray { value.types.forEach { add(JsonPrimitive(it)) } })
            }
            if (value.idPrefixes.isNotEmpty()) {
                put("idPrefixes", buildJsonArray { value.idPrefixes.forEach { add(JsonPrimitive(it)) } })
            }
        }
        encoder.encodeSerializableValue(JsonObject.serializer(), obj)
    }

    override fun deserialize(decoder: Decoder): Resource {
        val el: JsonElement = (decoder as JsonDecoder).decodeJsonElement()
        return if (el is JsonPrimitive) {
            Resource(name = el.content)
        } else {
            val o = el.jsonObject
            Resource(
                name = o["name"]?.jsonPrimitive?.content ?: "",
                types = o["types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                idPrefixes = o["idPrefixes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            )
        }
    }
}

@Serializable
data class CatalogDefinition(
    val id: String,  // e.g. "featured"
    val type: String, // "movie", "series", etc.
    val name: String? = null,
    val extra: List<CatalogExtra> = emptyList(),
    // Defaults this client applies when a catalog needs a required extra.
    val defaults: Map<String, String> = emptyMap(),
)

@Serializable
data class CatalogExtra(
    val name: String = "",
    val options: List<String> = emptyList(),
    val isRequired: Boolean = false,
)

@Serializable
data class BehaviorHints(
    val logo: String? = null,
    val epgProvider: Boolean = false
)

// Catalog response
@Serializable
data class CatalogResponse(
    val metas: List<Meta>,
    val extras: List<kotlinx.serialization.json.JsonElement> = emptyList()
)

// Meta/Item info
@Serializable
data class Meta(
    val id: String,  // e.g. "tt0120338"
    val name: String,
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val releaseInfo: ReleaseInfo? = null,
    @Serializable(with = LenientIntSerializer::class)
    val runtime: Int? = null,
    @Serializable(with = StringOrListSerializer::class)
    val genre: List<String> = emptyList(),
    @Serializable(with = StringOrListSerializer::class)
    val country: List<String> = emptyList(),
    val trailer: String? = null,
    @kotlinx.serialization.SerialName("imdb_id")
    val imdbId: String? = null,
    val videos: List<Video>? = null,
    val releaseDate: String? = null,  // ISO8601 or YYYY-MM-DD
    val upcoming: Boolean = false,
    val addonId: String? = null,
    val addonName: String? = null,
    /** URL of the addon whose catalog produced this item — used to fetch its full meta. */
    val sourceAddon: String? = null,
    /** Catalog type ("movie"/"series") the item came from; addons rarely set this themselves. */
    val sourceType: String? = null,
)

/** Addons vary on scalars: "43 min", "43", or 43. */
object LenientIntSerializer : KSerializer<Int?> {
    @OptIn(kotlinx.serialization.InternalSerializationApi::class)
    override val descriptor = buildSerialDescriptor("LenientInt", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }

    override fun deserialize(decoder: Decoder): Int? {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when {
            el is JsonPrimitive && el.isString -> parseDuration(el.content)
            el is JsonPrimitive -> el.content.toIntOrNull()
            else -> null
        }
    }

    private val HM = Regex("""(\d+)\s*h(?:our)?s?\s*(\d+)?""", RegexOption.IGNORE_CASE)

    /**
     * TMDB sends runtime as "2h33min". Taking the first number (the old
     * behaviour) produced "2 min" for a two-and-a-half-hour film, so hours are
     * folded into minutes before falling back to a plain digit read.
     */
    private fun parseDuration(raw: String): Int? {
        HM.find(raw)?.let { m ->
            val hours = m.groupValues[1].toIntOrNull() ?: 0
            val mins = m.groupValues[2].toIntOrNull() ?: 0
            val total = hours * 60 + mins
            if (total > 0) return total
        }
        return Regex("""\d+""").find(raw)?.value?.toIntOrNull()
    }
}

/** Addons send genres/countries as either a string or an array. */
object StringOrListSerializer : KSerializer<List<String>> {
    @OptIn(kotlinx.serialization.InternalSerializationApi::class)
    override val descriptor = buildSerialDescriptor("StringOrList", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeSerializableValue(ListSerializer(String.serializer()), value)
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when {
            el is JsonPrimitive -> listOf(el.content).filter { it.isNotBlank() }
            el is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { c -> c.isNotBlank() } }
            else -> emptyList()
        }
    }
}

@Serializable(with = ReleaseInfoSerializer::class)
data class ReleaseInfo(
    val type: String = "",  // "movie", "series", "episode"
    val title: String? = null,
    val year: Int? = null,
    var rating: Int? = null,
    var provider: String? = null,
    var language: String? = null,
    var subs: List<String> = emptyList(),
    var audio: List<String> = emptyList()
)

/** Some addons send releaseInfo as a bare string ("1997") instead of an object. */
object ReleaseInfoSerializer : KSerializer<ReleaseInfo> {
    @OptIn(kotlinx.serialization.InternalSerializationApi::class)
    override val descriptor = buildSerialDescriptor("ReleaseInfo", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ReleaseInfo) {
        encoder.encodeSerializableValue(ReleaseInfoObjectSurrogate.serializer(), value.toSurrogate())
    }

    override fun deserialize(decoder: Decoder): ReleaseInfo {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when {
            el is JsonPrimitive -> ReleaseInfo(
                year = Regex("""\d{4}""").find(el.content)?.value?.toIntOrNull()
            )
            else -> Json.decodeFromJsonElement(ReleaseInfoObjectSurrogate.serializer(), el).toReleaseInfo()
        }
    }

    private fun ReleaseInfo.toSurrogate() = ReleaseInfoObjectSurrogate(
        type = type, title = title, year = year, rating = rating,
        provider = provider, language = language,
    )

    private fun ReleaseInfoObjectSurrogate.toReleaseInfo() = ReleaseInfo(
        type = type, title = title, year = year, rating = rating,
        provider = provider, language = language,
    )
}

@Serializable
internal data class ReleaseInfoObjectSurrogate(
    val type: String = "",
    val title: String? = null,
    @Serializable(with = LenientIntSerializer::class) val year: Int? = null,
    @Serializable(with = LenientIntSerializer::class) val rating: Int? = null,
    val provider: String? = null,
    val language: String? = null,
)

@Serializable
data class Video(
    val id: String,  // series video id, often same as episode
    val name: String? = null,
    val title: String? = null,
    var releaseDate: String? = null,
    var runtime: Int? = null,
    var season: Int? = null,
    var episode: Int? = null,
    var trailer: String? = null
)

// Stream providers
@Serializable
data class StreamResponse(
    val streams: List<Stream>,
    val extra: List<kotlinx.serialization.json.JsonElement> = emptyList()
)

@Serializable
data class Stream(
    val name: String? = null,
    val title: String? = null,
    // Exactly one of url / infoHash / externalUrl is present.
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,         // file index if multi-file
    val externalUrl: String? = null,  // YouTube/other platform
    val ytId: String? = null,         // YouTube ID
    /** Tracker/DHT hints, e.g. "tracker:udp://...", "dht:<hash>". */
    val sources: List<String> = emptyList(),
    val behaviorHints: StreamBehavior? = null
) {
    /** Human-readable identifier used in the source list. */
    val displayName: String
        get() = name?.replace("\n", " · ") ?: title?.take(80) ?: "Source"

    /** Announce URLs carried by the stream. Without these a magnet has no peers to find. */
    val trackers: List<String>
        get() = sources
            .asSequence()
            .map { if (it.startsWith("tracker:", true)) it.substringAfter(':').trim() else it }
            .filter { it.contains("://") }
            .distinct()
            .toList()

    /** Everything addons stuff their metadata into, lowercased once. */
    private val haystack: String
        get() = listOfNotNull(name, title, behaviorHints?.filename)
            .joinToString(" ")
            .lowercase()

    /** Vertical resolution advertised by the addon; 0 when it says nothing. */
    val resolution: Int
        get() {
            val t = haystack
            return when {
                QUALITY_4K.containsMatchIn(t) -> 2160
                t.contains("1080p") -> 1080
                t.contains("720p") -> 720
                t.contains("480p") -> 480
                t.contains("360p") -> 360
                QUALITY_CAM.containsMatchIn(t) -> 240
                else -> 0
            }
        }

    /** Short badge used in the source list ("4K", "1080p", …). */
    val qualityLabel: String
        get() = when (resolution) {
            2160 -> "4K"
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "CAM"
            else -> ""
        }

    /** Torrentio publishes "👤 352" — pull the number out so the list can sort on it. */
    val seeders: Int
        get() = SEEDERS.find(haystack)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    val sizeLabel: String?
        get() = SIZE.find(haystack)?.groupValues?.getOrNull(1)?.trim()?.uppercase()

    val sizeBytes: Long
        get() {
            val match = SIZE.find(haystack) ?: return 0L
            val numStr = match.groupValues.getOrNull(1)?.trim() ?: return 0L
            val num = numStr.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
            val lower = numStr.lowercase()
            return when {
                lower.endsWith("gb") -> (num * 1024 * 1024 * 1024).toLong()
                lower.endsWith("mb") -> (num * 1024 * 1024).toLong()
                lower.endsWith("kb") -> (num * 1024).toLong()
                else -> (num * 1024 * 1024 * 1024).toLong()
            }
        }

    /**
     * Compute a fitness score for Android TV playback based on TV hardware and network capability.
     */
    fun score(preferFallback: Boolean = false): Double {
        // Unplayable streams (e.g. external browser redirects) must never be auto-selected
        if (url.isNullOrBlank() && infoHash.isNullOrBlank()) return -99999.0

        var s = 0.0
        val seeds = seeders
        val size = sizeBytes
        val res = resolution

        // Swarm health is the single biggest predictor of time-to-first-frame.
        // Measured on this TV: a 1-seed torrent sat at 0% for ~40s while a
        // 8-seed torrent started in under 2s. So thin swarms are pushed down
        // hard enough that any healthy alternative wins, even at lower
        // resolution or larger size.
        s += minOf(seeds, 250) * 2.5
        when {
            seeds == 0 -> s -= 400.0   // no announce data at all: worst case
            seeds < 3 -> s -= 300.0    // effectively dead: 1-2 peers cannot feed a video
            seeds < 5 -> s -= 180.0    // risky: may start, will likely stall
            seeds < 10 -> s -= 40.0    // usable but slow to ramp up
        }
        if (seeds >= 50) s += 35.0

        // Direct HTTP & Debrid cached streams start in <200ms with zero peer handshake delay
        if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) s += 500.0
        if (isCached) s += 350.0

        // Web-DL & FastStart releases have index atoms at byte 0 and start instantly (< 2s)
        val isWeb = haystack.contains("web-dl") || haystack.contains("webrip") || haystack.contains("web-rip")
        if (isWeb) s += 60.0
        if (haystack.contains("remux") && size > 15_000_000_000L) s -= 80.0

        if (preferFallback) {
            // Degraded network mode: prioritize compact 1080p / 720p with high seeds
            when (res) {
                1080 -> s += 70.0
                720 -> s += 60.0
                2160 -> s += 10.0
                else -> s += 20.0
            }
            if (size in 500_000_000L..4_000_000_000L) s += 50.0
            else if (size > 8_000_000_000L) s -= 60.0
        } else {
            // Standard optimal mode: fast Web-DL / HEVC / high seed 1080p & 4K
            when (res) {
                2160 -> {
                    if (seeds >= 25 && (size == 0L || size <= 12_000_000_000L)) {
                        s += 75.0
                    } else if (size > 22_000_000_000L) {
                        s -= 100.0 // Heavy remuxes stall TV Wi-Fi
                    } else {
                        s += 40.0
                    }
                }
                1080 -> {
                    s += 65.0
                    if (seeds >= 15) s += 20.0
                }
                720 -> s += 30.0
                else -> s += 15.0
            }
            // Sweet spot: 1.2 GB - 5.5 GB releases download faster than playback and start instantly
            if (size in 1_000_000_000L..5_500_000_000L) s += 45.0
            else if (size > 25_000_000_000L) s -= 120.0
        }

        if (res == 240) s -= 200.0

        // Codec compatibility. This outweighs every other factor, because an
        // unplayable file is not a slow file - it never starts at all.
        //
        // Measured on this TV: a 1080p 10-bit x265 release downloaded at 1.4 MB/s
        // with 15 peers while the player sat on 00:00 with no duration. MediaCodec
        // logged a continuous "drop frame" loop and the deck read "--:--". The swarm
        // was perfect; the decode was impossible. Rank 10-bit below every alternative
        // so the adaptive picker lands on something that can actually be decoded.
        when {
            isH264 && !isTenBit -> s += 180.0   // safest: SDR 8-bit H.264 decodes everywhere
            isH264 -> s += 40.0                 // 10-bit H.264 is rare and still risky
            isHevc && isTenBit -> s -= 450.0    // hard skip: reliably produces a black screen
            isHevc -> s += 90.0                 // 8-bit HEVC is fine on MTK
            else -> s += 0.0                    // unknown codec: no opinion
        }

        return s
    }

    /** Some addons flag pre-cached torrents with a bolt; those start instantly. */
    val isCached: Boolean
        get() = haystack.contains("⚡") || haystack.contains("cached") || haystack.contains("instant")

    /**
     * True when the release advertises a 10-bit encode ("10Bit", "Hi10P", "Main10").
     *
     * This matters more than resolution on this hardware. The panel ships an MTK
     * HEVC decoder, but a 10-bit x265 file drives it into a permanent "drop frame"
     * loop: ExoPlayer reports no duration at all, the position never leaves 00:00,
     * and MediaCodec floods logcat while the torrent continue downloading at full
     * speed. The viewer sees a black screen over a perfectly healthy swarm.
     */
    val isTenBit: Boolean
        get() = TEN_BIT.containsMatchIn(haystack)

    /** True when the release advertises an x265 / HEVC encode. */
    val isHevc: Boolean
        get() = HEVC.containsMatchIn(haystack)

    /** True when the release advertises an x264 / H.264 encode, which always decodes. */
    val isH264: Boolean
        get() = H264.containsMatchIn(haystack)

    /** Best-effort codec sniff so the UI can show "Atmos" / "DD+" / "DTS". */
    val audioLabel: String?
        get() {
            val t = haystack
            return when {
                t.contains("atmos") -> "Atmos"
                t.contains("truehd") -> "TrueHD"
                t.contains("dts-hd") || t.contains("dtsx") || t.contains("dts-x") -> "DTS-HD"
                t.contains("dts") -> "DTS"
                t.contains("ddp") || t.contains("dd+") || t.contains("eac3") ||
                    t.contains("e-ac3") || t.contains("dd+5.1") -> "DD+"
                t.contains("ac3") || t.contains("ac-3") -> "AC3"
                t.contains("flac") -> "FLAC"
                t.contains("aac") -> "AAC"
                else -> null
            }
        }

    /** Channel layout, e.g. "5.1" or "7.1", when the release name carries one. */
    val channelLabel: String?
        get() = CHANNELS.find(haystack)?.groupValues?.getOrNull(1)

    /** Audio languages detected from stream release title and metadata */
    val audioLanguages: List<String>
        get() {
            val t = haystack
            val detected = mutableListOf<String>()

            if (LANG_HINDI.containsMatchIn(t)) detected += "Hindi"
            if (LANG_ENGLISH.containsMatchIn(t)) detected += "English"
            if (LANG_TAMIL.containsMatchIn(t)) detected += "Tamil"
            if (LANG_TELUGU.containsMatchIn(t)) detected += "Telugu"
            if (LANG_MALAYALAM.containsMatchIn(t)) detected += "Malayalam"
            if (LANG_KANNADA.containsMatchIn(t)) detected += "Kannada"
            if (LANG_BENGALI.containsMatchIn(t)) detected += "Bengali"
            if (LANG_PUNJABI.containsMatchIn(t)) detected += "Punjabi"
            if (LANG_JAPANESE.containsMatchIn(t)) detected += "Japanese"
            if (LANG_KOREAN.containsMatchIn(t)) detected += "Korean"
            if (LANG_SPANISH.containsMatchIn(t)) detected += "Spanish"
            if (LANG_FRENCH.containsMatchIn(t)) detected += "French"
            if (LANG_GERMAN.containsMatchIn(t)) detected += "German"
            if (LANG_ITALIAN.containsMatchIn(t)) detected += "Italian"
            if (LANG_PORTUGUESE.containsMatchIn(t)) detected += "Portuguese"
            if (LANG_RUSSIAN.containsMatchIn(t)) detected += "Russian"

            if (t.contains("dual audio") || t.contains("dual-audio") || t.contains("dualaudio")) {
                if (!detected.contains("Dual Audio")) detected += "Dual Audio"
                if (!detected.contains("Hindi") && (t.contains("hin") || t.contains("bollywood") || t.contains("hindi"))) {
                    detected += "Hindi"
                }
            }
            if (t.contains("multi audio") || t.contains("multi-audio") || t.contains("multiaudio") || t.contains("multi")) {
                if (!detected.contains("Multi Audio")) detected += "Multi Audio"
            }

            if (detected.isEmpty()) {
                detected += "English"
            }
            return detected.distinct()
        }

    fun hasAudio(lang: String): Boolean {
        if (lang.isBlank() || lang.equals("All", ignoreCase = true) || lang.equals("Auto", ignoreCase = true)) return true
        if (lang.equals("Dual Audio", ignoreCase = true)) {
            return haystack.contains("dual") || audioLanguages.contains("Dual Audio") || audioLanguages.size >= 2
        }
        if (lang.equals("Multi Audio", ignoreCase = true)) {
            return haystack.contains("multi") || audioLanguages.contains("Multi Audio") || audioLanguages.size >= 3
        }
        if (audioLanguages.any { it.equals(lang, ignoreCase = true) }) return true
        if (lang.equals("English", ignoreCase = true) && audioLanguages.isEmpty()) return true
        return false
    }

    companion object {
        private val QUALITY_4K = Regex("""\b(2160p|4k|uhd)\b""")
        private val QUALITY_CAM = Regex("""\b(cam|hdcam|ts|hdts|telesync|scr|dvdscr|prehd)\b""")
        /** 10-bit encodes: "10Bit", "10-bit", "Hi10P", "Main10". */
        private val TEN_BIT = Regex("""(10\s*-?\s*bit|hi10p|main\s*10)""")
        /** x265 / HEVC family. */
        private val HEVC = Regex("""\b(hevc|x265|h\.?265|main10)\b""")
        /** x264 / H.264 family - universally decodable on TV silicon. */
        private val H264 = Regex("""\b(x264|h\.?264|avc)\b""")
        private val SEEDERS = Regex("""👤\s*(\d+)""")
        private val SIZE = Regex("""💾\s*([\d.]+\s*[kmgt]b)""")
        private val CHANNELS = Regex("""\b([257]\.[01])\b""")

        private val LANG_HINDI = Regex("""\b(hindi|hin|bollywood)\b""")
        private val LANG_ENGLISH = Regex("""\b(english|eng)\b""")
        private val LANG_TAMIL = Regex("""\b(tamil|tam)\b""")
        private val LANG_TELUGU = Regex("""\b(telugu|tel)\b""")
        private val LANG_MALAYALAM = Regex("""\b(malayalam|mal)\b""")
        private val LANG_KANNADA = Regex("""\b(kannada|kan)\b""")
        private val LANG_BENGALI = Regex("""\b(bengali|ben|bangla)\b""")
        private val LANG_PUNJABI = Regex("""\b(punjabi|pan)\b""")
        private val LANG_JAPANESE = Regex("""\b(japanese|jap|jpn|anime)\b""")
        private val LANG_KOREAN = Regex("""\b(korean|kor|kdrama)\b""")
        private val LANG_SPANISH = Regex("""\b(spanish|spa|latino|castellano)\b""")
        private val LANG_FRENCH = Regex("""\b(french|fre|fra|vff|vfq)\b""")
        private val LANG_GERMAN = Regex("""\b(german|ger|deu|deutsch)\b""")
        private val LANG_ITALIAN = Regex("""\b(italian|ita)\b""")
        private val LANG_PORTUGUESE = Regex("""\b(portuguese|por|pt-br|ptbr)\b""")
        private val LANG_RUSSIAN = Regex("""\b(russian|rus)\b""")
    }
}

/** Collect all available audio languages detected across a list of streams */
fun List<Stream>.availableAudioLanguages(): List<String> {
    val detected = mutableSetOf<String>()
    forEach { s ->
        detected.addAll(s.audioLanguages)
    }
    val priority = listOf(
        "English", "Hindi", "Tamil", "Telugu", "Dual Audio", "Multi Audio",
        "Malayalam", "Kannada", "Japanese", "Korean", "Spanish", "French",
        "German", "Italian", "Portuguese", "Russian"
    )
    return detected.sortedWith(compareBy({ val idx = priority.indexOf(it); if (idx >= 0) idx else 99 }, { it }))
}

/** Find the stream offering the highest video quality & best performance for a target audio language */
fun List<Stream>.findBestForAudio(language: String): Stream? {
    val playable = filter { (!it.url.isNullOrBlank() && it.url.startsWith("http", ignoreCase = true)) || !it.infoHash.isNullOrBlank() }
    val candidates = if (playable.isNotEmpty()) playable else this
    if (candidates.isEmpty()) return null
    if (language.isBlank() || language.equals("All", ignoreCase = true) || language.equals("Auto", ignoreCase = true)) {
        return candidates.maxByOrNull { it.score(preferFallback = false) } ?: candidates.firstOrNull()
    }
    val matching = candidates.filter { it.hasAudio(language) }
    if (matching.isNotEmpty()) {
        return matching.maxByOrNull { it.score(preferFallback = false) }
    }
    return candidates.maxByOrNull { it.score(preferFallback = false) } ?: candidates.firstOrNull()
}

@Serializable
data class StreamBehavior(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,     // grouping key for auto-play, not a flag
    val filename: String? = null,
    val proxyHeaders: Map<String, String> = emptyMap()
)

// Subtitles
@Serializable
data class SubtitlesResponse(
    val subtitles: List<Subtitle>,
    val extra: List<kotlinx.serialization.json.JsonElement> = emptyList()
)

@Serializable
data class Subtitle(
    val id: String,  // OpenSubtitles hash
    val lang: String,
    val url: String? = null,
    val file: String? = null,
    val ext: String? = null,
    val behavior: SubtitleBehavior? = null
)

@Serializable
data class SubtitleBehavior(
    val name: String? = null,
    val subType: String? = null
)

// URL helpers
class AddonUrl(private val baseUrl: String) {
    fun manifestUrl() = baseUrl + Protocol.MANIFEST_PATH

    fun catalogUrl(type: String, id: String, extras: Map<String, String> = emptyMap()): String {
        val path = Protocol.CATALOG_PATH
            .replace("{type}", type)
            .replace("{id}", id)
        return buildUrlWithExtras(path, extras)
    }

    fun metaUrl(type: String, id: String) =
        Protocol.META_PATH
            .replace("{type}", type)
            .replace("{id}", id)

    fun streamUrl(type: String, id: String, extras: Map<String, String> = emptyMap()): String {
        val path = Protocol.STREAM_PATH
            .replace("{type}", type)
            .replace("{id}", id)
        return buildUrlWithExtras(path, extras)
    }

    fun subtitlesUrl(type: String, id: String) =
        Protocol.SUBTITLES_PATH
            .replace("{type}", type)
            .replace("{id}", id)

    private fun buildUrlWithExtras(path: String, extras: Map<String, String>): String {
        return if (extras.isEmpty()) {
            baseUrl + path
        } else {
            val extraStr = AddonRepository.encodeExtraParams(extras)
            baseUrl + path + "/" + extraStr
        }
    }
}

// JSON config
object JsonConfig {
    val instance = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

// Addon repository
object AddonRepository {
    // Real, working addons seeded on first launch, each verified live.
    // Cinemeta/TMDB = catalogs + metadata; Torrentio/TorrentsDB = streams;
    // WatchHub = where-to-watch links; OpenSubtitles = subtitles.
    //
    // Torrentio is deliberately the UNFILTERED endpoint. Pinning a provider list
    // (providers=yts,eztv,...) cut a title from 500 candidates to 361, and for
    // regional cinema from 6 to 5 — that filter was the main cause of "no
    // sources" on Punjabi/Tamil titles. Two independent stream addons also mean
    // a title Torrentio has never indexed still gets served by TorrentsDB
    // (measured: 25 streams for a Punjabi film where Torrentio had 6).
    val defaultAddons = listOf(
        "https://torrentio.strem.fun/manifest.json",
        "https://torrentsdb.com/manifest.json",
        "https://v3-cinemeta.strem.io/manifest.json",
        "https://tmdb.elfhosted.com/manifest.json",
        "https://watchhub.strem.io/manifest.json",
        "https://opensubtitles-v3.strem.io/manifest.json",
    )

    /**
     * Fragments of addon URLs that are worse than the current default and should
     * be retired on upgrade. Checked with `contains` against installed URLs.
     */
    val supersededAddons = listOf("torrentio.strem.fun/providers=")

    // Catalogs blank unless given an extra: supply a sensible default so the
    // row is useful instead of empty.
    val catalogDefaults: Map<String, Map<String, String>> = mapOf(
        "tmdb.language" to mapOf("genre" to "Hindi"),
        "tmdb.top" to emptyMap(),
        "tmdb.trending" to mapOf("genre" to "Week"),
        "tmdb.latest" to emptyMap(),
        "tmdb.year" to mapOf("genre" to "2025"),
        "top" to emptyMap(),
        "year" to mapOf("genre" to "2025"),
        "imdbRating" to emptyMap(),
        "last-videos" to emptyMap(),
        "calendar-videos" to emptyMap(),
    )

    fun validateManifest(manifest: AddonManifest): Boolean {
        if (manifest.id.isBlank() || manifest.name.isBlank() || manifest.version.isBlank()) return false
        if (manifest.resources.isEmpty()) return false
        return true
    }

    fun encodeExtraParams(extras: Map<String, String>): String {
        if (extras.isEmpty()) return ""
        return extras.entries.joinToString("&") { "${it.key}=${encodeURIComponent(it.value)}" }
    }

    private fun encodeURIComponent(s: String): String {
        return s.replace(" ", "%20")
            .replace("&", "%26")
            .replace("?", "%3F")
            .replace("=", "%3D")
            .replace("#", "%23")
            .replace("/", "%2F")
            .replace("+", "%2B")
    }
}