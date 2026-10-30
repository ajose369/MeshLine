package org.meshline.app.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.PeerNodeEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.ui.components.*
import org.meshline.app.ui.theme.*

/**
 * Shows the devices this phone has genuinely exchanged authenticated packets
 * with. Nothing is simulated: an empty radar means an empty radar.
 */
@Composable
fun RadarScreen() {
    val context = LocalContext.current
    val store = remember { StoreAndForwardManager.getInstance(context) }
    val peers by store.peersFlow.collectAsState()

    var nodeId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nodeId = MeshCoreBridge.nodeIdHex()
            store.prunePeers()
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        ScreenHeader(
            eyebrow = "Mesh radar",
            title = "Who is carrying for you",
            subtitle = "Devices exchanging verified traffic with this phone",
            accent = EmergencyGreen
        )

        Spacer(Modifier.height(16.dp))

        RadarFace(
            peers = peers,
            live = MeshCoreBridge.isReady(),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(250.dp)
        )

        Spacer(Modifier.height(18.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MeshMark(Modifier.size(20.dp), color = if (nodeId != null) NeonCyan else SignalRed)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "THIS DEVICE",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = nodeId ?: "Mesh core unavailable",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = MeshMono,
                        color = if (nodeId != null) NeonCyan else SignalRed
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (peers.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = MeshIcons.Radar,
                    title = "No devices in range",
                    body = "MeshLine keeps scanning in the background. Move closer to " +
                        "other people running the app, or to higher ground, to extend range.",
                    accent = EmergencyGreen
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(peers, key = { it.nodeId }) { peer -> PeerRow(peer, now) }
            }
        }

        Spacer(Modifier.height(12.dp))
        PanicWipeControl(onWipe = { store.panicWipe() })
        Spacer(Modifier.height(14.dp))
    }
}

/* ---------------------------------------------------------------------------
 * The radar face
 * ------------------------------------------------------------------------- */

/**
 * Plots each peer at its measured signal strength.
 *
 * The distance from the centre is derived from RSSI, which is a genuine
 * measurement, so a peer that drifts away visibly moves outward. The *angle* is
 * not a measurement — BLE gives no direction — so it is derived from the node
 * id, which at least keeps each device in a stable place between frames instead
 * of skittering around and implying a bearing nobody knows.
 */
