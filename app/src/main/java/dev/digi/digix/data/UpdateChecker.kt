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

    private val _hasUpdate = MutableStateFlow(false)
    val hasUpdate: StateFlow<Boolean> = _hasUpdate.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _latestVersion = MutableStateFlow<String?>(null)
    val latestVersion: StateFlow<String?> = _latestVersion.asStateFlow()

    companion object {
        private const val PREF_LAST_CHECK = "last_check_time"
        private const val PREF_VERSION_CODE = "installed_version_code"
        private const val CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/Digi-Worm/xflix-tv/main/update.json"
    }

    init {
        // Fire the first check in the background; never block startup.
        scope.launch { checkForUpdates(force = true) }
    }

    suspend fun checkForUpdates(force: Boolean = false) {
        if (_isChecking.value) return
        _isChecking.value = true

        try {
            val currentTime = System.currentTimeMillis()
            val lastCheckTime = prefs.getLong(PREF_LAST_CHECK, 0)
            val timeSinceLastCheck = currentTime - lastCheckTime

            if (!force && timeSinceLastCheck < TimeUnit.DAYS.toMillis(1)) {
                _isChecking.value = false
                return
            }

            val release = fetchLatestRelease()
            if (release != null) {
                val installedVersionCode = prefs.getInt(PREF_VERSION_CODE, 0)
                val hasUpdate = release.versionCode > installedVersionCode

                _latestVersion.value = release.tagName
                _hasUpdate.value = hasUpdate

                if (hasUpdate) {
                    showNotification(release)
                }

                prefs.edit()
                    .putLong(PREF_LAST_CHECK, currentTime)
                    .apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _isChecking.value = false
        }
    }

    private suspend fun fetchLatestRelease(): GitHubReleaseInfo? {
        // Preferred source: a tiny manifest committed at the repo root. Publishing
        // an update is then just "edit update.json, push" - no GitHub Release, no
        // CLI tooling, and it works on a private repo over the raw URL.
        fetchFromManifest()?.let { return it }
        return fetchFromReleasesApi()
    }

    private suspend fun fetchFromManifest(): GitHubReleaseInfo? {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(MANIFEST_URL)
                .header("Cache-Control", "no-cache")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string()
            if (body.isNullOrBlank()) return null

            val manifest = json.decodeFromString<UpdateManifest>(body)
            GitHubReleaseInfo(
                versionCode = manifest.versionCode,
                tagName = manifest.versionName,
                name = manifest.versionName,
                body = manifest.notes,
                htmlUrl = manifest.apkUrl,
                publishedAt = manifest.publishedAt ?: "",
                downloadUrl = manifest.apkUrl
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchFromReleasesApi(): GitHubReleaseInfo? {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://api.github.com/repos/Digi-Worm/xflix-tv/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    GitHubReleaseInfo.fromGitHubRelease(json.decodeFromString(body), 0)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun showNotification(release: GitHubReleaseInfo) {
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
        val intent = Intent(context, DownloadUpdateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
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

        private fun extractVersionCode(tagName: String): Int {
            val match = Regex("[0-9]+").find(tagName)
            return match?.value?.toIntOrNull() ?: 0
        }
    }
}
