package dev.digi.xflixtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.digi.xflixtv.ui.AppRoot
import dev.digi.xflixtv.ui.theme.XflixTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            XflixTvTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Accepts xflix://detail/<type>/<id> and xflix://stream/<infoHash>[/<fileIdx>].
     * Stream links let a user hand a specific torrent straight to the player.
     */
    private fun handleDeepLink(intent: android.content.Intent?) {
        val data = intent?.data?.toString() ?: return
        val path = data.removePrefix("xflix://").removePrefix("https://")
            .substringBefore('?')
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size >= 2 && parts[0] == "stream") {
            val hash = parts[1]
            val idx = parts.getOrNull(2)?.toIntOrNull() ?: 0
            XflixTvApp.instance.deepLinkStream = dev.digi.xflixtv.data.Stream(
                name = "Deep link",
                infoHash = hash,
                fileIdx = idx,
            )
        }
    }
}