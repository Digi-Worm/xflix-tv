package dev.digi.digix.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import dev.digi.digix.data.CAST_DEFAULT_PORT
import dev.digi.digix.data.CAST_SERVICE_TYPE
import dev.digi.digix.data.CastJson
import dev.digi.digix.data.CastRequest
import dev.digi.digix.data.CastStatus
import dev.digi.digix.data.CastTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finds Digi X TVs on the LAN and talks to them.
 *
 * Discovery uses NSD, the only LAN service API reachable without extra
 * dependencies. It is also the flakiest part of the stack: cheap TV firmware
 * drops registrations, routers block multicast, and a listener that is stopped
 * and immediately restarted throws "listener already in use". Nothing here is
 * allowed to throw or to leave a dangling listener - the worst case is an empty
 * list plus a retry twenty seconds later, which is exactly what the manual IP
 * entry in the cast picker exists to cover.
 *
 * The object owns its scope, so callers never manage coroutines or cancellation
 * just to get a device list.
 */
class CastDiscovery(private val context: Context) {

    private val nsd: NsdManager? = runCatching {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }.getOrNull()

    private val _targets = MutableStateFlow<List<CastTarget>>(emptyList())
    private val targetsFlow: StateFlow<List<CastTarget>> = _targets.asStateFlow()

    /**
     * Live list of reachable TVs, de-duplicated by `host:port`. Always safe to
     * collect: it is never null and never throws, even when NSD is broken.
     */
    fun targets(): StateFlow<List<CastTarget>> = targetsFlow

    /** Main-thread confined: every NSD callback and state mutation runs here. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // --- NSD state ---------------------------------------------------------
    // Only touched from [scope]. `generation` invalidates callbacks belonging to
    // a session that has already been torn down, which is otherwise how a stale
    // resolve resurrects a TV the user can no longer reach.
    private var generation = 0
    private var wantDiscovery = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var rescanJob: Job? = null

    private val byName = LinkedHashMap<String, CastTarget>()
    private val resolvedAt = HashMap<String, Long>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** First-failure-only log gate for [pushStatus], which fires about once a second. */
    private val pushFailureLogged = ConcurrentHashMap<String, Boolean>()

    /** Begin (or resume) discovery. Safe to call repeatedly. */
    fun start() {
        scope.launch {
            wantDiscovery = true
            if (discoveryListener == null) beginDiscovery()
            ensureRescanLoop()
        }
    }

    /**
     * Stop discovery and release the listener. Targets are cleared so a later
     * scan never shows a TV that has since been switched off.
     */
    fun stop() {
        scope.launch {
            wantDiscovery = false
            rescanJob?.cancel()
            rescanJob = null
            stopDiscovery()
            byName.clear()
            resolvedAt.clear()
            publish()
        }
    }

    // --- Discovery ---------------------------------------------------------

    private fun beginDiscovery() {
        val manager = nsd ?: return
        if (discoveryListener != null) return

        val gen = ++generation
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery started: $serviceType")
            }

