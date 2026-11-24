package dev.digi.digix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digi.digix.ui.theme.*

@Composable
fun AddonsScreen(
    installed: List<String>,
    onInstall: (String, (Boolean) -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val m = uiMetrics()

    Column(modifier.fillMaxSize().background(Bg)) {
        Text(
            "Addons",
            color = OnBg,
            fontSize = m.f(24f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                start = m.d(TvDimens.contentStart),
                top = m.d(24.dp),
                bottom = m.d(14.dp),
            ),
        )

        // The field used to be a fixed 560dp wide, which is wider than a whole
        // phone in portrait. It takes the available width now and the button
        // drops beneath it when there is not enough room beside it.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = m.d(TvDimens.contentStart)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.d(12.dp)),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Addon manifest URL") },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = m.f(13f)),
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Button(
                onClick = {
                    if (url.isNotBlank()) {
                        status = "Installing…"
                        onInstall(url.trim()) { ok ->
                            status = if (ok) "Installed." else "Not a valid addon manifest."
                            if (ok) url = ""
                        }
                    }
                },
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = OnAccent,
                ),
            ) {
                Text("Install", fontWeight = FontWeight.SemiBold, fontSize = m.f(13f))
            }
        }

        status?.let {
            Text(
                it,
                color = if (it.startsWith("Not")) Color(0xFFE88A8A) else OnBg,
                fontSize = m.f(12f),
                modifier = Modifier.padding(start = m.d(TvDimens.contentStart), top = m.d(10.dp)),
            )
        }

        Spacer(Modifier.height(m.d(16.dp)))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = m.d(TvDimens.contentStart)),
            verticalArrangement = Arrangement.spacedBy(m.d(9.dp)),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(installed) { addon ->
                AddonRow(addon, onRemove)
            }
            if (installed.isEmpty()) {
                item {
                    Text(
                        "No addons installed. Paste a manifest URL above.",
                        color = Muted,
                        fontSize = m.f(13f),
                        modifier = Modifier.padding(m.d(14.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonRow(addon: String, onRemove: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val m = uiMetrics()
    // Material3 Surface with onClick: the tv.material3.Card here only activated
    // from a D-pad, so taps did nothing on a phone.
    androidx.compose.material3.Surface(
        onClick = { },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(m.d(TvDimens.corner)),
        color = if (focused) SurfaceFocused else Surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = m.d(16.dp), vertical = m.d(12.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(m.d(8.dp))
                    .background(if (focused) Accent else MutedDim, RoundedCornerShape(50))
            )
            Spacer(Modifier.width(m.d(12.dp)))
            Text(
                addon,
                color = OnBg,
                fontSize = m.f(12f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(m.d(12.dp)))
            androidx.compose.material3.Button(
                onClick = { onRemove(addon) },
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = SurfaceHigh,
                    contentColor = OnBg,
                ),
            ) {
                Text("Remove", fontSize = m.f(11f))
            }
        }
    }
}
