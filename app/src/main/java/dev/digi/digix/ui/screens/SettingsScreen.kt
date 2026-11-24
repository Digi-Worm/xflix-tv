package dev.digi.digix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.digi.digix.DigiXApp
import dev.digi.digix.data.StorageJanitor
import dev.digi.digix.data.UpdateState
import dev.digi.digix.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    castInfo: dev.digi.digix.ui.CastInfo? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheLabel by remember { mutableStateOf("measuring…") }
    var torrentLabel by remember { mutableStateOf("measuring…") }
    var busy by remember { mutableStateOf(false) }
    var lastClean by remember { mutableStateOf<String?>(null) }

    val checker = remember { DigiXApp.instance.updateChecker }
    val updateState by checker.state.collectAsState()
    val available by checker.available.collectAsState()

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

    val m = uiMetrics()
    Column(
        modifier
            .fillMaxSize()
            .background(Bg)
            // Six setting lines plus the storage row do not fit a phone in
            // portrait; without a scroll the last row was unreachable.
            .verticalScroll(rememberScrollState())
            .padding(bottom = m.d(28.dp)),
    ) {
        Row(
            modifier = Modifier.padding(
                start = m.d(TvDimens.contentStart),
                end = m.d(TvDimens.contentStart),
                top = m.d(24.dp),
                bottom = m.d(14.dp),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DX",
                color = BrandAccent,
                fontSize = m.f(25f),
                fontWeight = FontWeight.Black,
                letterSpacing = m.f(-1.4f),
            )
            Spacer(Modifier.width(m.d(9.dp)))
            Column {
                Text(
                    "Digi X TV",
                    color = OnBg,
                    fontSize = m.f(21f),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Settings & Preferences",
                    color = Muted,
                    fontSize = m.f(12f),
                )
            }
        }

        SettingLine("App Name", "Digi X TV")
        SettingLine("Version", "${dev.digi.digix.BuildConfig.VERSION_NAME} (TV / Mobile)")

        // Manual update check. Automatic checks run at most once a day, so this is
        // the path a user takes when they know a release just shipped.
        val updateValue = when (val s = updateState) {
            is UpdateState.Checking -> "Checking GitHub for a newer release…"
            is UpdateState.Available ->
                "Version ${available?.tagName ?: "newer"} is available. Tap to download and install."
            is UpdateState.UpToDate ->
                "You are on the latest version (build ${dev.digi.digix.BuildConfig.VERSION_CODE})."
            is UpdateState.Failed -> "Could not reach GitHub. Check the connection and retry."
            is UpdateState.Idle -> "Tap to check for a newer version."
        }
        ActionLine(
            title = "Check for updates",
            value = updateValue,
            action = when (updateState) {
                is UpdateState.Checking -> "Checking…"
                is UpdateState.Available -> "Update now"
                else -> "Check now"
            },
            enabled = updateState !is UpdateState.Checking,
            onClick = {
                if (updateState is UpdateState.Available) {
                    checker.openDownloadActivity()
                } else {
                    scope.launch { checker.checkNow() }
                }
            },
        )
        SettingLine("UI Theme", "Cinema Dark (4K HDR)")
        SettingLine("Addons & Catalogs", "Managed dynamically via Addons tab")
        SettingLine("Playback Engine", "ExoPlayer Hardware Decoding & High-Performance Torrent Engine")
        SettingLine("Account", "Not required — fully local, zero tracking")
        // Shown so a phone on a network where multicast is blocked can still
        // connect by typing the address.
        castInfo?.let { info ->
            val where = info.host?.let { "$it : ${info.port}" } ?: "Unavailable — no LAN address"
            SettingLine("Cast to this TV", info.name + " · " + where)
        }

        Spacer(Modifier.height(m.d(9.dp)))

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
                fontSize = m.f(11.5f),
                modifier = Modifier.padding(start = m.d(TvDimens.contentStart), top = m.d(6.dp)),
            )
        }
    }
}

@Composable
private fun SettingLine(title: String, value: String) {
    val m = uiMetrics()
    Column(
        Modifier
            .padding(horizontal = m.d(TvDimens.contentStart), vertical = m.d(4.dp))
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.d(TvDimens.corner)))
            .background(Surface)
            .padding(horizontal = m.d(18.dp), vertical = m.d(12.dp)),
    ) {
        Text(title, color = OnBg, fontSize = m.f(14f), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(m.d(3.dp)))
        Text(value, color = Muted, fontSize = m.f(12f))
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
    val m = uiMetrics()
    Row(
        Modifier
            .padding(horizontal = m.d(TvDimens.contentStart), vertical = m.d(4.dp))
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(m.d(TvDimens.corner)))
            .background(if (focused) SurfaceFocused else Surface)
            .border(m.d(1.dp), if (focused) BrandAccent else SurfaceHigh, RoundedCornerShape(m.d(TvDimens.corner)))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = m.d(18.dp), vertical = m.d(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnBg, fontSize = m.f(14f), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(m.d(3.dp)))
            Text(value, color = Muted, fontSize = m.f(12f))
        }
        Spacer(Modifier.width(m.d(14.dp)))
        Text(
            action,
            color = if (enabled) BrandAccent else MutedDim,
            fontSize = m.f(12f),
            fontWeight = FontWeight.Bold,
        )
    }
}
