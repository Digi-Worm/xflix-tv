package dev.digi.digix.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import dev.digi.digix.data.CAST_DEFAULT_PORT
import dev.digi.digix.data.CAST_SERVICE_TYPE
import dev.digi.digix.data.CastJson
import dev.digi.digix.data.CastKind
import dev.digi.digix.data.CastRequest
import dev.digi.digix.data.CastStatus
import dev.digi.digix.data.CastTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finds Digi X TVs on the LAN and talks to them.
 *
 * Two discovery paths run at the same time and merge into one list:
 *
 *  1. **Active subnet scan.** The /24 around this device's own IPv4 is probed
 *     on [CAST_DEFAULT_PORT]..[PORT_ATTEMPTS] with a concurrent `GET /health`.
 *     This is the reliable path: it needs no multicast, works on routers that
 *     drop mDNS, and returns in a couple of seconds instead of tens.
 *  2. **NSD (mDNS).** Kept because it also finds TVs on a different subnet and
 *     because it is the only way to learn a TV's *name* without already knowing
 *     its address. It is the flakier path: cheap TV firmware drops
 *     registrations and some routers block multicast outright.
 *
 * A [WifiManager.MulticastLock] is held while discovery is running. Without it
 * the Wi-Fi stack filters mDNS replies in low-power mode on most phones, which
 * was the single biggest cause of "the TV never shows up".
 *
 * Nothing here is allowed to throw or to leave a dangling listener - the worst
 * case is a short list plus the manual IP entry in the cast picker.
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
    private var chromecastListener: NsdManager.DiscoveryListener? = null
    private var rescanJob: Job? = null
    private var scanJob: Job? = null

    /** Held while discovery runs so the Wi-Fi stack stops filtering mDNS. */
    private var multicastLock: WifiManager.MulticastLock? = null

    private val byName = LinkedHashMap<String, CastTarget>()
    private val resolvedAt = HashMap<String, Long>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()

    /** Kind for a queued resolve, since NsdServiceInfo does not carry it. */
    private val pendingKinds = HashMap<String, CastKind>()

    private var resolving = false

    /**
     * Targets confirmed by the active scan, keyed by `host:port`.
     *
     * Kept apart from [byName] because NSD knows the TV's *name* while a scan
     * knows only the address. A scan hit is upgraded to a named entry as soon as
     * NSD resolves the same endpoint, and both sources feed [publish].
     */
    private val byAddress = HashMap<String, CastTarget>()
    private val addressSeenAt = HashMap<String, Long>()

    /** First-failure-only log gate for [pushStatus], which fires about once a second. */
    private val pushFailureLogged = ConcurrentHashMap<String, Boolean>()

    /** Begin (or resume) discovery. Safe to call repeatedly. */
    fun start() {
        scope.launch {
            wantDiscovery = true
            acquireMulticastLock()
            if (discoveryListener == null) beginDiscovery()
            ensureRescanLoop()
            // The active scan is what actually produces results in a second or
            // two; kick it immediately rather than waiting for the first tick.
            if (scanJob?.isActive != true) ensureScanLoop(immediate = true)
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
            scanJob?.cancel()
            scanJob = null
            stopDiscovery()
            releaseMulticastLock()
            byName.clear()
            resolvedAt.clear()
            byAddress.clear()
            addressSeenAt.clear()
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

        beginChromecastDiscovery(gen)
    }

    /**
     * Second NSD session for real Google Cast devices.
     *
     * A separate listener is required because one `DiscoveryListener` can only
     * serve one service type. Failures here are logged and ignored: plenty of
     * networks have no Chromecast, and that must never affect finding a TV that
     * runs this app.
     */
    private fun beginChromecastDiscovery(gen: Int) {
        val manager = nsd ?: return
        if (chromecastListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    if (gen != generation) return@launch
                    enqueueResolve(serviceInfo, CastKind.CHROMECAST)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                scope.launch {
                    if (gen != generation) return@launch
                    val name = serviceInfo.serviceName ?: return@launch
                    if (byName.remove(name) != null) {
                        resolvedAt.remove(name)
                        publish()
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.d(TAG, "chromecast discovery failed ($errorCode)")
                val me = this
                scope.launch { if (chromecastListener === me) chromecastListener = null }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                val me = this
                scope.launch { if (chromecastListener === me) chromecastListener = null }
            }
        }

        chromecastListener = listener
        try {
            manager.discoverServices(
                CHROMECAST_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (t: Throwable) {
            Log.d(TAG, "chromecast discoverServices failed: ${t.message}")
            if (chromecastListener === listener) chromecastListener = null
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
        chromecastListener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        chromecastListener = null
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

    // --- Multicast lock ----------------------------------------------------

    /**
     * Wi-Fi power saving silently drops multicast/broadcast frames, which is
     * exactly what mDNS replies are. Holding this lock is the difference
     * between "no TVs found" and "found in two seconds" on most phones.
     *
     * Deliberately non-fatal: a device without Wi-Fi (Ethernet-only TV, or the
     * user casting over a hotspot) simply has no lock to take.
     */
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
            Log.d(TAG, "multicast lock acquired")
        }.onFailure { Log.w(TAG, "multicast lock unavailable", it) }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        runCatching {
            if (lock.isHeld) lock.release()
        }.onFailure { Log.w(TAG, "multicast lock release failed", it) }
    }

    // --- Active subnet scan ------------------------------------------------

    /**
     * Probe the local /24 on the cast port range. This is the fast, boring path
     * that does not depend on multicast at all.
     *
     * Every address is tried concurrently behind a [SCAN_CONCURRENCY] semaphore:
     * 254 sockets at once exhausts file descriptors on a phone, but a few dozen
     * in parallel finish a /24 in a handful of batches.
     */
    private fun ensureScanLoop(immediate: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            if (!immediate) delay(SCAN_INTERVAL_MS)
            while (isActive && wantDiscovery) {
                runCatching { scanSubnet() }
                    .onFailure { Log.w(TAG, "subnet scan failed", it) }
                pruneStale()
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private suspend fun scanSubnet() {
        val localIp = findLocalIpv4() ?: run {
            Log.d(TAG, "no LAN IPv4; skipping subnet scan")
            return
        }
        val prefix = localIp.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return

        val started = SystemClock.elapsedRealtime()

        // Phase 1: the default port only. The receiver only falls back to
        // another port when 47823 is taken, which is rare, and probing four
        // ports per host quadruples the socket count for no usual gain.
        val hosts = (1..254).map { "$prefix.$it" }.filter { it != localIp }
        var found = sweep(hosts, listOf(CAST_DEFAULT_PORT))

        // Phase 2: nothing at all answered on the default port, so pay for the
        // full receiver range before concluding there are no TVs. Only runs in
        // that case, which keeps the common path fast.
        if (found == 0) {
            val extended = (1..SCAN_PORT_ATTEMPTS).map { CAST_DEFAULT_PORT + it }
            found = sweep(hosts, extended)
            Log.d(TAG, "extended scan on ports $extended found $found")
        }

        // Phase 3: Chromecast, by address rather than by mDNS.
        //
        // mDNS is the documented way to find Cast devices and it is what the
        // picker used to rely on exclusively - but it is also the least reliable
        // part of Android networking: multicast is filtered by many access
        // points, disabled on some Wi-Fi drivers, and NSD is missing or broken
        // on a fair number of phones. That is exactly the "no TV to cast to"
        // report. Every Cast device answers a plain HTTP GET on 8008, so the
        // subnet is probed directly and mDNS is treated as a bonus.
        val chromecasts = scanChromecasts(hosts)
        Log.d(TAG, "chromecast scan found $chromecasts")

        Log.d(
            TAG,
            "subnet scan $prefix.0/24: digix=$found chromecast=$chromecasts in " +
                "${SystemClock.elapsedRealtime() - started} ms"
        )
        pruneStale()
    }

    /**
     * Find Cast devices by probing `/setup/eureka_info` on port 8008.
     *
     * This is the same unauthenticated endpoint the Google Home app uses during
     * setup, and every Chromecast / Cast-enabled TV / Google TV device serves it
     * on the LAN. Using it means discovery does not depend on mDNS at all.
     *
     * Deliberately a *separate* sweep from the Digi X TV one: the two use
     * different ports and different response shapes, and folding them together
     * would make a failure in one look like a failure in both.
     */
    private suspend fun scanChromecasts(hosts: List<String>): Int {
        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val found = AtomicInteger(0)

        coroutineScope {
            for (host in hosts) {
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val target = probeChromecast(host)
                        if (target != null) {
                            found.incrementAndGet()
                            registerScanned(target)
                        }
                    }
                }
            }
        }
        return found.get()
    }

    /**
     * Ask one host if it is a Cast device.
     *
     * Only a 200 carrying a `name` field counts. A random web server on 8008 will
     * not answer this path with that shape, so the check stays honest instead of
     * filling the picker with false positives.
     */
    private suspend fun probeChromecast(host: String): CastTarget? {
        return try {
            val body = httpGetScan(
                "http://$host:$CHROMECAST_INFO_PORT/setup/eureka_info",
                scanInfoHttp,
            ) ?: return null
            val json = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
            val name = json.optString("name").takeIf { it.isNotBlank() }
                ?: json.optString("ssdp_udn").takeIf { it.isNotBlank() }
                ?: return null
            CastTarget(
                name = name,
                host = host,
                port = ChromecastClient.CAST_V2_PORT,
                kind = dev.digi.digix.data.CastKind.CHROMECAST,
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Probe every host on every port in [ports], all concurrently behind one
     * semaphore, and return how many endpoints were registered.
     */
    private suspend fun sweep(hosts: List<String>, ports: List<Int>): Int {
        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val found = AtomicInteger(0)

        coroutineScope {
            for (host in hosts) {
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val target = probeHost(host, ports)
                        if (target != null) {
                            found.incrementAndGet()
                            registerScanned(target)
                        }
                    }
                }
            }
        }
        return found.get()
    }

    /**
     * Try each of [ports] on [host], returning the first that answers as a
     * Digi X TV.
     *
     * The ports are probed **concurrently**, sharing one timeout window. Probing
     * them in sequence multiplied the sweep time by the port count - 254 hosts x
     * 3 ports x 600 ms was over ten seconds on a real TV, which is exactly the
     * "discovery is slow" complaint. Concurrently it costs one timeout.
     */
    private suspend fun probeHost(host: String, ports: List<Int>): CastTarget? =
        coroutineScope {
            val probes = ports.map { port -> async { probeHealth(host, port) } }
            probes.firstNotNullOfOrNull { it.await() }
        }

    private suspend fun probeHealth(host: String, port: Int): CastTarget? {
        return try {
            val body = httpGetScan("http://$host:$port/health") ?: return null
            // Decoding the whole status keeps the scan honest: any other web
            // server on the LAN will not answer as a Digi X TV.
            val name = runCatching {
                CastJson.decodeFromString(CastStatus.serializer(), body).deviceName
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            CastTarget(name = name, host = host, port = port)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Non-blocking GET.
     *
     * This must NOT be a blocking `execute()` on `Dispatchers.IO`: that pool is
     * capped at 64 threads, so 254 parallel probes silently serialised into four
     * waves and the sweep took ~10 s on a real TV. `enqueue` parks no thread, so
     * the whole /24 really is in flight at once and finishes in one timeout.
     */
    private suspend fun httpGetScan(url: String): String? = httpGetScan(url, scanHttp)

    /** Same non-blocking GET against a specific client. */
    private suspend fun httpGetScan(url: String, client: OkHttpClient): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).get().build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = runCatching {
                        response.use {
                            if (it.isSuccessful) it.body?.string() else null
                        }
                    }.getOrNull()
                    if (continuation.isActive) continuation.resumeWith(Result.success(body))
                }
            })
        }

    private fun registerScanned(target: CastTarget) {
        val endpoint = key(target)
        val isNew = byAddress.put(endpoint, target) == null
        addressSeenAt[endpoint] = SystemClock.elapsedRealtime()
        // Only repaint for a genuinely new endpoint. A repeat sweep of the same
        // /24 would otherwise publish 250 identical lists a second.
        if (isNew) {
            Log.d(TAG, "active scan found $endpoint (${target.name})")
            publish()
        }
    }

    /** Local IPv4, preferring Wi-Fi then Ethernet - same policy as the receiver. */
    private fun findLocalIpv4(): String? = runCatching {
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
    }.getOrNull()

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

    private fun enqueueResolve(info: NsdServiceInfo, kind: CastKind = CastKind.DIGIX) {
        val name = info.serviceName
        if (name.isNullOrBlank()) return

        // Re-resolving a known name on every scan is wasteful, but never
        // resolving it again would pin a TV to a stale address after a DHCP
        // lease change, so the entry expires.
        val last = resolvedAt[name]
        if (last != null && SystemClock.elapsedRealtime() - last < RESOLVE_TTL_MS) return
        if (resolveQueue.any { it.serviceName == name }) return

        pendingKinds[name] = kind
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
                        val kind = name?.let { pendingKinds[it] } ?: CastKind.DIGIX
                        if (!name.isNullOrBlank() && !host.isNullOrBlank() && port in 1..65535) {
                            // A Chromecast advertises its device description on
                            // port 8008/8009 depending on model; the Cast v2
                            // control port is always 8009, so pin it rather than
                            // trusting the SRV record.
                            val effectivePort =
                                if (kind == CastKind.CHROMECAST) ChromecastClient.CAST_V2_PORT
                                else port
                            byName[name] = CastTarget(
                                name = if (kind == CastKind.CHROMECAST) "$name" else name,
                                host = host,
                                port = effectivePort,
                                kind = kind,
                            )
                            resolvedAt[name] = SystemClock.elapsedRealtime()
                            pendingKinds.remove(name)
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
            val endpoint = byName[name]?.let { key(it) }
            val seenAt = maxOf(
                resolvedAt[name] ?: 0L,
                endpoint?.let { addressSeenAt[it] } ?: 0L,
            )
            if (now - seenAt > STALE_MS) {
                iterator.remove()
                resolvedAt.remove(name)
                changed = true
            }
        }

        val addressIterator = byAddress.keys.iterator()
        while (addressIterator.hasNext()) {
            val endpoint = addressIterator.next()
            val seenAt = addressSeenAt[endpoint] ?: 0L
            if (now - seenAt > STALE_MS) {
                addressIterator.remove()
                addressSeenAt.remove(endpoint)
                changed = true
            }
        }

        if (changed) publish()
    }

    /**
     * Merge NSD results and scan results into one de-duplicated list.
     *
     * NSD entries come first so the display *name* is the TV's advertised one
     * ("Living Room TCL") rather than a bare address, and an endpoint seen by
     * both sources is shown exactly once.
     */
    private fun publish() {
        val seen = HashSet<String>(byName.size + byAddress.size)
        val list = ArrayList<CastTarget>(byName.size + byAddress.size)

        for (target in byName.values) {
            if (seen.add(key(target))) list.add(target)
        }
        for ((endpoint, target) in byAddress) {
            if (seen.add(endpoint)) list.add(target)
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

            probeDigiX(bareHost, resolvedPort) ?: probeCastDevice(bareHost)
        }

    /** Ask `host:port` for this app's `/health` payload. */
    private fun probeDigiX(bareHost: String, port: Int): CastTarget? =
        runCatching {
            val request = Request.Builder()
                .url("http://${bracket(bareHost)}:$port/health")
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
                CastTarget(name = name, host = bareHost, port = port)
            }
        }.getOrNull()

    /**
     * Ask the Cast setup port who lives at [bareHost].
     *
     * Manual entry used to be Digi-X-TV-only, so typing a Chromecast's address
     * always failed even though the device was sitting right there. Trying the
     * Cast endpoint as a fallback makes "enter the address" work for both kinds.
     */
    private fun probeCastDevice(bareHost: String): CastTarget? = runCatching {
        val request = Request.Builder()
            .url("http://${bracket(bareHost)}:$CHROMECAST_INFO_PORT/setup/eureka_info")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            val json = org.json.JSONObject(body)
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: return@use null
            CastTarget(
                name = name,
                host = bareHost,
                port = ChromecastClient.CAST_V2_PORT,
                kind = CastKind.CHROMECAST,
            )
        }
    }.getOrNull()

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

        /**
         * NSD goes quiet without telling anyone; a rescan is the only cure. Kept
         * short because the gap between rescans is dead time for a newly woken
         * TV, and the active scan already covers the common case.
         */
        const val RESCAN_INTERVAL_MS = 12_000L

        /** An immediate stop-then-start is rejected by the framework. */
        const val RESTART_GAP_MS = 700L

        /** Re-resolve a known name after this, to follow DHCP changes. */
        const val RESOLVE_TTL_MS = 60_000L

        /** Drop a TV that no scan has confirmed for this long. */
        const val STALE_MS = 90_000L

        /** How often the /24 is re-probed while the picker is open. */
        const val SCAN_INTERVAL_MS = 8_000L

        /**
         * Bounds the worst case. A live TV answers in single-digit milliseconds;
         * an *absent* address is what costs time, because the OS waits on ARP
         * before giving up. 400 ms was enough to reach every TV on the test
         * network, and a full /24 sweep then lands in roughly two batches.
         */
        const val SCAN_TIMEOUT_MS = 400L

        const val SCAN_CONCURRENCY = 254

        /**
         * Budget for one Cast setup probe.
         *
         * Higher than [SCAN_TIMEOUT_MS] on purpose: a Cast receiver's embedded
         * web server answers noticeably slower than our own `/health` endpoint,
         * and the earlier 400 ms window is what made real Cast TVs intermittently
         * invisible in the picker.
         */
        const val CHROMECAST_TIMEOUT_MS = 1_200L

        /** The receiver binds [CAST_DEFAULT_PORT] + 0..10, so match that range. */
        const val SCAN_PORT_ATTEMPTS = 3

        const val MULTICAST_LOCK_TAG = "digix-cast-discovery"

        /**
         * mDNS service type every Google Cast device advertises. Discovering it
         * is what makes a Chromecast (or a Cast-enabled TV) appear in the picker.
         */
        const val CHROMECAST_SERVICE_TYPE = "_googlecast._tcp."

        /**
         * Plain-HTTP setup port every Cast device serves `/setup/eureka_info` on.
         * Used for the active scan so discovery does not depend on mDNS.
         */
        const val CHROMECAST_INFO_PORT = 8008

        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * Only the scanner uses this: a /24 is 254 probes and each one must give
         * up almost immediately, or the whole sweep outlives the picker.
         *
         * The dispatcher limits matter more than the timeouts. OkHttp's default
         * is 64 concurrent requests, so a semaphore of 128 still ran the sweep in
         * four sequential batches and took ~10 s on a real TV. Raising it to the
         * full host count lets every address be tried in one window.
         */
        val scanHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = SCAN_CONCURRENCY
                        maxRequestsPerHost = SCAN_CONCURRENCY
                    }
                )
                .build()
        }

        /**
         * The Cast setup probe uses its own client.
         *
         * A Google Cast *receiver* is not our receiver: it runs a small embedded
         * HTTP server that answers `/setup/eureka_info` after doing its own work,
         * and on real hardware (a TCL Google TV) that took longer than the 400 ms
         * our own `/health` endpoint needs. A timeout tuned for the cheap case
         * made Cast TVs randomly absent from the picker, which is precisely the
         * bug being fixed here - so this one gets a realistic budget instead of
         * being squeezed to keep the sweep fast.
         */
        val scanInfoHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CHROMECAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CHROMECAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = SCAN_CONCURRENCY
                        maxRequestsPerHost = SCAN_CONCURRENCY
                    }
                )
                .build()
        }

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
