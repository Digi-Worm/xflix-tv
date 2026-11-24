package dev.digi.digix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.digi.digix.ui.AppRoot
import dev.digi.digix.ui.theme.DigiXTheme

class MainActivity : ComponentActivity() {
    /**
     * Android 13+ drops notifications unless this is granted at runtime. The
     * update checker can only *detect* without it, so the request has to happen
     * somewhere reachable; asking once at first launch is the least intrusive
     * place. A denial is fine - the Settings row remains the manual path.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            android.util.Log.i("MainActivity", "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        requestNotificationPermissionIfNeeded()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            DigiXTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }.onFailure {
            android.util.Log.w("MainActivity", "notification permission request failed", it)
        }
    }

    /**
     * Accepts digix://detail/<type>/<id> and digix://stream/<infoHash>[/<fileIdx>].
     * Stream links let a user hand a specific torrent straight to the player.
     */
    private fun handleDeepLink(intent: android.content.Intent?) {
        val data = intent?.data?.toString() ?: return
        val path = data.removePrefix("digix://").removePrefix("https://")
            .substringBefore('?')
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size >= 2 && parts[0] == "stream") {
            val hash = parts[1]
            val idx = parts.getOrNull(2)?.toIntOrNull() ?: 0
            DigiXApp.instance.deepLinkStream = dev.digi.digix.data.Stream(
                name = "Deep link",
                infoHash = hash,
                fileIdx = idx,
            )
        }
    }
}