            // NSD delivers these on its own handler thread, not on main, so every
            // callback that touches state is hopped onto [scope]. Without this,
            // `byName`/`resolving` would be mutated and read from two threads.
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    if (gen != generation) return@launch
                    enqueueResolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    if (gen != generation) return@launch
                    val name = serviceInfo.serviceName ?: return@launch
                    if (name.isBlank()) return@launch
                    if (byName.remove(name) != null) {
                        resolvedAt.remove(name)
                        publish()
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // Several firmwares reject the first start after boot with
                // INTERNAL_ERROR. Detach the session so the rescan loop retries
                // instead of leaving a dead listener behind.
                Log.w(TAG, "start discovery failed ($errorCode)")
                val me = this
                scope.launch { detachIfCurrent(me) }
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // The session is unusable either way; forget it so a fresh one
                // can be created on the next pass.
                Log.w(TAG, "stop discovery failed ($errorCode)")
                val me = this
                scope.launch { detachIfCurrent(me) }
            }
        }

        // Registered before discoverServices: the failure callback can arrive
        // while that call is still unwinding, and assigning afterwards would
        // resurrect a listener the framework already disposed of.
        discoveryListener = listener
        try {
            manager.discoverServices(CAST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            // "listener already in use" is thrown here, not reported.
            Log.w(TAG, "discoverServices failed", t)
            if (discoveryListener === listener) discoveryListener = null
        }
    }

    /**
     * Detach [listener] only if it is still the live session. A late failure
     * callback from a session that has already been replaced must not clear the
     * replacement, which is the bug that used to leave casting permanently dead
     * until the app restarted.
     */
    private fun detachIfCurrent(listener: NsdManager.DiscoveryListener?) {
        if (listener != null && discoveryListener === listener) discoveryListener = null
    }

    private fun stopDiscovery() {
        // Bumping the generation first means any resolve already in flight when
        // the session is torn down lands as a no-op instead of resurrecting a
        // target from a session the user has left.
        generation++
        resolveQueue.clear()
        resolving = false
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsd?.stopServiceDiscovery(listener) }
            .onFailure { Log.w(TAG, "stopServiceDiscovery failed", it) }
    }

    /**
     * NSD silently stops delivering on some networks and there is no callback
     * when it does. Restarting the session periodically is the only reliable
     * cure; the gap between stop and start matters because an immediate restart
     * is rejected by the framework.
     */
    private fun ensureRescanLoop() {
        if (rescanJob?.isActive == true) return
        rescanJob = scope.launch {
            while (isActive) {
                delay(RESCAN_INTERVAL_MS)
                if (!wantDiscovery) break
                stopDiscovery()
                delay(RESTART_GAP_MS)
                if (!wantDiscovery) break
                if (discoveryListener == null) beginDiscovery()
                pruneStale()
            }
        }
    }

    // --- Resolution --------------------------------------------------------

    /**
     * The resolved host address.
     *
     * Read through `getHost()`, which has been public since API 16 and only
     * deprecated at 34 (where it still returns the first of `hostAddresses`), so
     * one code path covers minSdk 24 through 35. Kept in one place because the
     * deprecated accessor will otherwise appear at every call site.
     */
    @Suppress("DEPRECATION")
    private fun hostAddressOf(info: NsdServiceInfo): String? =
        runCatching { info.host?.hostAddress }.getOrNull()

    private fun enqueueResolve(info: NsdServiceInfo) {
        val name = info.serviceName
        if (name.isNullOrBlank()) return

        // Re-resolving a known name on every scan is wasteful, but never
        // resolving it again would pin a TV to a stale address after a DHCP
        // lease change, so the entry expires.
        val last = resolvedAt[name]
        if (last != null && SystemClock.elapsedRealtime() - last < RESOLVE_TTL_MS) return
        if (resolveQueue.any { it.serviceName == name }) return

        resolveQueue.addLast(info)
        drainResolveQueue()
    }

    /** Only one resolve may be in flight at a time or the platform rejects it. */
    @Suppress("DEPRECATION")
    private fun drainResolveQueue() {
        val manager = nsd ?: return
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        val gen = generation
        resolving = true

        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                // Same thread caveat as discovery: these arrive on NSD's handler
                // thread, so all state changes go through [scope].
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    // A failed resolve is normal (TV asleep, mDNS blocked) and
                    // must not wedge the queue.
                    Log.d(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    scope.launch {
                        // Ignore a late callback from a session that has already
                        // been replaced: clearing the in-flight flag here would
                        // let the new session run two resolves at once.
                        if (gen != generation) return@launch
                        resolving = false
                        drainResolveQueue()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    scope.launch {
                        if (gen != generation) return@launch
                        resolving = false
                        val name = serviceInfo.serviceName
                        val host = hostAddressOf(serviceInfo)
                        val port = serviceInfo.port
                        if (!name.isNullOrBlank() && !host.isNullOrBlank() && port in 1..65535) {
                            byName[name] = CastTarget(name = name, host = host, port = port)
                            resolvedAt[name] = SystemClock.elapsedRealtime()
                            publish()
                        }
                        drainResolveQueue()
                    }
                }
            })
        } catch (t: Throwable) {
            resolving = false
            Log.w(TAG, "resolveService failed", t)
            drainResolveQueue()
        }
    }

    private fun pruneStale() {
        val now = SystemClock.elapsedRealtime()
        var changed = false
        val iterator = byName.keys.iterator()
        while (iterator.hasNext()) {
            val name = iterator.next()
            val seenAt = resolvedAt[name] ?: 0L
            if (now - seenAt > STALE_MS) {
                iterator.remove()
                resolvedAt.remove(name)
                changed = true
            }
        }
        if (changed) publish()
    }

    /** Two service names can point at one endpoint; the picker shows it once. */
    private fun publish() {
        val seen = HashSet<String>(byName.size)
        val list = ArrayList<CastTarget>(byName.size)
        for (target in byName.values) {
            if (seen.add("${target.host}:${target.port}")) list.add(target)
        }
        _targets.value = list.sortedBy { it.name.lowercase() }
    }

    // --- Manual probe ------------------------------------------------------

    /**
     * Check a manually typed address. Accepts `192.168.1.109`, `192.168.1.109:47823`
     * and a plain hostname, and confirms the endpoint really is a Digi X TV by
     * asking `/health` for its name.
     *
     * Timeouts are deliberately short: this runs behind a button the user is
     * watching, so failing fast with "nothing answered" beats a spinner.
     */
    suspend fun probe(host: String, port: Int = CAST_DEFAULT_PORT): CastTarget? =
        withContext(Dispatchers.IO) {
            val (bareHost, resolvedPort) = splitHost(host, port)
            if (bareHost.isBlank()) return@withContext null

            runCatching {
                val request = Request.Builder()
                    .url("http://${bracket(bareHost)}:$resolvedPort/health")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    // Parsing the full health payload (a CastStatus carrying only
                    // a device name) keeps the probe honest: a random web server
                    // on the same port will not decode as one of our TVs.
                    val name = runCatching {
                        CastJson.decodeFromString(CastStatus.serializer(), body).deviceName
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@use null
                    CastTarget(name = name, host = bareHost, port = resolvedPort)
                }
            }.getOrNull()
        }

    // --- Commands ----------------------------------------------------------

    /** Hand a stream to [target]. Returns false on any failure; never throws. */
    suspend fun sendCast(target: CastTarget, request: CastRequest): Boolean {
        val json = runCatching { CastJson.encodeToString(CastRequest.serializer(), request) }
            .getOrNull() ?: return false
        val ok = postJson(endpoint(target, "cast"), json)
        if (!ok) Log.d(TAG, "cast rejected by ${target.host}:${target.port}")
        return ok
    }

    /** Ask [target] to stop playback. Returns false on any failure; never throws. */
    suspend fun sendStop(target: CastTarget): Boolean {
        val ok = postJson(endpoint(target, "stop"), "{}")
        if (!ok) Log.d(TAG, "stop rejected by ${target.host}:${target.port}")
        return ok
    }

    /** Read [target]'s playback state, or null if it cannot be reached. */
    suspend fun status(target: CastTarget): CastStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(endpoint(target, "status"))
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                CastJson.decodeFromString(CastStatus.serializer(), response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /**
     * Push this device's playback state to the TV so anything polling `/status`
     * sees the truth. Called once per progress tick, so it is quiet: a failing
     * target is logged the first time and then silently skipped, otherwise one
     * unreachable TV would fill the log every second.
     */
    suspend fun pushStatus(target: CastTarget, status: CastStatus): Boolean {
        val json = runCatching { CastJson.encodeToString(CastStatus.serializer(), status) }
            .getOrNull() ?: return false
        val ok = postJson(endpoint(target, "status"), json)
        val key = key(target)
        if (ok) {
            pushFailureLogged.remove(key)
        } else if (pushFailureLogged.putIfAbsent(key, true) == null) {
            Log.d(TAG, "status push failed for $key (further failures are silent)")
        }
        return ok
    }

    // --- HTTP plumbing -----------------------------------------------------

    private suspend fun postJson(url: String, json: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.toRequestBody(JSON_MEDIA)
            val request = Request.Builder().url(url).post(body).build()
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun endpoint(target: CastTarget, path: String): String =
        "http://${bracket(target.host)}:${target.port}/$path"

    private fun key(target: CastTarget): String = "${target.host}:${target.port}"

    /** IPv6 literals must be bracketed inside a URL; IPv4 and names must not. */
    private fun bracket(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun splitHost(raw: String, defaultPort: Int): Pair<String, Int> {
        val cleaned = raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
        if (cleaned.isBlank()) return "" to defaultPort

        val host = cleaned.substringBefore(':')
        val explicitPort = cleaned.substringAfter(':', "")
            .toIntOrNull()
            ?.takeIf { it in 1..65535 }
        return host to (explicitPort ?: defaultPort)
    }

    private companion object {
        const val TAG = "CastDiscovery"

        /** NSD goes quiet without telling anyone; a rescan is the only cure. */
        const val RESCAN_INTERVAL_MS = 20_000L

        /** An immediate stop-then-start is rejected by the framework. */
        const val RESTART_GAP_MS = 700L

        /** Re-resolve a known name after this, to follow DHCP changes. */
        const val RESOLVE_TTL_MS = 60_000L

        /** Drop a TV that no scan has confirmed for this long. */
        const val STALE_MS = 90_000L

        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * One pool for the whole cast path. Timeouts are short because every
         * call sits behind a control the user is waiting on.
         */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                // A hung TV must not be retried silently; the UI shows the error.
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}
