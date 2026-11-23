package dev.digi.digix.ui.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.digi.digix.data.CastTarget
import dev.digi.digix.ui.theme.Accent
import dev.digi.digix.ui.theme.BrandAccent
import dev.digi.digix.ui.theme.Muted
import dev.digi.digix.ui.theme.MutedDim
import dev.digi.digix.ui.theme.OnAccent
import dev.digi.digix.ui.theme.OnBg
import dev.digi.digix.ui.theme.Surface
import dev.digi.digix.ui.theme.SurfaceFocused
import dev.digi.digix.ui.theme.SurfaceHigh
import dev.digi.digix.ui.theme.UiMetrics
import dev.digi.digix.ui.theme.uiMetrics

/** Scrim behind the sheet: dim enough to kill the page, light enough to read it. */
private val Scrim = Color(0xB3000000)

/** Failure text. A muted rose rather than pure red, which reads as an alarm. */
private val ErrorTint = Color(0xFFE88A8A)

/**
 * TV picker overlay.
 *
 * Deliberately not a `ModalBottomSheet`: this has to work on a 960x540 TV panel
 * driven by a D-pad, inside the app's own chrome, where the Material bottom
 * sheet's drag gestures and window insets are wrong. A plain centred card with
 * focusable rows behaves on both a remote and a phone.
 *
 * The sheet is stateless — every action is reported to the caller, which owns
 * discovery and the in-flight request.
 *
 * @param visible render (and animate in) only when true.
 * @param deviceName this device's own name, shown so the user can tell which
 *   screen the cast originates from.
 * @param castingTo non-null when a cast is already live; the sheet then offers
 *   "Stop casting" instead of the device list.
 * @param busy a cast request is in flight; taps are ignored meanwhile.
 * @param error one-line failure message, or null.
 * @param targets TVs currently visible on the LAN.
 * @param onPick a discovered TV was chosen.
 * @param onManualConnect the user typed an address and pressed Connect.
 * @param onStopCasting stop the live cast.
 */
@Composable
fun CastSheet(
    visible: Boolean,
    deviceName: String,
    castingTo: CastTarget?,
    busy: Boolean,
    error: String?,
    targets: List<CastTarget>,
    onDismiss: () -> Unit,
    onPick: (CastTarget) -> Unit,
    onManualConnect: (String) -> Unit,
    onStopCasting: () -> Unit,
) {
    val m = uiMetrics()

    // Scrim fades; the card lifts a few percent. Scaling the overlay rather than
    // the card is intentional: the scrim is transparent at the start of the
    // entrance, so the 4% margin it leaves is invisible while it fades in.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
            scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            ),
        exit = fadeOut(tween(150, easing = FastOutSlowInEasing)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // The scrim is a sibling *behind* the card, not a parent. A tap
            // detector wrapped around a scrollable card eats the touch-down and
            // the card stops scrolling, so dismissal lives on its own layer:
            // anywhere the card does not cover belongs to the scrim.
            //
            // pointerInput rather than clickable: clickable would make the whole
            // screen a D-pad focus stop on TV, which traps the cursor behind the
            // card. This also avoids a ripple across the scrim.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Scrim)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )

            val shape = RoundedCornerShape(m.d(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = m.d(420.dp))
                    .clip(shape)
                    .background(Surface)
                    .border(1.dp, SurfaceHigh, shape)
                    // No extra tap consumer here: verticalScroll already owns a
                    // pointer-input node across the whole card, so taps on its
                    // padding are consumed by the card and never reach the scrim.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = m.d(20.dp), vertical = m.d(18.dp)),
            ) {
                Text(
                    text = "This device · $deviceName",
                    color = MutedDim,
                    fontSize = m.f(10f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(m.d(6.dp)))
                Text(
                    text = "Cast to TV",
                    color = OnBg,
                    fontSize = m.f(21f),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(m.d(5.dp)))
                Text(
                    // Explains the architecture: the TV pulls the stream itself,
                    // which is why the two devices only need to share a network,
                    // not a Bluetooth-class pairing.
                    text = "The TV plays this using its own network, so it keeps going " +
                        "when you lock your phone or leave the room.",
                    color = Muted,
                    fontSize = m.f(12f),
                    lineHeight = m.f(17f),
                )
                Spacer(Modifier.height(m.d(16.dp)))

                if (castingTo != null) {
                    NowCasting(target = castingTo, busy = busy, onStop = onStopCasting, m = m)
                } else {
                    DevicePicker(
                        targets = targets,
                        busy = busy,
                        onPick = onPick,
                        onManualConnect = onManualConnect,
                        m = m,
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(m.d(12.dp)))
                    Text(
                        text = error,
                        color = ErrorTint,
                        fontSize = m.f(12f),
                        lineHeight = m.f(16f),
                    )
                }
            }
        }
    }
}

