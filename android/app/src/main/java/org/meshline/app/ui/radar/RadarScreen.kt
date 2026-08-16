package org.meshline.app.ui.radar

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.PeerNodeEntity
import org.meshline.app.db.StoreAndForwardManager
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
    LaunchedEffect(Unit) {
        while (true) {
            nodeId = MeshCoreBridge.nodeIdHex()
            store.prunePeers()
            delay(5_000)
        }
    }

    val sweep = rememberInfiniteTransition(label = "RadarSweep")
    val sweepAngle by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Angle"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Mesh Radar",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = "Devices exchanging verified mesh traffic with this phone",
            fontSize = 11.sp,
            color = TextMuted
        )

        Spacer(Modifier.height(20.dp))

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            listOf(200.dp, 140.dp, 80.dp).forEach { size ->
                Box(
                    modifier = Modifier
                        .size(size)
                        .background(Color.Transparent, CircleShape)
                        .border(1.dp, GlassSurfaceBorder, CircleShape)
                )
            }

            // The sweep only turns when the transport is actually running.
            if (MeshCoreBridge.isReady()) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .rotate(sweepAngle)
                        .background(
                            brush = Brush.sweepGradient(
                                listOf(Color.Transparent, EmergencyGreen.copy(alpha = 0.25f))
                            ),
                            shape = CircleShape
                        )
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${peers.size}",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    color = if (peers.isEmpty()) TextMuted else EmergencyGreen
                )
                Text(
                    text = if (peers.size == 1) "device" else "devices",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, GlassSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "This device",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )
                Text(
                    text = nodeId ?: "Mesh core unavailable",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (nodeId != null) NeonCyan else SignalRed
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (peers.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "No devices in range.\n\nMeshLine keeps scanning in the " +
                        "background. Move closer to other people running the app, " +
                        "or higher ground, to extend range.",
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 18.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(peers, key = { it.nodeId }) { peer -> PeerRow(peer) }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerNodeEntity) {
    val secondsAgo = ((System.currentTimeMillis() - peer.lastSeenMillis) / 1000).coerceAtLeast(0)

    Card(
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GlassSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    peer.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "${peer.transport} • last heard ${secondsAgo}s ago",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
            Surface(
                color = if (peer.hasSecureSession) {
                    EmergencyGreen.copy(alpha = 0.18f)
                } else {
                    GlassSurfaceBorder
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (peer.hasSecureSession) "encrypted" else "no session",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (peer.hasSecureSession) EmergencyGreen else TextMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
