package dev.digi.digix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digi.digix.data.StorageJanitor
import dev.digi.digix.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheLabel by remember { mutableStateOf("measuring…") }
    var torrentLabel by remember { mutableStateOf("measuring…") }
    var busy by remember { mutableStateOf(false) }
    var lastClean by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val (cache, torrents) = withContext(Dispatchers.IO) {
                StorageJanitor.dirSize(context.cacheDir) to
                    StorageJanitor.dirSize(File(context.filesDir, "torrents"))
            }
            cacheLabel = StorageJanitor.formatBytes(cache)
            torrentLabel = StorageJanitor.formatBytes(torrents)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.padding(start = TvDimens.contentStart, top = 28.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DX",
                color = BrandAccent,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.5).sp,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Digi X TV",
                    color = OnBg,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Settings & Preferences",
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        }

        SettingLine("App Name", "Digi X TV")
        SettingLine("Version", "${dev.digi.digix.BuildConfig.VERSION_NAME} (TV Edition)")
        SettingLine("UI Theme", "Cinema Dark (4K HDR)")
        SettingLine("Addons & Catalogs", "Managed dynamically via Addons tab")
        SettingLine("Playback Engine", "ExoPlayer Hardware Decoding & High-Performance Torrent Engine")
        SettingLine("Account", "Not required — fully local, zero tracking")

        Spacer(Modifier.height(10.dp))

        ActionLine(
            title = "Storage & Memory",
            value = "Cache $cacheLabel  •  Streams $torrentLabel  •  auto-cleans every 15 min",
            action = if (busy) "Cleaning…" else "Clean now",
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    StorageJanitor.sweep(context, aggressive = true)
                    busy = false
                    lastClean = "Freed cache and stream residue"
                    refresh()
                }
            },
        )
        lastClean?.let {
            Text(
                it,
                color = BrandSuccess,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = TvDimens.contentStart, top = 6.dp),
            )
        }
    }
}

@Composable
private fun SettingLine(title: String, value: String) {
    Column(
        Modifier
            .padding(horizontal = TvDimens.contentStart, vertical = 5.dp)
            .fillMaxWidth(0.72f)
            .clip(RoundedCornerShape(TvDimens.corner))
            .background(Surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(title, color = OnBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Muted, fontSize = 13.sp)
    }
}

/** Focusable row so the remote can trigger a manual sweep. */
@Composable
private fun ActionLine(
    title: String,
    value: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(horizontal = TvDimens.contentStart, vertical = 5.dp)
            .fillMaxWidth(0.72f)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(TvDimens.corner))
            .background(if (focused) SurfaceFocused else Surface)
            .border(1.dp, if (focused) BrandAccent else SurfaceHigh, RoundedCornerShape(TvDimens.corner))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(value, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            action,
            color = if (enabled) BrandAccent else MutedDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
