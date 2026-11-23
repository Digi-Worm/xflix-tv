package dev.digi.xflixtv.ui.screens

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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import dev.digi.xflixtv.ui.theme.*

@Composable
fun AddonsScreen(
    installed: List<String>,
    onInstall: (String, (Boolean) -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().background(Bg)) {
        Text(
            "Addons",
            color = OnBg,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = TvDimens.contentStart, top = 28.dp, bottom = 16.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvDimens.contentStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Addon manifest URL") },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                modifier = Modifier.width(560.dp),
            )
            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        status = "Installing…"
                        onInstall(url.trim()) { ok ->
                            status = if (ok) "Installed." else "Not a valid addon manifest."
                            if (ok) url = ""
                        }
                    }
                },
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                colors = ButtonDefaults.colors(
                    containerColor = Accent,
                    contentColor = Color.Black,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
            ) {
                Text("Install", fontWeight = FontWeight.SemiBold)
            }
        }

        status?.let {
            Text(
                it,
                color = if (it.startsWith("Not")) Color(0xFFFF7A7A) else Accent,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = TvDimens.contentStart, top = 10.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = TvDimens.contentStart),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonRow(addon: String, onRemove: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = { },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier else Modifier),
        shape = CardDefaults.shape(RoundedCornerShape(TvDimens.corner)),
        colors = CardDefaults.colors(containerColor = if (focused) SurfaceFocused else Surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(if (focused) Accent else MutedDim, RoundedCornerShape(50))
            )
            Spacer(Modifier.width(14.dp))
            Text(
                addon,
                color = OnBg,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(14.dp))
            Button(
                onClick = { onRemove(addon) },
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                colors = ButtonDefaults.colors(
                    containerColor = SurfaceHigh,
                    contentColor = OnBg,
                    focusedContainerColor = Color(0xFFFF7A7A),
                    focusedContentColor = Color.Black,
                ),
            ) {
                Text("Remove", fontSize = 12.sp)
            }
        }
    }
}