/** Live-cast state: one confirmation line and the single destructive action. */
@Composable
private fun NowCasting(
    target: CastTarget,
    busy: Boolean,
    onStop: () -> Unit,
    m: UiMetrics,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(m.d(14.dp)))
            .background(SurfaceHigh.copy(alpha = 0.55f))
            .padding(m.d(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CastConnected,
            contentDescription = null,
            tint = BrandAccent,
            modifier = Modifier.size(m.d(22.dp)),
        )
        Spacer(Modifier.width(m.d(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Now casting to ${target.name}",
                color = OnBg,
                fontSize = m.f(14f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(m.d(2.dp)))
            Text(
                text = target.host,
                color = MutedDim,
                fontSize = m.f(11f),
                maxLines = 1,
            )
        }
    }

    Spacer(Modifier.height(m.d(14.dp)))

    CastButton(
        text = if (busy) "Stopping…" else "Stop casting",
        filled = true,
        enabled = !busy,
        onClick = onStop,
        m = m,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Discovered-device list plus the manual address fallback. */
@Composable
private fun DevicePicker(
    targets: List<CastTarget>,
    busy: Boolean,
    onPick: (CastTarget) -> Unit,
    onManualConnect: (String) -> Unit,
    m: UiMetrics,
) {
    if (targets.isEmpty()) {
        ScanningHint(m)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(m.d(8.dp))) {
            targets.forEach { target ->
                DeviceRow(
                    target = target,
                    enabled = !busy,
                    onClick = { onPick(target) },
                    m = m,
                )
            }
        }
    }

    Spacer(Modifier.height(m.d(18.dp)))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SurfaceHigh)
    )
    Spacer(Modifier.height(m.d(16.dp)))

    ManualAddress(
        busy = busy,
        onConnect = onManualConnect,
        m = m,
    )
}

/** Empty state: a spinner, and the one hint that actually fixes bad discovery. */
@Composable
private fun ScanningHint(m: UiMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = Muted.copy(alpha = 0.8f),
            strokeWidth = 2.dp,
            modifier = Modifier.size(m.d(16.dp)),
        )
        Spacer(Modifier.width(m.d(10.dp)))
        Text(
            text = "Scanning for TVs…",
            color = OnBg,
            fontSize = m.f(13f),
            fontWeight = FontWeight.Medium,
        )
    }
    Spacer(Modifier.height(m.d(6.dp)))
    Text(
        // Multicast is blocked across subnets and on many guest networks, so
        // this hint saves the user from reporting "it just never finds my TV".
        text = "Both devices must be on the same Wi-Fi. If your TV never appears, " +
            "enter its address below.",
        color = Muted,
        fontSize = m.f(11f),
        lineHeight = m.f(16f),
    )
}

/** One discovered TV. Focus state is explicit so a remote can see the cursor. */
@Composable
private fun DeviceRow(
    target: CastTarget,
    enabled: Boolean,
    onClick: () -> Unit,
    m: UiMetrics,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(m.d(13.dp))
    // Touch devices have no focus state, so the row has to look tappable all
    // the time; on a TV the fill only lights up under the cursor.
    val highlighted = focused || m.isTouch

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (m.isTouch) m.d(56.dp) else m.d(52.dp))
            .clip(shape)
            .background(if (focused) SurfaceFocused else SurfaceHigh.copy(alpha = 0.5f))
            .then(
                if (focused) Modifier.border(1.dp, Accent.copy(alpha = 0.45f), shape)
                else Modifier.border(1.dp, Color.Transparent, shape)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = m.d(13.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Tv,
            contentDescription = null,
            tint = if (highlighted) BrandAccent else Muted,
            modifier = Modifier.size(m.d(20.dp)),
        )
        Spacer(Modifier.width(m.d(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                text = target.name,
                color = OnBg,
                fontSize = m.f(13f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(m.d(1.dp)))
            Text(
                text = "Ready",
                color = MutedDim,
                fontSize = m.f(10f),
                maxLines = 1,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = if (highlighted) OnBg.copy(alpha = 0.7f) else MutedDim,
            modifier = Modifier.size(m.d(18.dp)),
        )
    }
}

/**
 * Address entry, for the TV that multicast will never find.
 *
 * On a compact phone the field and button stack: a 360dp-wide screen cannot
 * hold a legible field plus a button on one line.
 */
@Composable
private fun ManualAddress(
    busy: Boolean,
    onConnect: (String) -> Unit,
    m: UiMetrics,
) {
    var address by remember { mutableStateOf("") }
    val trimmed = address.trim()
    val valid = looksLikeAddress(trimmed)
    val submit: () -> Unit = { if (valid && !busy) onConnect(trimmed) }

    Text(
        text = "Connect by IP",
        color = OnBg,
        fontSize = m.f(12f),
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(m.d(3.dp)))
    Text(
        text = "For TVs on a different subnet or a network that blocks multicast.",
        color = MutedDim,
        fontSize = m.f(10f),
        lineHeight = m.f(14f),
    )
    Spacer(Modifier.height(m.d(10.dp)))

    if (m.isCompact) {
        AddressField(
            value = address,
            onValueChange = { address = it },
            busy = busy,
            valid = valid,
            onGo = submit,
            m = m,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(m.d(8.dp)))
        CastButton(
            text = if (busy) "Connecting…" else "Connect",
            filled = false,
            enabled = valid && !busy,
            onClick = submit,
            m = m,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.d(10.dp)),
        ) {
            AddressField(
                value = address,
                onValueChange = { address = it },
                busy = busy,
                valid = valid,
                onGo = submit,
                m = m,
                modifier = Modifier.weight(1f),
            )
            CastButton(
                text = if (busy) "…" else "Connect",
                filled = false,
                enabled = valid && !busy,
                onClick = submit,
                m = m,
            )
        }
    }
}

/** The IP field, shared by the stacked and side-by-side layouts. */
@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    valid: Boolean,
    onGo: () -> Unit,
    m: UiMetrics,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = !busy,
        label = { Text("TV IP address", fontSize = m.f(11f)) },
        placeholder = {
            Text("192.168.1.109", color = MutedDim, fontSize = m.f(12f))
        },
        textStyle = TextStyle(fontSize = m.f(13f), color = OnBg),
        isError = value.isNotBlank() && !valid,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        // material3's default field is light-surfaced; these keep it inside the
        // monochrome dark palette instead of punching a white hole in the card.
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = OnBg,
            unfocusedTextColor = OnBg,
            cursorColor = Accent,
            focusedBorderColor = Accent.copy(alpha = 0.75f),
            unfocusedBorderColor = SurfaceHigh,
            focusedLabelColor = Muted,
            unfocusedLabelColor = MutedDim,
            errorBorderColor = ErrorTint,
            errorLabelColor = ErrorTint,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        shape = RoundedCornerShape(m.d(12.dp)),
        modifier = modifier,
    )
}

