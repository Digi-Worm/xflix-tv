package dev.digi.digix.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.digi.digix.MainActivity
import dev.digi.digix.ui.DownloadUpdateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Simple automatic update checker that runs on app launch.
 * Checks GitHub for a new release and shows a notification when one exists.
 */
class UpdateChecker(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One client for every manifest/release call. Rebuilding it per request
     * created a new connection pool and thread pool each time, which is both
     * wasteful and meant a stale pool could linger after a failed check.
     */
    private val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val _hasUpdate = MutableStateFlow(false)
    val hasUpdate: StateFlow<Boolean> = _hasUpdate.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _latestVersion = MutableStateFlow<String?>(null)
    val latestVersion: StateFlow<String?> = _latestVersion.asStateFlow()

    /** Result of the most recent check, used to drive the Settings row. */
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The newer release, if the last check found one. */
    private val _available = MutableStateFlow<GitHubReleaseInfo?>(null)
    val available: StateFlow<GitHubReleaseInfo?> = _available.asStateFlow()

    companion object {
        private const val PREF_LAST_CHECK = "last_check_time"
        private const val PREF_VERSION_CODE = "installed_version_code"
        private const val CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/Digi-Worm/xflix-tv/main/update.json"
        private const val API_CONTENTS_URL =
            "https://api.github.com/repos/Digi-Worm/xflix-tv/contents/update.json"
    }

    init {
        // Fire the first check in the background; never block startup.
        scope.launch { checkForUpdates(force = true) }
    }

    suspend fun checkForUpdates(force: Boolean = false) {
        if (_isChecking.value) return
        _isChecking.value = true
        _state.value = UpdateState.Checking

        try {
            // Force the network work onto IO. The Settings button calls this from
            // rememberCoroutineScope(), which is bound to the main thread, and the
            // HTTP client below blocks; without this, the manual check threw
            // NetworkOnMainThreadException, the catch turned it into a null
            // release, and the UI claimed it could not reach GitHub - while the
            // launch check (already on IO) worked fine.
            withContext(Dispatchers.IO) {
                val currentTime = System.currentTimeMillis()
                val lastCheckTime = prefs.getLong(PREF_LAST_CHECK, 0)
                val timeSinceLastCheck = currentTime - lastCheckTime

                if (!force && timeSinceLastCheck < TimeUnit.DAYS.toMillis(1)) {
                    // Restore the verdict from the previous check instead of
                    // leaving the row on Idle, otherwise a gated check would
                    // silently drop the "Update now" action the user already saw.
                    _state.value =
                        if (_hasUpdate.value) UpdateState.Available else UpdateState.UpToDate
                    return@withContext
                }

                val release = fetchLatestRelease()
                android.util.Log.i("UpdateChecker", "manifest fetch result: $release")
                if (release == null) {
                    _state.value = UpdateState.Failed
                } else {
                    // Compare against what is installed *now*, read from the
                    // package manager. The stored pref is written by the previous
                    // build, so a user who sideloads a newer APK would otherwise
                    // be compared against a stale number.
                    val installed = installedVersionCode()
                    val hasUpdate = release.versionCode > installed
                    android.util.Log.i(
                        "UpdateChecker",
                        "installed=$installed remote=${release.versionCode} hasUpdate=$hasUpdate"
                    )

                    _latestVersion.value = release.tagName
                    _hasUpdate.value = hasUpdate
                    _available.value = if (hasUpdate) release else null
                    _state.value = if (hasUpdate) UpdateState.Available else UpdateState.UpToDate

                    if (hasUpdate) {
                        showNotification(release)
                    }

                    prefs.edit()
                        .putLong(PREF_LAST_CHECK, currentTime)
                        .putInt(PREF_VERSION_CODE, installed)
                        .apply()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("UpdateChecker", "check failed", e)
            _state.value = UpdateState.Failed
        } finally {
            _isChecking.value = false
        }
    }

    /** The versionCode actually installed, or the build constant if unknown. */
    private fun installedVersionCode(): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            @Suppress("DEPRECATION")
            it.versionCode
        }.takeIf { it > 0 } ?: dev.digi.digix.BuildConfig.VERSION_CODE
    } catch (e: Exception) {
        dev.digi.digix.BuildConfig.VERSION_CODE
    }

    /** Manual check triggered by the Settings button; always bypasses the daily gate. */
    suspend fun checkNow() {
        checkForUpdates(force = true)
    }

    private suspend fun fetchLatestRelease(): GitHubReleaseInfo? {
        // Preferred source: a tiny manifest committed at the repo root. Publishing
        // an update is then just "edit update.json, push" - no GitHub Release, no
        // CLI tooling, and it works on a private repo over the raw URL.
        fetchFromManifest()?.let { return it }
        return fetchFromReleasesApi()
    }

    private suspend fun fetchFromManifest(): GitHubReleaseInfo? {
        // api.github.com is not CDN-cached the way raw.githubusercontent.com is.
        // Raw ignored query-string cache busting, so a fresh push could take
        // several minutes to appear; the contents API returns it immediately.
        val sources = listOf(
            "$API_CONTENTS_URL" to "application/vnd.github.raw+json",
            "$MANIFEST_URL?cb=${System.currentTimeMillis()}" to "text/plain",
        )
        for ((url, accept) in sources) {
            val body = httpGet(url, accept) ?: continue
            try {
                val manifest = json.decodeFromString<UpdateManifest>(body)
                return GitHubReleaseInfo(
                    versionCode = manifest.versionCode,
                    tagName = manifest.versionName,
                    name = manifest.versionName,
                    body = manifest.notes,
                    htmlUrl = manifest.apkUrl,
                    publishedAt = manifest.publishedAt ?: "",
                    downloadUrl = manifest.apkUrl
                )
            } catch (e: Exception) {
                android.util.Log.w("UpdateChecker", "bad manifest from $url", e)
            }
        }
        return null
    }

    private fun httpGet(url: String, accept: String): String? {
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "DigiX-TV")
                .build()

            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    // 403 from the unauthenticated contents API means the
                    // 60-requests/hour quota is gone; the raw URL is the fallback.
                    android.util.Log.w("UpdateChecker", "HTTP ${response.code} for $url")
                    null
                }
            }
        } catch (e: Exception) {
            // Never silent: a bare null here was what made a real failure look
            // like "no update available".
            android.util.Log.w("UpdateChecker", "GET failed for $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun fetchFromReleasesApi(): GitHubReleaseInfo? {
        return try {
            val request = okhttp3.Request.Builder()
                .url("https://api.github.com/repos/Digi-Worm/xflix-tv/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "DigiX-TV")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 404 is expected: this repo publishes through update.json
                    // rather than GitHub Releases, so there is no "latest".
                    android.util.Log.d("UpdateChecker", "no release: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    null
                } else {
                    GitHubReleaseInfo.fromGitHubRelease(json.decodeFromString(body), 0)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("UpdateChecker", "release lookup failed", e)
            null
        }
    }

    private fun showNotification(release: GitHubReleaseInfo) {
        // Android 13+ needs a runtime grant, and nothing in the app ever asked
        // for it, so on a phone the notification was dropped without a trace
        // even though the check succeeded. The Settings row still shows the
        // update, which is why the manual path has to work independently of this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.i("UpdateChecker", "no POST_NOTIFICATIONS grant; skipping notification")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val downloadIntent = Intent(context, DownloadUpdateActivity::class.java).apply {
            putExtra("release_url", release.downloadUrl)
            putExtra("release_name", release.tagName)
        }
        val downloadPendingIntent = PendingIntent.getActivity(
            context,
            1,
            downloadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Automatic update notifications"
                enableLights(true)
                enableVibration(true)
                lightColor = ContextCompat.getColor(context, android.R.color.holo_blue_bright)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Update Available")
            .setContentText("New version ${release.tagName} is available")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_info_details,
                "Download",
                downloadPendingIntent
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun openDownloadActivity() {
        val release = _available.value
        val intent = Intent(context, DownloadUpdateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (release != null) {
                putExtra("release_url", release.downloadUrl)
                putExtra("release_name", release.tagName)
            }
        }
        context.startActivity(intent)
    }
}

/** Where the last update check stands, for the Settings row. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Available : UpdateState
    data object Failed : UpdateState
}

@kotlinx.serialization.Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String? = null,
    val publishedAt: String? = null
)

@kotlinx.serialization.Serializable
data class GitHubRelease(
    val id: Long,
    val tag_name: String,
    val name: String? = null,
    val body: String? = null,
    val html_url: String,
    val published_at: String,
    val assets: List<Asset> = emptyList()
)

@kotlinx.serialization.Serializable
data class Asset(
    val name: String,
    val browser_download_url: String,
    val size: Long = 0L
)

@kotlinx.serialization.Serializable
data class GitHubReleaseInfo(
    val versionCode: Int,
    val tagName: String,
    val name: String?,
    val body: String?,
    val htmlUrl: String,
    val publishedAt: String,
    val downloadUrl: String
) {
    companion object {
        fun fromGitHubRelease(release: GitHubRelease, currentVersionCode: Int): GitHubReleaseInfo {
            val versionCode = extractVersionCode(release.tag_name)
            val versionName = release.name ?: release.tag_name
            val apkAsset = release.assets.find { it.name.contains(".apk", ignoreCase = true) }
            val downloadUrl = apkAsset?.browser_download_url ?: ""

            return GitHubReleaseInfo(
                versionCode = versionCode,
                tagName = release.tag_name,
                name = versionName,
                body = release.body,
                htmlUrl = release.html_url,
                publishedAt = release.published_at,
                downloadUrl = downloadUrl
            )
        }

        /**
         * Derive a comparable versionCode from a tag such as `2.2.0-b18`.
         *
         * Taking the first number in the string (the old behaviour) turned
         * `2.2.0-b18` into `2`, which compares *below* every build and would
         * report "up to date" forever. Prefer an explicit build number, then
         * fold a semver triple into a monotonic int.
         */
        private fun extractVersionCode(tagName: String): Int {
            Regex("-b(\\d+)", RegexOption.IGNORE_CASE).find(tagName)?.let { match ->
                match.groupValues[1].toIntOrNull()?.let { return it }
            }
            val parts = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(tagName)
            if (parts != null) {
                val major = parts.groupValues[1].toIntOrNull() ?: 0
                val minor = parts.groupValues[2].toIntOrNull() ?: 0
                val patch = parts.groupValues[3].toIntOrNull() ?: 0
                return major * 10_000 + minor * 100 + patch
            }
            return Regex("[0-9]+").find(tagName)?.value?.toIntOrNull() ?: 0
        }
    }
}
