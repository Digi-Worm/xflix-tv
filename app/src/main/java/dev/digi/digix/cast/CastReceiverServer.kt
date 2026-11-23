package dev.digi.digix.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import android.util.Log
import dev.digi.digix.data.CAST_DEFAULT_PORT
import dev.digi.digix.data.CAST_SERVICE_TYPE
import dev.digi.digix.data.CastJson
import dev.digi.digix.data.CastRequest
import dev.digi.digix.data.CastStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale

/**
 * TV-side cast receiver: advertises this device on the LAN and accepts the
 * phone's stream descriptors.
 *
 * The phone only sends *what* to play (an http(s) URL or an infoHash plus
 * trackers); resolving and buffering happens here, on the TV, through the
 * normal torrent engine. That is the whole design: no media is proxied, so the
 * cast keeps playing after the phone disconnects.
 *
 * The HTTP server is deliberately hand-rolled on a raw [ServerSocket] instead
 * of pulling in a framework. A phone sends one small request at a time, and on
 * a cheap TV box every extra library is another thing that can fail to start.
 * The accept loop is a single coroutine; requests are handled inline because
 * concurrency would buy nothing and would make the socket bookkeeping harder
 * to reason about.
 *
 * Robustness rules baked in:
 *  - sockets have a read timeout, so a half-open connection cannot wedge the
 *    accept loop;
 *  - the request head is capped at 8 KB and the body at 1 MB, so a hostile or
 *    garbled client cannot exhaust memory;
 *  - every request is handled inside try/catch, so a bad request is answered
 *    (or dropped) instead of killing the loop;
 *  - NSD advertisement failure is non-fatal - the user can still type the IP
 *    shown on screen.
 *
 * SECURITY: there is **no authentication**. Anything on the same network can
 * POST /cast and POST /stop. That is accepted for a sideloaded app on a
 * trusted home network; do not expose this port to the internet or run it on
 * shared/public Wi-Fi. Adding auth would mean provisioning a secret onto both
 * devices, which is more moving parts than this feature is worth.
 *
 * All public members are safe to call from the main thread.
 */
