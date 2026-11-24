package dev.digi.digix.cast

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.digi.digix.data.CastKind
import dev.digi.digix.data.CastRequest
import dev.digi.digix.data.CastTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone-side casting state: what the user is watching, which TV they picked,
 * and the small amount of UI state (sheet open, in-flight request, error).
 *
 * Discovery is deliberately kept in [CastDiscovery] rather than merged in here:
 * NSD registration and resolution are stateful and easy to leak, and keeping
 * them in one owned object makes "stop everything" a single call.
 *
 * All network calls are funnelled through [scope] so a screen that is closed
 * mid-request cannot leave a coroutine pointed at a dead context.
 */
@Stable
class CastController(private val context: Context) {

    val discovery = CastDiscovery(context)

    /** The request the user is trying to send; held while they choose a TV. */
    var pending by mutableStateOf<CastRequest?>(null)
        private set

    var sheetVisible by mutableStateOf(false)
        private set

    /** Non-null once a TV has accepted a cast. */
    var castingTo by mutableStateOf<CastTarget?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** The TV's own advertised name, also shown in the sheet header. */
    val deviceName: String = friendlyDeviceName()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discoveryJob: Job? = null

    /**
     * Live Chromecast session, if one is running.
     *
     * Kept separately from [castingTo] because a Chromecast needs an open TLS
     * socket on the receiver to issue pause/stop; closing it is how playback is
     * torn down.
     */
    private var chromecast: ChromecastClient? = null

    /**
     * Prepare to cast [request]. Opens the picker rather than casting straight
     * away: the phone has no way to know which TV the viewer means, and
     * silently picking the first responder sends a stream to the wrong room.
     */
    fun begin(request: CastRequest) {
        pending = request
        error = null
        sheetVisible = true
        startScanning()
    }

    /** Open the picker with no pending media (browse / reconnect). */
    fun openSheet() {
        error = null
        sheetVisible = true
        startScanning()
    }

    fun dismiss() {
        sheetVisible = false
        if (castingTo == null) {
            // Leaving the picker with nothing selected: stop burning battery on
            // multicast. A live cast keeps discovery running so the sheet can
            // still show the current target.
            stopScanning()
            if (busy.not()) pending = null
        }
    }

    private fun startScanning() {
        discoveryJob?.cancel()
        discovery.start()
    }

    private fun stopScanning() {
        discoveryJob?.cancel()
        discoveryJob = null
        discovery.stop()
    }

    /** Cast to a discovered TV. */
    fun castTo(target: CastTarget) {
        val request = pending ?: return
        if (target.kind == CastKind.CHROMECAST) {
            castToChromecast(target, request)
            return
        }
        busy = true
        error = null
        scope.launch {
            val ok = discovery.sendCast(target, request)
            busy = false
            if (ok) {
                castingTo = target
                pending = null
                sheetVisible = false
            } else {
                error = "Couldn't reach ${target.name}. Check that the TV is on the same Wi-Fi."
            }
        }
    }

    /**
     * Cast to a real Google Cast device.
     *
     * Unlike a Digi X TV, a Chromecast is a dumb player: it fetches the media URL
     * itself, so the phone has to serve it and stay on the network. It also only
     * decodes MP4/WebM/HLS/DASH - no Matroska, which is most torrent releases.
     * Both limits are reported to the user rather than failing silently.
     */
    private fun castToChromecast(target: CastTarget, request: CastRequest) {
        val url = request.url
        if (url.isNullOrBlank()) {
            error = "Chromecast can only play a direct video link. This source is a " +
                "torrent, which a Chromecast can't fetch. Pick a direct link, or cast " +
                "to a TV running Digi X TV."
            return
        }
        ChromecastClient.unsupportedReason(url)?.let { reason ->
            error = reason
            return
        }
        val contentType = ChromecastClient.contentTypeFor(url) ?: "video/mp4"

        busy = true
        error = null
        scope.launch {
            val client = ChromecastClient(target.host, target.port)
            val connected = client.connect()
            if (!connected) {
                client.close()
                busy = false
                error = "Couldn't connect to ${target.name}. Check the TV is on and on " +
                    "the same Wi-Fi."
                return@launch
            }
            val ok = client.play(
                mediaUrl = url,
                contentType = contentType,
                title = request.title,
                poster = request.poster,
                positionMs = request.positionMs,
            )
            busy = false
            if (ok) {
                chromecast = client
                castingTo = target
                pending = null
                sheetVisible = false
            } else {
                client.close()
                error = "${target.name} refused to play this video. Chromecast only " +
                    "accepts MP4 and WebM."
            }
        }
    }

