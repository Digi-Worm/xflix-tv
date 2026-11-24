package dev.digi.digix.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A minimal Google Cast v2 sender.
 *
 * Why hand-rolled instead of the Cast SDK: the SDK drags in Play Services and
 * needs a registered receiver application ID. This app is sideloaded onto TVs
 * and phones with no Play Services guarantee, and a Cast app ID would mean
 * running a Google developer console registration just to publish a build. The
 * wire protocol is small and stable, so it is implemented directly.
 *
 * Protocol summary (all over one TLS connection to `<device>:8009`):
 *
 *  - every frame is a 4-byte big-endian length followed by a `CastMessage`
 *    protobuf;
 *  - `sender-0` connects to `receiver-0` on the connection namespace;
 *  - a `LAUNCH` on the receiver namespace starts the Default Media Receiver
 *    (`CC1AD845`) and returns its `transportId`;
 *  - `LOAD` on the media namespace against that transportId starts playback.
 *
 * The receiver is a *dumb player*: it only accepts a URL it can fetch itself.
 * It supports MP4/WebM/HLS/DASH and **not** Matroska (`.mkv`), which is what
 * most torrent releases are. [isProbablyPlayable] encodes that limitation so
 * callers can fail with a real explanation rather than a silent no-op.
 *
 * Nothing here is authenticated: Cast devices accept any sender on the LAN by
 * design, so this cannot be worse than the receiver's own model.
 */
