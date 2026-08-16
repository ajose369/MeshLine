package org.meshline.app.ui.sos

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.location.MeshLocationProvider
import org.meshline.app.permissions.MeshPermissions
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
    val store = remember { StoreAndForwardManager.getInstance(context) }
    val locationProvider = remember { MeshLocationProvider(context) }
    val peers by store.peersFlow.collectAsState()

    var sosMessageText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SosResult>(SosResult.Idle) }
    var isSending by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf<MeshLocationProvider.Fix?>(null) }

    // Actively request position updates while this screen is open, rather than
    // polling the last-known cache. On a device where no other app has asked
    // for a fix recently the cache is empty, which would send every SOS without
    // coordinates.
    LaunchedEffect(Unit) {
        locationProvider.positionUpdates().collect { fix = it }
    }

    val pulse = rememberInfiniteTransition(label = "SosPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Scale"
    )

    val isBroadcasting = result is SosResult.Sent

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = SignalRed, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "MESHLINE EMERGENCY",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = TextPrimary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Phone-to-phone relay • no cell tower or internet needed",
                fontSize = 11.sp,
                color = TextMuted
            )
        }

        OutlinedTextField(
            value = sosMessageText,
            onValueChange = { if (it.length <= 500) sosMessageText = it },
            label = { Text("What has happened, and what do you need?", color = TextMuted) },
            placeholder = {
                Text("e.g. Two people trapped, second floor, need medical help", color = TextMuted)
            },
            supportingText = { Text("${sosMessageText.length}/500", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SignalRed,
                unfocusedBorderColor = GlassSurfaceBorder,
                focusedContainerColor = GlassSurfaceCard,
                unfocusedContainerColor = GlassSurfaceCard,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(230.dp)) {
            if (isBroadcasting) {
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .scale(pulseScale)
                        .background(SignalRedGlow, CircleShape)
                )
            }

            val enabled = MeshCoreBridge.isReady() && !isSending && sosMessageText.isNotBlank()

            Box(
                modifier = Modifier
                    .size(190.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = when {
                                !enabled && !isBroadcasting ->
                                    listOf(Color(0xFF4A4A4A), Color(0xFF2A2A2A))
                                isBroadcasting -> listOf(SafetyAmber, SignalRed)
                                else -> listOf(SignalRed, Color(0xFF990000))
                            }
                        ),
                        shape = CircleShape
                    )
                    .border(
                        4.dp,
                        if (isBroadcasting) SafetyAmber else if (enabled) SignalRed else Color(0xFF3A3A3A),
                        CircleShape
                    )
                    .clickable(enabled = enabled) {
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
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isBroadcasting) "SENDING" else "SEND SOS",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            !MeshCoreBridge.isReady() -> "Unavailable"
                            sosMessageText.isBlank() -> "Describe your emergency first"
                            isBroadcasting -> "Relaying while in range"
                            else -> "Tap to broadcast"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        when (val current = result) {
            is SosResult.Failed -> StatusCard(
                title = "SOS not sent",
                body = current.reason,
                accent = SignalRed
            )

            is SosResult.Sent -> StatusCard(
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
                accent = if (peers.isEmpty()) SafetyAmber else EmergencyGreen
            )

            SosResult.Idle -> Unit
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentFix = fix
                TelemetryRow(
                    label = "Your position",
                    value = currentFix?.formatted() ?: when {
                        // Distinguish the three reasons a fix is missing, so the
                        // user knows whether it is worth waiting or whether they
                        // need to change a setting.
                        !MeshPermissions.canUseLocation(context) -> "Permission not granted"
                        !locationProvider.isLocationEnabled() -> "Location is turned off"
                        else -> "Searching…"
                    },
                    valueColor = if (currentFix != null) NeonCyan else SafetyAmber
                )
                TelemetryRow(
                    label = "Devices in range",
                    value = if (peers.isEmpty()) "None yet" else "${peers.size}",
                    valueColor = if (peers.isEmpty()) TextMuted else EmergencyGreen
                )
                TelemetryRow(
                    label = "Secure core",
                    value = if (MeshCoreBridge.isReady()) "Active" else "Unavailable",
                    valueColor = if (MeshCoreBridge.isReady()) EmergencyGreen else SignalRed
                )
            }
        }

        Text(
            text = "A public SOS is readable by anyone in range — that is what lets " +
                "strangers help you. Private messages in the Chat tab are encrypted.",
            fontSize = 10.sp,
            color = TextMuted,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
private fun StatusCard(title: String, body: String, accent: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(4.dp))
            Text(body, fontSize = 11.sp, color = TextPrimary, lineHeight = 16.sp)
        }
    }
}