    /**
     * Manual fallback for the common case where the TV is on a different
     * subnet or the router blocks multicast: type the address and connect
     * directly.
     */
    fun castToAddress(rawHost: String) {
        val request = pending ?: return
        val host = normaliseHost(rawHost)
        if (host == null) {
            error = "Enter the TV's address, for example 192.168.1.109"
            return
        }
        busy = true
        error = null
        scope.launch {
            val target = discovery.probe(host)
            if (target == null) {
                busy = false
                error = "No TV answered at $host. Check the address and that it is on " +
                    "the same Wi-Fi."
                return@launch
            }
            if (target.kind == CastKind.CHROMECAST) {
                // Hand off rather than duplicating the Cast v2 path; it owns
                // `busy` and `error` from here.
                busy = false
                castToChromecast(target, request)
                return@launch
            }
            val ok = discovery.sendCast(target, request)
            busy = false
            if (ok) {
                castingTo = target
                pending = null
                sheetVisible = false
            } else {
                error = "Found ${target.name} but it refused the cast."
            }
        }
    }

    /** Stop playback on the TV. The phone's own player is untouched. */
    fun stopCasting() {
        val target = castingTo
        castingTo = null
        chromecast?.let { client ->
            chromecast = null
            scope.launch { client.stop() }
            return
        }
        if (target == null) return
        scope.launch { withContext(Dispatchers.IO) { discovery.sendStop(target) } }
    }

    /**
     * Called on every player progress tick while a cast is live, so the TV's
     * status endpoint stays truthful for anything polling it.
     */
    fun publishStatus(title: String?, positionMs: Long, durationMs: Long, playing: Boolean) {
        val target = castingTo ?: return
        // A Chromecast is driven over the Cast protocol, not this app's /status
        // endpoint, and pushing there would just log a failure every second.
        if (target.kind == CastKind.CHROMECAST) return
        val status = dev.digi.digix.data.CastStatus(
            deviceName = deviceName,
            title = title,
            positionMs = positionMs,
            durationMs = durationMs,
            playing = playing,
        )
        scope.launch { withContext(Dispatchers.IO) { discovery.pushStatus(target, status) } }
    }

    fun clearError() {
        error = null
    }

    private companion object {
        /**
         * Accepts "192.168.1.109", "192.168.1.109:47823" and "host.local".
         * Returns the bare host, since [CastDiscovery.probe] owns the port.
         */
        fun normaliseHost(raw: String): String? {
            val trimmed = raw.trim().removePrefix("http://").removePrefix("https://")
            if (trimmed.isBlank()) return null
            val host = trimmed.substringBefore('/').substringBefore(':')
            val valid = host.isNotBlank() &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
            return if (valid) host else null
        }

        fun friendlyDeviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            val brand = Build.MANUFACTURER?.trim().orEmpty()
            val name = when {
                model.isBlank() -> "Digi X TV"
                model.startsWith(brand, ignoreCase = true) -> model
                brand.isBlank() -> model
                else -> "$brand $model"
            }
            // NSD service names have a 63-byte limit and choke on some
            // punctuation, so keep it short and plain.
            return name.replace(Regex("[^A-Za-z0-9 ._-]"), " ").trim().take(40)
                .ifBlank { "Digi X TV" }
        }
    }
}
