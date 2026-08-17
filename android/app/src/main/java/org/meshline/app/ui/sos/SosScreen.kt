package org.meshline.app.ui.sos

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.location.MeshLocationProvider
import org.meshline.app.permissions.MeshPermissions
import org.meshline.app.ui.components.*
import org.meshline.app.ui.theme.*

/** Outcome of the most recent SOS attempt, surfaced verbatim to the user. */
private sealed interface SosResult {
    data object Idle : SosResult
    data class Sent(val hadLocation: Boolean) : SosResult
    data class Failed(val reason: String) : SosResult
}

@Composable
fun SosScreen() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val store = remember { StoreAndForwardManager.getInstance(context) }
    val locationProvider = remember { MeshLocationProvider(context) }
    val peers by store.peersFlow.collectAsState()

    var sosMessageText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SosResult>(SosResult.Idle) }
    var isSending by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf<MeshLocationProvider.Fix?>(null) }
    var custody by remember { mutableIntStateOf(store.pendingOutboundCount()) }

    // The queue drains from the relay service, not from here, so poll it rather
    // than trying to observe a structure that has no change notification.
    LaunchedEffect(Unit) {
        while (true) {
            custody = store.pendingOutboundCount()
            delay(2_000)
        }
    }

    // Actively request position updates while this screen is open, rather than
    // polling the last-known cache. On a device where no other app has asked
    // for a fix recently the cache is empty, which would send every SOS without
    // coordinates.
    LaunchedEffect(Unit) {
        locationProvider.positionUpdates().collect { fix = it }
    }

    val isBroadcasting = result is SosResult.Sent
    val coreReady = MeshCoreBridge.isReady()
    val enabled = coreReady && !isSending && sosMessageText.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScreenHeader(
            eyebrow = "Emergency broadcast",
            title = "Send an SOS",
            subtitle = "Phone to phone, no tower and no internet",
            accent = SignalRed
        )

        MeshTextField(
            value = sosMessageText,
            onValueChange = { if (it.length <= 500) sosMessageText = it },
            placeholder = "e.g. Two people trapped, second floor, need medical help",
            label = "What has happened, and what do you need?",
            supporting = "${sosMessageText.length}/500",
            accent = SignalRed,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        // Typing accurately is hard when your hands are shaking and the light is
        // gone. These compose a first line in one tap and stay editable, so the
        // speed never costs the specifics.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(QUICK_PHRASES) { phrase ->
                QuickPhrase(phrase) {
                    val next = if (sosMessageText.isBlank()) {
                        phrase
                    } else {
                        "${sosMessageText.trimEnd().trimEnd('.')}. $phrase"
                    }
                    if (next.length <= 500) sosMessageText = next
                }
            }
        }

        Beacon(
            enabled = enabled,
            broadcasting = isBroadcasting,
            coreReady = coreReady,
            hasText = sosMessageText.isNotBlank(),
            onSend = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                isSending = true
                val current = fix
                result = when {
                    !MeshCoreBridge.isReady() ->
                        SosResult.Failed(MeshCoreBridge.unavailableReason())

                    else -> {
                        // Send with or without coordinates. A distress
                        // call with no position still beats no call, so
                        // a missing fix must never block the broadcast.
                        val sent = store.sendSos(
                            text = sosMessageText,
                            latitude = current?.latitude?.toFloat() ?: 0f,
                            longitude = current?.longitude?.toFloat() ?: 0f
                        )
                        if (sent) {
                            SosResult.Sent(hadLocation = current != null)
                        } else {
                            SosResult.Failed("Your SOS could not be sent. Try again.")
                        }
                    }
                }
                isSending = false
            }
        )

        when (val current = result) {
            is SosResult.Failed -> NoticeCard(
                title = "SOS not sent",
                body = current.reason,
                accent = SignalRed,
                icon = MeshIcons.Warning
            )

            is SosResult.Sent -> NoticeCard(
                title = "SOS queued for relay",
                body = buildString {
                    append(
                        if (current.hadLocation) {
                            "Your coordinates are attached. "
                        } else {
                            "No position fix was available, so no coordinates are attached. " +
                                "Describe your location in the message if you can. "
                        }
                    )
                    append(
                        if (peers.isEmpty()) {
                            "No devices are in range yet — your phone will keep trying " +
                                "and will pass the message on as soon as one appears."
                        } else {
                            "Handing off to ${peers.size} device(s) in range."
                        }
                    )
                },
                accent = if (peers.isEmpty()) SafetyAmber else EmergencyGreen,
                icon = if (peers.isEmpty()) MeshIcons.Warning else MeshIcons.Check
            )

            SosResult.Idle -> Unit
        }

        val currentFix = fix
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Eyebrow("Readiness", TextMuted)
            Spacer(Modifier.height(12.dp))
            ReadoutRow(
                label = "Your position",
                value = currentFix?.formatted() ?: when {
                    // Distinguish the three reasons a fix is missing, so the
                    // user knows whether it is worth waiting or whether they
                    // need to change a setting.
                    !MeshPermissions.canUseLocation(context) -> "Permission not granted"
                    !locationProvider.isLocationEnabled() -> "Location is turned off"
                    else -> "Searching…"
                },
                valueColor = if (currentFix != null) NeonCyan else SafetyAmber,
                mono = currentFix != null
            )
            Spacer(Modifier.height(10.dp))
            HairlineDivider()
            Spacer(Modifier.height(10.dp))
            ReadoutRow(
                label = "Devices in range",
                value = if (peers.isEmpty()) "None yet" else "${peers.size}",
                valueColor = if (peers.isEmpty()) TextMuted else EmergencyGreen
            )
            Spacer(Modifier.height(10.dp))
            HairlineDivider()
            Spacer(Modifier.height(10.dp))
            ReadoutRow(
                label = "Secure core",
                value = if (coreReady) "Active" else "Unavailable",
                valueColor = if (coreReady) EmergencyGreen else SignalRed
            )
            Spacer(Modifier.height(10.dp))
            HairlineDivider()
            Spacer(Modifier.height(10.dp))
            ReadoutRow(
                label = "Carrying for the mesh",
                value = when (custody) {
                    0 -> "Nothing queued"
                    1 -> "1 packet"
                    else -> "$custody packets"
                },
                valueColor = if (custody == 0) TextMuted else NeonCyan
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(SafetyAmber)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "A public SOS is readable by anyone in range — that is what lets " +
                    "strangers help you. Private messages in the Chat tab are encrypted.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

/** One-tap phrases for the things people most often need to say first. */
private val QUICK_PHRASES = listOf(
    "People trapped",
    "Medical help needed",
    "Fire",
    "Building collapsed",
    "Need drinking water",
    "Children with us",
    "Cannot move"
)

@Composable
private fun QuickPhrase(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        modifier = Modifier
            .clip(PillShape)
            .background(GlassSurfaceCard)
            .border(1.dp, GlassSurfaceBorder, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp)
    )
}