/**
 * Pill button in two weights.
 *
 * Built by hand rather than from material3 because the focused state has to be
 * legible on a TV: [filled] draws near-black text on white (never white on
 * white), and focus adds an outer ring plus a small lift.
 */
@Composable
private fun CastButton(
    text: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    m: UiMetrics,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(
        targetValue = if (focused && enabled) 1.03f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "castButtonLift",
    )
    val shape = RoundedCornerShape(50)
    val container = when {
        !enabled -> SurfaceHigh
        filled -> Accent
        else -> SurfaceHigh.copy(alpha = 0.9f)
    }
    val content = when {
        !enabled -> MutedDim
        filled -> OnAccent
        else -> OnBg
    }
    val ring = if (focused && enabled) Accent.copy(alpha = 0.55f) else Color.Transparent

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .clip(shape)
            .background(ring)
            .padding(m.d(2.dp))
            .clip(shape)
            .background(container)
            .heightIn(min = if (m.isTouch) m.d(48.dp) else m.d(44.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = content,
            fontSize = m.f(13f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = m.d(18.dp), vertical = m.d(6.dp)),
        )
    }
}

/**
 * Compact cast affordance for the player chrome.
 *
 * Tinted [BrandAccent] while a cast is live; otherwise quiet, so it does not
 * compete with playback controls.
 */
@Composable
fun CastIconButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = uiMetrics()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val tint = when {
        active -> BrandAccent
        focused -> OnBg
        else -> Muted
    }

    Box(
        modifier = modifier
            .heightIn(min = m.d(40.dp))
            .widthIn(min = m.d(40.dp))
            .clip(shape)
            .background(if (focused) SurfaceFocused else Color.Transparent)
            // Hairline pill: visible enough to read as a control, faint enough
            // to disappear over video.
            .border(1.dp, if (focused) Accent.copy(alpha = 0.45f) else OnBg.copy(alpha = 0.16f), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = if (active) "Stop casting" else "Cast to TV",
            tint = tint,
            modifier = Modifier.size(m.d(20.dp)),
        )
    }
}

/**
 * Cheap client-side sanity check before a network call.
 *
 * Only rejects things that cannot be an address at all: whitespace, empty
 * labels, bad characters, an out-of-range port. It deliberately does not try to
 * validate IPv4 octets — a hostname is equally valid, and the /health probe is
 * the real test.
 */
private fun looksLikeAddress(raw: String): Boolean {
    val cleaned = raw.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
    if (cleaned.isEmpty() || cleaned.length > 260) return false
    if (cleaned.any { it.isWhitespace() }) return false

    val host = cleaned.substringBefore(':')
    val portPart = cleaned.substringAfter(':', "")
    if (portPart.isNotEmpty() && portPart.toIntOrNull()?.let { it in 1..65535 } != true) return false

    if (host.isEmpty() || host.split('.').any { it.isEmpty() }) return false
    if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return false
    // A bare word is only meaningful for localhost; everything else needs a dot.
    return host.equals("localhost", ignoreCase = true) || host.contains('.')
}