@Composable
private fun RadarFace(
    peers: List<PeerNodeEntity>,
    live: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "Radar")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Sweep"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Pulse"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val outer = size.minDimension / 2f - 2f

            // Range rings.
            listOf(1f, 0.68f, 0.36f).forEach { fraction ->
                drawCircle(
                    color = GlassSurfaceBorder.copy(alpha = if (fraction == 1f) 0.9f else 0.55f),
                    radius = outer * fraction,
                    center = centre,
                    style = Stroke(width = 1f)
                )
            }

            // Cross hairs and graduations.
            repeat(4) { i ->
                val rad = Math.toRadians(i * 45.0)
                val dx = sin(rad).toFloat()
                val dy = -cos(rad).toFloat()
                drawLine(
                    color = GlassSurfaceBorder.copy(alpha = 0.4f),
                    start = Offset(centre.x - outer * dx, centre.y - outer * dy),
                    end = Offset(centre.x + outer * dx, centre.y + outer * dy),
                    strokeWidth = 0.9f
                )
            }
            repeat(72) { i ->
                val rad = Math.toRadians(i * 5.0)
                val long = i % 9 == 0
                val inner = outer - if (long) 8f else 4f
                drawLine(
                    color = GlassSurfaceBorder.copy(alpha = if (long) 0.9f else 0.45f),
                    start = Offset(
                        centre.x + inner * sin(rad).toFloat(),
                        centre.y - inner * cos(rad).toFloat()
                    ),
                    end = Offset(
                        centre.x + outer * sin(rad).toFloat(),
                        centre.y - outer * cos(rad).toFloat()
                    ),
                    strokeWidth = if (long) 1.1f else 0.7f
                )
            }

            // The sweep only turns when the transport is actually running.
            if (live) {
                rotate(degrees = sweepAngle, pivot = centre) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                EmergencyGreen.copy(alpha = 0.04f),
                                EmergencyGreen.copy(alpha = 0.26f)
                            ),
                            center = centre
                        ),
                        radius = outer,
                        center = centre
                    )
                }
            }

            // Own position.
            drawCircle(NeonCyan.copy(alpha = 0.25f), 7f, centre)
            drawCircle(NeonCyan, 3f, centre)

            peers.forEach { peer ->
                val angle = Math.toRadians(peerAngle(peer.nodeId).toDouble())
                val distance = outer * peerRange(peer.rssiDbm)
                val at = Offset(
                    centre.x + distance * sin(angle).toFloat(),
                    centre.y - distance * cos(angle).toFloat()
                )
                val colour = trustColor(peer.hasSecureSession, peer.isVerified)

                // A slow halo, so a blip is findable against the grid without
                // needing the sweep to be passing over it.
                drawCircle(
                    color = colour.copy(alpha = 0.22f * (1f - pulse)),
                    radius = 5f + 11f * pulse,
                    center = at
                )
                drawCircle(colour.copy(alpha = 0.3f), 8f, at)
                drawCircle(colour, 3.6f, at)
            }
        }

        // The count sits under the centre rather than on it, so it never covers
        // a peer plotted close in.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = 46.dp)
        ) {
            Text(
                text = "${peers.size}",
                style = MaterialTheme.typography.displayLarge,
                fontSize = 34.sp,
                color = if (peers.isEmpty()) TextMuted else EmergencyGreen
            )
            Text(
                text = if (peers.size == 1) "DEVICE IN RANGE" else "DEVICES IN RANGE",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

/** A stable angle per node id — a placement, not a bearing. */
private fun peerAngle(nodeId: String): Float {
    var hash = 7
    nodeId.forEach { hash = hash * 31 + it.code }
    return abs(hash % 360).toFloat()
}

/** RSSI mapped onto the face: −40 dBm sits near the centre, −95 at the rim. */
private fun peerRange(rssiDbm: Int): Float {
    val clamped = rssiDbm.coerceIn(-95, -40)
    return (0.18f + (abs(clamped) - 40) / 55f * 0.76f).coerceIn(0.18f, 0.94f)
}

/* ---------------------------------------------------------------------------
 * Peers
 * ------------------------------------------------------------------------- */

@Composable
private fun PeerRow(peer: PeerNodeEntity, nowMillis: Long) {
    val secondsAgo = ((nowMillis - peer.lastSeenMillis) / 1000).coerceAtLeast(0)
    // Three states, not two: an encrypted session whose identity nobody has
    // checked is a genuinely weaker position than a verified one, and
    // collapsing them into one green badge would hide that.
    val colour = trustColor(peer.hasSecureSession, peer.isVerified)
    val badge = when {
        !peer.hasSecureSession -> "no session"
        peer.isVerified -> "verified"
        else -> "unverified"
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalBars(peer.rssiDbm, colour)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    peer.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${peer.transport} · ${peer.rssiDbm} dBm · heard ${secondsAgo}s ago",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = TextMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(
                text = badge,
                color = colour,
                icon = if (peer.isVerified) MeshIcons.Check else null
            )
        }
    }
}

/** Four bars filled from the measured RSSI. */
@Composable
private fun SignalBars(rssiDbm: Int, colour: Color) {
    val filled = when {
        rssiDbm >= -55 -> 4
        rssiDbm >= -70 -> 3
        rssiDbm >= -82 -> 2
        else -> 1
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(20.dp)
    ) {
        repeat(4) { i ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((6 + i * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i < filled) colour else GlassSurfaceBorder
                    )
            )
        }
    }
}

/* ---------------------------------------------------------------------------
 * Panic wipe
 * ------------------------------------------------------------------------- */

/**
 * Destroys everything on this device that could be read off it.
 *
 * Behind a confirmation because it is irreversible, but only one tap deep,
 * because the moment it is needed is not a moment for menus.
 */
@Composable
private fun PanicWipeControl(onWipe: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var wiped by remember { mutableStateOf(false) }

    if (wiped) {
        NoticeCard(
            title = "Secure data wiped",
            body = "Sessions, group keys, and messages are gone from this device. " +
                "Your mesh identity was kept, so contacts who verified you still " +
                "recognise this phone.",
            accent = EmergencyGreen,
            icon = MeshIcons.Check
        )
        return
    }

    GhostButton(
        text = "Wipe all secure data",
        onClick = { confirming = true },
        accent = SignalRed,
        icon = MeshIcons.Wipe,
        modifier = Modifier.fillMaxWidth()
    )

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = ObsidianElevated,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            shape = RoundedCornerShape(22.dp),
            icon = {
                Icon(MeshIcons.Warning, contentDescription = null, tint = SignalRed)
            },
            title = {
                Text(
                    "Wipe secure data?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    "This deletes every encrypted session, every group key, and every " +
                        "stored message on this phone. It cannot be undone, and it cannot " +
                        "recall anything already sent. Your groups will carry on without " +
                        "you until someone re-invites you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onWipe()
                        confirming = false
                        wiped = true
                    }
                ) {
                    Text("Wipe now", color = SignalRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}