class ChromecastClient(
    private val host: String,
    private val port: Int = CAST_V2_PORT,
) {

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    /** transportId of the launched receiver app, filled in once LAUNCH replies. */
    @Volatile
    private var transportId: String? = null

    /** Best-effort media session id, needed for PAUSE/SEEK after LOAD. */
    @Volatile
    private var mediaSessionId: Int = -1

    @Volatile
    private var connected = false

    private val requestId = AtomicInteger(1)

    @Volatile
    private var lastError: String? = null

    /** Verdict from the receiver for the current LOAD, so play() can wait on it. */
    private enum class LoadOutcome { UNKNOWN, PLAYING, FAILED }

    @Volatile
    private var loadOutcome: LoadOutcome = LoadOutcome.UNKNOWN

    /** Device name reported by the receiver, useful for the UI. */
    @Volatile
    var deviceName: String? = null
        private set

    /**
     * Connect, then launch the receiver app and wait for its transport id.
     * Returns false when the device is unreachable or refuses the launch.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            raw.tcpNoDelay = true

            // Cast devices present a self-signed certificate that cannot be
            // validated against any CA. This is the documented behaviour of the
            // protocol; trust is established by the user picking the device.
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(TrustAll), null)
            val tls = (ctx.socketFactory.createSocket(
                raw, host, port, true
            ) as SSLSocket).apply {
                useClientMode = true
                startHandshake()
            }

            socket = tls
            output = tls.outputStream
            connected = true
            startReader()

            // Sender -> receiver, on the connection namespace.
            sendConnect(DEST_RECEIVER)
            send("urn:x-cast:com.google.cast.receiver", DEST_RECEIVER, launchPayload())

            awaitTransportId()
        }.getOrDefault(false)
    }

    /** Wait for the receiver to report a transport id, polling briefly. */
    private fun awaitTransportId(): Boolean {
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (transportId != null) return true
            if (!connected) return false
            if (lastError != null) {
                Log.w(TAG, "launch failed: $lastError")
                return false
            }
            Thread.sleep(100)
        }
        Log.w(TAG, "no transport id after ${LAUNCH_TIMEOUT_MS}ms")
        return false
    }

    /**
     * Start playback of [mediaUrl] on the receiver.
     *
     * [contentType] must be a MIME type the receiver can decode; use
     * [contentTypeFor] to derive it from a URL.
     *
     * Waits for the receiver's verdict rather than returning as soon as the
     * message is written. A well-formed LOAD is always *accepted*, then fails
     * asynchronously with LOAD_FAILED if the device cannot fetch or decode the
     * media - so returning early made an unplayable video look like a success.
     */
    suspend fun play(
        mediaUrl: String,
        contentType: String,
        title: String? = null,
        poster: String? = null,
        positionMs: Long = 0L,
    ): Boolean = withContext(Dispatchers.IO) {
        val transport = transportId ?: return@withContext false
        if (!connected) return@withContext false

        loadOutcome = LoadOutcome.UNKNOWN
        val sent = runCatching {
            // The media namespace needs its own virtual connection to the app.
            sendConnect(transport)

            val media = JSONObject().apply {
                put("contentId", mediaUrl)
                put("contentType", contentType)
                put(
                    "streamType",
                    // A torrent-backed file is written as it downloads, so the
                    // receiver must tolerate a length that grows. BUFFERED tells
                    // it to treat the content as a normal file it can range-request.
                    "BUFFERED"
                )
                if (!title.isNullOrBlank()) {
                    put(
                        "metadata",
                        JSONObject().apply {
                            put("metadataType", 0)
                            put("title", title)
                            if (!poster.isNullOrBlank()) put("images", org.json.JSONArray().put(poster))
                        }
                    )
                }
            }

            val payload = JSONObject().apply {
                put("type", "LOAD")
                put("requestId", requestId.getAndIncrement())
                put("media", media)
                put("autoplay", true)
                put("currentTime", positionMs / 1000.0)
            }
            send(NAMESPACE_MEDIA, transport, payload.toString())
        }.isSuccess

        if (!sent) return@withContext false

        // Wait for LOAD to resolve one way or the other.
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (loadOutcome) {
                LoadOutcome.PLAYING -> return@withContext true
                LoadOutcome.FAILED -> return@withContext false
                else -> Thread.sleep(120)
            }
            if (!connected) return@withContext false
        }
        // No verdict within the window: treat as playing. The receiver often
        // buffers for a while before reporting PLAYING, and blocking the UI
        // longer than this is worse than showing the cast as active.
        Log.d(TAG, "no explicit load verdict after ${LOAD_TIMEOUT_MS}ms; assuming ok")
        true
    }

    suspend fun pause(): Boolean = sendMediaCommand("PAUSE")
    suspend fun resume(): Boolean = sendMediaCommand("PLAY")

    private suspend fun sendMediaCommand(type: String): Boolean = withContext(Dispatchers.IO) {
        val transport = transportId ?: return@withContext false
        if (!connected || mediaSessionId < 0) return@withContext false
        runCatching {
            val payload = JSONObject().apply {
                put("type", type)
                put("requestId", requestId.getAndIncrement())
                put("mediaSessionId", mediaSessionId)
            }
            send(NAMESPACE_MEDIA, transport, payload.toString())
            true
        }.getOrDefault(false)
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val transport = transportId
        runCatching {
            if (transport != null && connected && mediaSessionId >= 0) {
                val payload = JSONObject().apply {
                    put("type", "STOP")
                    put("requestId", requestId.getAndIncrement())
                    put("mediaSessionId", mediaSessionId)
                }
                send(NAMESPACE_MEDIA, transport, payload.toString())
            }
            // Closing the socket also drops the receiver connection, which stops
            // the app on the device side.
            close()
            true
        }.getOrDefault(false)
    }

    fun close() {
        connected = false
        runCatching { output?.close() }
        runCatching { socket?.close() }
        runCatching { reader?.interrupt() }
        output = null
        socket = null
        reader = null
    }

    // --- Wire format -------------------------------------------------------

    private fun sendConnect(destination: String) {
        send(
            "urn:x-cast:com.google.cast.tp.connection",
            destination,
            JSONObject().apply { put("type", "CONNECT") }.toString()
        )
    }

    private fun launchPayload(): String = JSONObject().apply {
        put("type", "LAUNCH")
        put("requestId", requestId.getAndIncrement())
        put("appId", DEFAULT_RECEIVER_APP_ID)
    }.toString()

    private fun send(namespace: String, destination: String, payload: String) {
        val message = encodeCastMessage(
            sourceId = SOURCE_ID,
            destinationId = destination,
            namespace = namespace,
            payload = payload,
        )
        val out = output ?: return
        synchronized(this) {
            out.write(byteArrayOf(
                (message.size ushr 24).toByte(),
                (message.size ushr 16).toByte(),
                (message.size ushr 8).toByte(),
                message.size.toByte(),
            ))
            out.write(message)
            out.flush()
        }
    }

    private fun startReader() {
        reader = Thread({
            runCatching {
                val input = DataInputStream(socket?.inputStream ?: return@Thread)
                while (connected) {
                    val length = input.readInt()
                    if (length <= 0 || length > MAX_FRAME) break
                    val frame = ByteArray(length)
                    input.readFully(frame)
                    runCatching { handleFrame(frame) }
                }
            }.onFailure { Log.d(TAG, "reader ended: ${it.message}") }
            connected = false
        }, "castv2-reader").apply { isDaemon = true; start() }
    }

    private fun handleFrame(frame: ByteArray) {
        val message = decodeCastMessage(frame) ?: return
        val payload = message.payload
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (json.optString("type")) {
            "RECEIVER_STATUS" -> {
                val status = json.optJSONObject("status") ?: return
                val apps = status.optJSONArray("applications")
                if (apps != null) {
                    for (i in 0 until apps.length()) {
                        val app = apps.optJSONObject(i) ?: continue
                        if (app.optString("appId") == DEFAULT_RECEIVER_APP_ID) {
                            val tid = app.optString("transportId")
                            if (tid.isNotBlank()) {
                                transportId = tid
                                val displayName = app.optString("displayName")
                                if (displayName.isNotBlank()) deviceName = displayName
                                Log.i(TAG, "receiver launched, transport=$tid")
                            }
                        }
                    }
                }
            }
            "LAUNCH_ERROR" -> {
                lastError = json.optString("reason", "launch error")
            }
            "LOAD_FAILED" -> {
                Log.w(TAG, "LOAD failed: ${json.optString("reason")}")
                loadOutcome = LoadOutcome.FAILED
            }
            "MEDIA_STATUS" -> {
                val statuses = json.optJSONArray("status")
                if (statuses != null && statuses.length() > 0) {
                    val first = statuses.optJSONObject(0) ?: return
                    mediaSessionId = first.optInt("mediaSessionId", mediaSessionId)
                    val playerState = first.optString("playerState")
                    val idleReason = first.optString("idleReason")
                    when {
                        playerState == "PLAYING" || playerState == "BUFFERING" ->
                            loadOutcome = LoadOutcome.PLAYING
                        playerState == "IDLE" && idleReason.isNotEmpty() ->
                            loadOutcome = LoadOutcome.FAILED
                    }
                }
            }
            "INVALID_REQUEST" -> {
                Log.w(TAG, "invalid request: ${json.optString("reason")}")
            }
            "CLOSE" -> connected = false
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val TAG = "ChromecastClient"

        /** The Cast v2 control port. Fixed by the protocol, not configurable. */
        const val CAST_V2_PORT = 8009

        /** Google's pre-built Default Media Receiver. Needs no registration. */
        const val DEFAULT_RECEIVER_APP_ID = "CC1AD845"

        private const val NAMESPACE_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val SOURCE_ID = "sender-0"
        private const val DEST_RECEIVER = "receiver-0"

        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 0
        private const val LAUNCH_TIMEOUT_MS = 8_000

        /**
         * How long to wait for the receiver's verdict on a LOAD.
         *
         * The device always *accepts* a well-formed LOAD and then reports
         * LOAD_FAILED asynchronously if it cannot fetch or decode the media, so
         * returning early would report a broken cast as a success.
         */
        private const val LOAD_TIMEOUT_MS = 10_000

        private const val MAX_FRAME = 64 * 1024

        /**
         * MIME type for a media URL.
         *
         * The Default Media Receiver decodes MP4/WebM/HLS/DASH only. Matroska
         * (`.mkv`) is the most common torrent container and is *not* supported,
         * so callers use this to reject a cast up front with a real reason
         * instead of leaving the TV on a black screen.
         */
        fun contentTypeFor(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".m3u8") -> "application/x-mpegURL"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".mkv") -> null
                path.endsWith(".avi") || path.endsWith(".wmv") || path.endsWith(".flv") -> null
                // A URL with no extension is usually a signed CDN link; MP4 is
                // the safest guess and the receiver falls back gracefully.
                else -> "video/mp4"
            }
        }

        /**
         * A one-line explanation for media the receiver cannot play, or null
         * when it can. Used to fail loudly by container rather than silently.
         */
        fun unsupportedReason(url: String): String? =
            if (contentTypeFor(url) == null) {
                "Chromecast can't play this file type (it only supports MP4 and WebM). " +
                    "Pick an MP4 source, or cast to a TV running Digi X TV instead."
            } else null

        // --- protobuf -----------------------------------------------------

        /**
         * Hand-encoded `CastMessage`.
         *
         * The schema is tiny and fixed, so a generated protobuf class would add a
         * whole codegen step for six fields.
         */
        internal fun encodeCastMessage(
            sourceId: String,
            destinationId: String,
            namespace: String,
            payload: String,
        ): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            // field 1, varint, protocol_version = CASTV2_1_0 (0)
            out.write(0x08); out.write(0x00)
            writeString(out, 2, sourceId)          // source_id
            writeString(out, 3, destinationId)     // destination_id
            writeString(out, 4, namespace)         // namespace
            out.write(0x28); out.write(0x00)       // field 5, payload_type = STRING
            writeString(out, 6, payload)           // payload_utf8
            return out.toByteArray()
        }

        private fun writeString(out: java.io.ByteArrayOutputStream, field: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write((field shl 3) or 0x02)       // wire type 2 = length-delimited
            writeVarint(out, bytes.size)
            out.write(bytes)
        }

        private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Int) {
            var v = value
            while (v and 0x7F.inv() != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v)
        }

        internal class DecodedMessage(val namespace: String, val payload: String)

        /** Parse just the two fields the reader needs. Unknown fields are skipped. */
        internal fun decodeCastMessage(frame: ByteArray): DecodedMessage? {
            var i = 0
            var namespace = ""
            var payload = ""
            while (i < frame.size) {
                val tag = frame[i].toInt() and 0xFF
                i++
                val field = tag ushr 3
                val wire = tag and 0x07
                when (wire) {
                    0 -> { // varint
                        while (i < frame.size && (frame[i].toInt() and 0x80) != 0) i++
                        i++
                    }
                    2 -> { // length-delimited
                        var len = 0
                        var shift = 0
                        while (i < frame.size) {
                            val b = frame[i].toInt() and 0xFF
                            i++
                            len = len or ((b and 0x7F) shl shift)
                            if (b and 0x80 == 0) break
                            shift += 7
                        }
                        if (i + len > frame.size) return null
                        when (field) {
                            4 -> namespace = String(frame, i, len, Charsets.UTF_8)
                            6 -> payload = String(frame, i, len, Charsets.UTF_8)
                        }
                        i += len
                    }
                    1 -> i += 8   // 64-bit
                    5 -> i += 4   // 32-bit
                    else -> return null
                }
            }
            return DecodedMessage(namespace, payload)
        }
    }
}