/**
 * The send control.
 *
 * It is deliberately the largest object on the screen and reachable with a
 * thumb, because it will be used one-handed, in the dark, by someone who is
 * frightened. The expanding rings are not decoration: they run fast once a
 * broadcast is live and idle slowly before it, so the state is readable from
 * arm's length without reading any words.
 */
@Composable
private fun Beacon(
    enabled: Boolean,
    broadcasting: Boolean,
    coreReady: Boolean,
    hasText: Boolean,
    onSend: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "Beacon")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (broadcasting) 2200 else 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave"
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.955f else 1f, tween(140), label = "Press")

    val ringColour = when {
        broadcasting -> SafetyAmber
        enabled -> SignalRed
        else -> TextMuted
    }
    val ringStrength = if (broadcasting) 0.75f else if (enabled) 0.4f else 0.16f

    Box(
        modifier = Modifier.size(264.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val minR = size.minDimension * 0.34f
            val maxR = size.minDimension * 0.5f

            repeat(3) { i ->
                val phase = (wave + i / 3f) % 1f
                val radius = minR + (maxR - minR) * phase
                drawCircle(
                    color = ringColour.copy(alpha = (1f - phase) * ringStrength),
                    radius = radius,
                    center = centre,
                    style = Stroke(width = 1.5f + 2.5f * (1f - phase))
                )
            }

            // A static bed under the rings so the control has a footprint even
            // when nothing is animating.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ringColour.copy(alpha = 0.14f), Color.Transparent),
                    center = centre,
                    radius = maxR
                ),
                radius = maxR,
                center = centre
            )
        }

        Box(
            modifier = Modifier
                .size(178.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = when {
                            broadcasting -> listOf(SafetyAmber, SignalRed)
                            enabled -> listOf(SignalRed, SignalRedDeep)
                            else -> listOf(GlassSurfaceHigh, GlassSurfaceCard)
                        }
                    )
                )
                .border(
                    width = 1.5.dp,
                    color = when {
                        broadcasting -> SafetyAmber
                        enabled -> SignalRed.copy(alpha = 0.9f)
                        else -> GlassSurfaceBorder
                    },
                    shape = CircleShape
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onSend
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Text(
                    text = if (broadcasting) "SENDING" else "SEND SOS",
                    style = MaterialTheme.typography.displayMedium,
                    fontSize = 26.sp,
                    letterSpacing = 1.5.sp,
                    color = if (enabled || broadcasting) Color.White else TextMuted
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !coreReady -> "Unavailable"
                        !hasText -> "Describe your emergency first"
                        broadcasting -> "Relaying while in range"
                        else -> "Tap to broadcast"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.5.sp,
                    textAlign = TextAlign.Center,
                    color = if (enabled || broadcasting) {
                        Color.White.copy(alpha = 0.88f)
                    } else {
                        TextMuted
                    }
                )
            }
        }
    }
}
