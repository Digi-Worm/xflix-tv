package dev.digi.digix.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire contract for "cast from phone to TV".
 *
 * The phone never streams video to the TV. It sends the *stream descriptor* it
 * would have played itself, and the TV re-resolves that descriptor on-device
 * through [TorrentEngine] - either a direct http(s) URL, or an infoHash plus
 * announce URLs that libtorrent turns into a local HTTP source. Sending a
 * descriptor instead of a media URL is what lets playback survive the phone
 * leaving the room, and it is why no server-side transcoding exists anywhere
 * in this feature.
 *
 * Transport is plain HTTP/1.1 on the LAN, one request per connection:
 *
 *  - `GET  /health` -> `200` [CastStatus] (device name only; the cheap probe
 *    the phone uses to confirm it is talking to a Digi X TV at all)
 *  - `GET  /status` -> `200` [CastStatus] (last playback state the TV published)
 *  - `POST /status` -> body is a [CastStatus] pushed by the sending phone, so
 *    remote callers see the same state the sending device is showing
 *  - `POST /cast`   -> body is a [CastRequest], success answers `200 {"ok":true}`
 *  - `POST /stop`   -> success answers `200 {"ok":true}`
 *
 * Any other path is `404`; a known path with the wrong method is `405`; a body
 * that is not valid JSON for the target type is `400`. Every response carries
 * `Access-Control-Allow-Origin: *`, and `OPTIONS` answers `204`, so the
 * endpoints can be prodded from a desktop browser while debugging.
 *
 * There is no authentication. See the warning on `CastReceiverServer`.
 */
const val CAST_SERVICE_TYPE = "_digixtv._tcp."

/** Preferred port; the receiver falls back to the next few if it is taken. */
const val CAST_DEFAULT_PORT = 47823

/**
 * Which protocol a discovered device speaks.
 *
 * [DIGIX] is this app's own receiver: the phone sends a stream *descriptor* and
 * the TV resolves it itself, so playback survives the phone leaving.
 *
 * [CHROMECAST] is a real Google Cast device. It is a dumb media player: it only
 * plays a URL it can fetch over HTTP(S), which means the phone has to serve the
 * media itself and must stay on the network while casting.
 */
enum class CastKind { DIGIX, CHROMECAST }

/**
 * One discovered TV, as the phone remembers it.
 *
 * [host] is a numeric LAN address because NSD hostnames (`digixtv.local`) do
 * not resolve on every Android version, and [port] is whatever the TV actually
 * bound, not necessarily [CAST_DEFAULT_PORT]. For a Chromecast, [port] is the
 * Cast v2 control port, 8009.
 */
@Serializable
data class CastTarget(
    val name: String,
    val host: String,
    val port: Int,
    val kind: CastKind = CastKind.DIGIX,
)

/**
 * What the phone asks the TV to play.
 *
 * Exactly one of [url] and [infoHash] is expected to be set, mirroring the
 * stream the phone was about to start. [fileIdx] only matters for multi-file
 * torrents. [positionMs] carries a resume point so a cast can pick up where the
 * phone left off.
 */
@Serializable
data class CastRequest(
    val title: String,
    val infoHash: String? = null,
    val fileIdx: Int = 0,
    val url: String? = null,
    val trackers: List<String> = emptyList(),
    val poster: String? = null,
    val positionMs: Long = 0L,
)

/**
 * Playback state the TV reports back.
 *
 * [deviceName] is always present so a `/status` response is self-describing;
 * [title] stays null until something has actually been cast.
 */
@Serializable
data class CastStatus(
    val deviceName: String,
    val title: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
)

/**
 * The single JSON codec for every cast payload. Shared so the phone and the TV
 * cannot drift apart on defaults, and tolerant because both ends ship as
 * separate APKs that users will not update in lockstep.
 */
val CastJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