class CastReceiverServer(
    private val context: Context,
    private val deviceName: String,
    private val onCast: (CastRequest) -> Unit,
    private val onStopCast: () -> Unit,
) {

    /** The port actually bound; the preferred port may already be in use. */
    @Volatile
    private var boundPort = 0

    /** Cached best-effort LAN IPv4 for the "type this on your phone" hint. */
    @Volatile
    private var lanIp: String? = null

    /** True once a lookup has run, including one that found nothing. */
    @Volatile
    private var lanIpDone = false

    private val ipLock = Any()

    /**
     * Last state pushed by the player. Volatile because the player publishes
     * from its own coroutine while the HTTP loop reads from a different one.
     */
    @Volatile
    private var lastStatus: CastStatus = CastStatus(deviceName = deviceName)

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    val port: Int get() = boundPort

    /**
     * Best-effort LAN IPv4 for the "type this on your phone" hint.
     *
     * Enumeration walks the kernel's address table, so it is cached after the
     * first lookup and never performed on the main thread: from the UI thread
     * this only reads the cache. Callers that need the value should read it
     * from an IO dispatcher, which is what the settings screen does.
     */
    val localIp: String?
        get() {
            lanIp?.let { return it }
            if (lanIpDone) return null
            // Main thread: kick off a background lookup and answer with what we
            // have. Blocking here would jank the settings screen on first paint.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                scope?.launch { resolveLanIp() }
                return null
            }
            return resolveLanIp()
        }

    /**
     * Bind the socket and start accepting. Idempotent: a second call while
     * already running does nothing.
     */
    @Synchronized
    fun start() {
        if (serverSocket != null) return

        val socket = bindServerSocket()
        if (socket == null) {
            Log.e(TAG, "no free port in $CAST_DEFAULT_PORT..+$PORT_ATTEMPTS, cast receiver disabled")
            return
        }

        serverSocket = socket
        boundPort = socket.localPort

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        // Interface enumeration touches the kernel and is not free; resolving it
        // here keeps it off the main thread (start() is typically called from UI).
        newScope.launch {
            resolveLanIp()
            Log.i(TAG, "cast receiver listening on ${lanIp ?: "unknown"}:$boundPort")
        }
        newScope.launch { acceptLoop(socket, newScope) }

        registerNsd(socket.localPort)
    }

    /**
     * Tear everything down. Safe to call twice, safe before [start], and safe
     * if the socket or the NSD registration was never fully established.
     */
    @Synchronized
    fun stop() {
        unregisterNsd()

        // Close before cancelling: accept() is blocked on the socket and only
        // returns once it goes away, so this unblocks the coroutine promptly.
        runCatching { serverSocket?.close() }
        serverSocket = null

        runCatching { scope?.cancel() }
        scope = null
    }

    /** Publish playback state for `GET /status`. Safe from any thread. */
    fun publishStatus(status: CastStatus) {
        lastStatus = status
    }

    // ---------------------------------------------------------------- HTTP ---

    private suspend fun acceptLoop(socket: ServerSocket, dispatch: CoroutineScope) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                // A closed listener is the normal shutdown path, not an error.
                if (socket.isClosed) break
                Log.w(TAG, "accept failed", t)
                // Tiny pause so a persistently broken listener cannot spin a core.
                delay(100)
                continue
            }

            try {
                handle(client, dispatch)
            } catch (e: SocketTimeoutException) {
                // A port scanner or a client that connected and went quiet.
                // Expected noise on a LAN; not worth a warning.
                Log.d(TAG, "client timed out")
            } catch (t: Throwable) {
                Log.w(TAG, "request handling failed", t)
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket, dispatch: CoroutineScope) {
        // Without this, a client that opens a connection and says nothing holds
        // the single accept loop hostage forever.
        client.soTimeout = SOCKET_TIMEOUT_MS

        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input)
        if (head == null) {
            respond(client, 400, BAD_REQUEST_BODY)
            return
        }

        val request = parseHead(head)
        if (request == null) {
            respond(client, 400, BAD_REQUEST_BODY)
            return
        }

        val declaredHeader = request.headers["content-length"]
        val declaredLength = declaredHeader?.toIntOrNull()
        if (declaredHeader != null && declaredLength == null) {
            // Present but unparseable (overflow, junk): refuse rather than guess.
            respond(client, 400, BAD_REQUEST_BODY)
            return
        }
        val length = declaredLength ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) {
            respond(client, 413, TOO_LARGE_BODY)
            return
        }

        val body = if (length > 0) readBody(input, length) else ""
        if (body == null) {
            respond(client, 400, BAD_REQUEST_BODY)
            return
        }

        // Answer CORS preflight for every path; browsers probe before POSTing.
        if (request.method == "OPTIONS") {
            respond(client, 204, null)
            return
        }

        when (request.path) {
            "/health" ->
                if (request.method == "GET") {
                    respond(client, 200, encodeStatus(CastStatus(deviceName = deviceName)))
                } else {
                    methodNotAllowed(client, "GET, OPTIONS")
                }

            "/status" ->
                when (request.method) {
                    "GET" -> respond(client, 200, encodeStatus(lastStatus))
                    // The phone mirrors its own playback state here so anything
                    // polling /status on this TV sees the truth. Cheap and
                    // idempotent, so it is handled inline.
                    "POST" -> {
                        val pushed = try {
                            CastJson.decodeFromString(CastStatus.serializer(), body)
                        } catch (t: Throwable) {
                            null
                        }
                        if (pushed == null) {
                            respond(client, 400, INVALID_BODY)
                        } else {
                            lastStatus = pushed
                            respond(client, 200, OK_BODY)
                        }
                    }

                    else -> methodNotAllowed(client, "GET, POST, OPTIONS")
                }

            "/cast" ->
                if (request.method == "POST") {
                    handleCast(client, body, dispatch)
                } else {
                    methodNotAllowed(client, "POST, OPTIONS")
                }

            "/stop" ->
                if (request.method == "POST") {
                    handleStop(client, dispatch)
                } else {
                    methodNotAllowed(client, "POST, OPTIONS")
                }

            else -> respond(client, 404, NOT_FOUND_BODY)
        }
    }

    private fun handleCast(client: Socket, body: String, dispatch: CoroutineScope) {
        val request = try {
            CastJson.decodeFromString(CastRequest.serializer(), body)
        } catch (t: Throwable) {
            Log.w(TAG, "malformed /cast body", t)
            null
        }

        if (request == null) {
            respond(client, 400, INVALID_BODY)
            return
        }

        // Acknowledge first. The phone's UI flips to "casting" on this response,
        // and starting a torrent can take a second or two on this hardware.
        respond(client, 200, OK_BODY)

        // Handlers run on the IO scope, never inline: the player will block
        // while it resolves the stream, and that must not stall the socket loop
        // (the phone polls /status next).
        dispatch.launch {
            try {
                onCast(request)
            } catch (t: Throwable) {
                Log.w(TAG, "cast handler failed", t)
            }
        }
    }

    private fun handleStop(client: Socket, dispatch: CoroutineScope) {
        respond(client, 200, OK_BODY)
        dispatch.launch {
            try {
                onStopCast()
            } catch (t: Throwable) {
                Log.w(TAG, "stop handler failed", t)
            }
        }
    }

    private fun encodeStatus(status: CastStatus): String = try {
        CastJson.encodeToString(CastStatus.serializer(), status)
    } catch (t: Throwable) {
        // Should be unreachable, but a serialization hiccup must not 500 the
        // /health probe that the phone uses to recognise this device.
        Log.w(TAG, "status encode failed", t)
        "{\"deviceName\":${JsonPrimitive(deviceName)}}"
    }

    /**
     * Minimal HTTP/1.1 head reader.
     *
     * Reads byte-by-byte on purpose: an InputStreamReader would buffer ahead
     * into the body and lose the first bytes of a POST, and these heads are a
     * few hundred bytes at most. Returns null on EOF or when the cap is hit.
     */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(512)
        var window = 0 // last four bytes, used to spot the blank line
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            window = (window shl 8) or (b and 0xFF)
            val size = buffer.size()
            if (size > MAX_HEAD_BYTES) return null
            if (size >= 4 && window == CRLFCRLF) {
                val bytes = buffer.toByteArray()
                return String(bytes, 0, bytes.size - 4, Charsets.UTF_8)
            }
            // Tolerate bare-LF clients; some hand-written probes do this.
            if (size >= 2 && (window and 0xFFFF) == 0x0A0A) {
                val bytes = buffer.toByteArray()
                return String(bytes, 0, bytes.size - 2, Charsets.UTF_8)
            }
        }
    }

    private fun readBody(input: InputStream, length: Int): String? {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) return null
            read += n
        }
        return String(data, Charsets.UTF_8)
    }

    private fun parseHead(head: String): HttpRequest? {
        val lines = LINE_SPLIT.split(head).filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull()?.trim() ?: return null
        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        val method = parts[0].uppercase(Locale.US)
        // Drop any query string; only the path routes here.
        val path = parts[1].substringBefore('?').trimEnd('/').ifEmpty { "/" }
        // Absolute-form targets and garbage would not route anywhere sensible.
        if (!path.startsWith("/")) return null

        val headers = HashMap<String, String>(8)
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            headers[key] = value
        }
        return HttpRequest(method, path, headers)
    }

    private fun methodNotAllowed(client: Socket, allow: String) {
        respond(client, 405, METHOD_NOT_ALLOWED_BODY, mapOf("Allow" to allow))
    }

    private fun respond(
        client: Socket,
        code: Int,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val payload = body?.toByteArray(Charsets.UTF_8)
        val head = buildString(256) {
            append("HTTP/1.1 ").append(code).append(' ').append(reasonFor(code)).append("\r\n")
            if (payload != null) {
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
            } else if (code != 204) {
                append("Content-Length: 0\r\n")
            }
            // Open CORS so the endpoints can be exercised from a browser tab
            // during debugging; the phone app itself does not need it.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            for ((key, value) in extraHeaders) append(key).append(": ").append(value).append("\r\n")
            append("\r\n")
        }

        val out = BufferedOutputStream(client.getOutputStream())
        // A client that hung up mid-request is routine (the phone aborts a poll
        // when the user backs out). Throwing here would bubble up as noise.
        runCatching {
            out.write(head.toByteArray(Charsets.US_ASCII))
            if (payload != null) out.write(payload)
            out.flush()
        }
    }

    private fun reasonFor(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    // ----------------------------------------------------------------- NSD ---

    private fun registerNsd(port: Int) {
        val manager = try {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        } catch (t: Throwable) {
            null
        }
        if (manager == null) {
            // Some cheap TV images ship without a working NSD stack. Not fatal:
            // the IP on screen is the fallback path.
            Log.w(TAG, "NSD unavailable; advertising skipped")
            return
        }

        val info = NsdServiceInfo().apply {
            serviceName = sanitizeServiceName(deviceName)
            serviceType = CAST_SERVICE_TYPE
            setPort(port)
            // TXT values are capped at 255 bytes and a user-supplied TV name is
            // unbounded; a throw here would otherwise abort the whole setup.
            runCatching { setAttribute("name", deviceName.take(200)) }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertised as '${info.serviceName}' ($CAST_SERVICE_TYPE)")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // Keep serving HTTP: discovery is a convenience, not a requirement.
                Log.w(TAG, "NSD registration failed (code $errorCode); manual IP entry still works")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD advertisement withdrawn")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed (code $errorCode)")
            }
        }

        nsdManager = manager
        registrationListener = listener
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            // IllegalArgumentException if a registration is already active, and
            // security exceptions on locked-down builds.
            Log.w(TAG, "NSD registration threw; advertising skipped", t)
            registrationListener = null
        }
    }

    private fun unregisterNsd() {
        val manager = nsdManager
        val listener = registrationListener
        nsdManager = null
        registrationListener = null
        if (manager == null || listener == null) return
        try {
            manager.unregisterService(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "NSD unregister threw", t)
        }
    }

    /** mDNS labels are limited to 63 bytes; keep it short and printable. */
    private fun sanitizeServiceName(raw: String): String {
        val cleaned = raw.trim().ifEmpty { "Digi X TV" }
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
        return cleaned.take(40).ifEmpty { "Digi X TV" }
    }

    // ----------------------------------------------------------------- net ---

    private fun bindServerSocket(): ServerSocket? {
        for (offset in 0..PORT_ATTEMPTS) {
            val candidate = CAST_DEFAULT_PORT + offset
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate), BACKLOG)
                return socket
            } catch (t: Throwable) {
                Log.d(TAG, "port $candidate unavailable: ${t.message}")
                runCatching { socket.close() }
            }
        }
        return null
    }

    /**
     * Runs [findLanIp] at most once and caches the result, including a null
     * result, so a device with no LAN address cannot trigger repeated scans.
     *
     * Locked on its own object rather than `this`: the lookup can hit the
     * kernel for a while, and holding the instance monitor would stall a
     * concurrent start()/stop().
     */
    private fun resolveLanIp(): String? = synchronized(ipLock) {
        if (!lanIpDone) {
            lanIp = findLanIp()
            lanIpDone = true
        }
        lanIp
    }

    /**
     * Best-effort LAN IPv4. Wi-Fi is preferred because a TV box may also have a
     * wired and a virtual (`p2p`, `dummy0`) interface, and showing the user the
     * wrong one makes manual pairing impossible.
     */
    private fun findLanIp(): String? = try {
        val candidates = mutableListOf<Pair<String, String>>()
        for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp || nif.isLoopback) continue
            for (address in Collections.list(nif.inetAddresses)) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val host = address.hostAddress ?: continue
                candidates += nif.name to host
            }
        }
        (candidates.firstOrNull { it.first.startsWith("wlan") }
            ?: candidates.firstOrNull { it.first.startsWith("eth") }
            ?: candidates.firstOrNull())?.second
    } catch (t: Throwable) {
        Log.w(TAG, "LAN IP lookup failed", t)
        null
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private companion object {
        const val TAG = "CastReceiver"
        const val PORT_ATTEMPTS = 10
        const val BACKLOG = 8
        const val SOCKET_TIMEOUT_MS = 10_000
        const val MAX_HEAD_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 1024 * 1024

        /** Last four head bytes spelling CRLFCRLF, as a little rolling int. */
        const val CRLFCRLF = 0x0D0A0D0A

        /** Compiled once: parsing heads per request must not allocate a Regex. */
        private val LINE_SPLIT = Regex("\r?\n")

        const val OK_BODY = "{\"ok\":true}"
        const val BAD_REQUEST_BODY = "{\"ok\":false,\"error\":\"bad request\"}"
        const val INVALID_BODY = "{\"ok\":false,\"error\":\"invalid cast request\"}"
        const val TOO_LARGE_BODY = "{\"ok\":false,\"error\":\"payload too large\"}"
        const val NOT_FOUND_BODY = "{\"ok\":false,\"error\":\"not found\"}"
        const val METHOD_NOT_ALLOWED_BODY = "{\"ok\":false,\"error\":\"method not allowed\"}"
    }
}
