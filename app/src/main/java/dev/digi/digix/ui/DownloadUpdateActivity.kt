package dev.digi.digix.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity to download the latest APK from GitHub and hand it to the installer.
 */
class DownloadUpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val releaseUrl = intent.getStringExtra("release_url") ?: ""
            val releaseName = intent.getStringExtra("release_name") ?: "Update"
            // The build the manifest advertised. Used both to name the download
            // (so a new build never reuses an old file) and to verify the APK we
            // actually got before handing it to the installer.
            val targetVersionCode = intent.getIntExtra("version_code", 0)

            var progress by remember { mutableStateOf(0) }
            var isDownloading by remember { mutableStateOf(false) }
            var isComplete by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            var failureText by remember { mutableStateOf<String?>(null) }

            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            // Guards the auto-install: the polling loop can observe SUCCESSFUL on
            // more than one tick before it breaks, and launching the installer
            // twice stacks two system prompts.
            var installLaunched by remember { mutableStateOf(false) }

            /**
             * The file DownloadManager actually wrote, taken from the query rather
             * than assumed.
             *
             * DownloadManager does NOT overwrite an existing destination: it saves
             * alongside it as `name-1.apk`, `name-2.apk`, and so on. Installing the
             * hard-coded `DigiX-TV-update.apk` therefore reinstalled the *previous*
             * download, which is exactly why an update looked like it did nothing
             * and left the old version installed.
             */
            var localUri by remember { mutableStateOf<Uri?>(null) }

            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

            /** A per-build name, so two versions can never collide on disk. */
            fun apkFileName(): String =
                if (targetVersionCode > 0) "DigiX-TV-update-$targetVersionCode.apk"
                else "DigiX-TV-update-${System.currentTimeMillis()}.apk"

            /** Remove leftovers so the device does not accumulate old installers. */
            fun clearOldDownloads() {
                runCatching {
                    downloadsDir?.listFiles()
                        ?.filter { it.name.startsWith("DigiX-TV-update") && it.extension == "apk" }
                        ?.forEach { it.delete() }
                }
            }

            fun startDownload() {
                if (releaseUrl.isBlank()) {
                    failed = true
                    failureText = "No download link was provided."
                    return
                }
                try {
                    clearOldDownloads()
                    val fileName = apkFileName()
                    val request = DownloadManager.Request(Uri.parse(releaseUrl)).apply {
                        setTitle("Digi X TV $releaseName")
                        setDescription("Downloading the latest version")
                        setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        setDestinationInExternalFilesDir(
                            context,
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                        )
                        setAllowedOverMetered(true)
                        setAllowedOverRoaming(false)
                    }
                    val id = downloadManager.enqueue(request)
                    isDownloading = true
                    failed = false
                    failureText = null
                    progress = 0

                    lifecycleScope.launch {
                        while (true) {
                            var status = -1
                            var done = 0L
                            var total = 0L
                            var reported: String? = null
                            val cursor = downloadManager.query(
                                DownloadManager.Query().setFilterById(id)
                            )
                            if (cursor.moveToFirst()) {
                                status = cursor.getInt(
                                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                                )
                                done = cursor.getLong(
                                    cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                    )
                                )
                                total = cursor.getLong(
                                    cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                                    )
                                )
                                val uriIndex =
                                    cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                if (uriIndex >= 0) reported = cursor.getString(uriIndex)
                            }
                            cursor.close()

                            if (total > 0) progress = ((done * 100) / total).toInt()

                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                progress = 100
                                isDownloading = false
                                localUri = reported?.let { Uri.parse(it) }
                                isComplete = true
                                break
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                isDownloading = false
                                failed = true
                                failureText = "Download failed. Tap retry."
                                break
                            }
                            delay(500)
                        }
                    }
                } catch (e: Exception) {
                    isDownloading = false
                    failed = true
                    failureText = e.message
                }
            }

            fun installApk() {
                val file = localUri?.takeIf { it.scheme == "file" }?.path?.let { File(it) }
                    ?: downloadsDir?.let { File(it, apkFileName()) }

                if (file == null || !file.exists() || file.length() == 0L) {
                    isComplete = false
                    failed = true
                    failureText = "The downloaded file is missing. Tap retry."
                    return
                }

                // Verify the APK is the build we advertised before handing it to
                // the installer. This is the check that would have caught the
                // stale-file bug above instead of silently reinstalling the same
                // version and looking like nothing happened.
                if (targetVersionCode > 0) {
                    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    @Suppress("DEPRECATION")
                    val actual = info?.versionCode ?: -1
                    if (actual != targetVersionCode) {
                        runCatching { file.delete() }
                        isComplete = false
                        failed = true
                        failureText =
                            "Downloaded build ${if (actual > 0) actual else "unknown"} " +
                                "instead of $targetVersionCode. Tap retry."
                        return
                    }
                }

                try {
                    val contentUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.updates",
                        file
                    )
                    val install = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(install)
                } catch (e: Exception) {
                    failed = true
                    failureText = "Cannot open installer: ${e.message}"
                }
            }

            // One tap, then it runs itself: download starts on open and the
            // installer is raised the moment the APK lands. The system "update
            // this app?" dialog is still shown by Android and cannot be skipped.
            LaunchedEffect(Unit) {
                if (!isDownloading && !isComplete && !failed) startDownload()
            }
            LaunchedEffect(isComplete) {
                if (isComplete && !installLaunched) {
                    installLaunched = true
                    installApk()
                }
            }

            Surface(color = Color(0xFF0C0C10), modifier = Modifier.fillMaxSize()) {
                Scaffold(containerColor = Color(0xFF0C0C10)) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text(
                            "Update available",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Digi X TV $releaseName is ready to download. Downloading it " +
                                "will replace the installed version once you confirm the install.",
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Progress bar
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress / 100f)
                                    .height(8.dp)
                                    .background(Color.White, RoundedCornerShape(4.dp))
                            )
                        }
                        Text(
                            when {
                                failed -> failureText
                                    ?: "Download failed. Check the connection and retry."
                                isComplete -> "Download complete. Opening the installer…"
                                isDownloading -> "Downloading… $progress%"
                                else -> "Preparing download…"
                            },
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        if (!isDownloading && !isComplete) {
                            PillButton(
                                text = if (failed) "Retry download" else "Download update",
                                filled = true,
                                onClick = { startDownload() }
                            )
                        }
                        if (isComplete) {
                            PillButton(
                                text = "Install now",
                                filled = true,
                                accent = true,
                                onClick = { installApk() }
                            )
                        }
                        PillButton(
                            text = "Close",
                            filled = false,
                            onClick = { finish() }
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PillButton(
    text: String,
    filled: Boolean,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        accent -> Color(0xFFE50914)
        filled -> Color.White
        else -> Color.White.copy(alpha = 0.13f)
    }
    val fg = if (filled || accent) Color.Black else Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (accent) Color.White else fg,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
