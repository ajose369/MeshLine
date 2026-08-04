package org.meshline.app.ui.radar

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import org.meshline.app.db.PeerNodeEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.transport.UsbSerialManager
import org.meshline.app.ui.theme.*

data class PeerNode(
    val nodeId: String,
    val deviceModel: String,
    val rssiDbm: Int,
    val hopDistance: Int,
    val lastSeenSec: Int,
    val transport: String
)

@Composable
fun RadarScreen() {
    val context = LocalContext.current
    val usbManager = remember { UsbSerialManager(context) }
    var loraConnected by remember { mutableStateOf(usbManager.scanConnectedDevices().isNotEmpty()) }
    val storeAndForward = remember { StoreAndForwardManager.getInstance(context) }
    val dbPeers by storeAndForward.peersFlow.collectAsState()

    // Radar Rotation Animation
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Angle"
    )

    val activePeers = dbPeers.map { entity ->
        PeerNode(
            nodeId = entity.nodeId,
            deviceModel = entity.deviceModel,
            rssiDbm = entity.rssiDbm,
            hopDistance = entity.hopDistance,
            lastSeenSec = entity.lastSeenSec,
            transport = entity.transport
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Mesh Radar & Topology",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Opportunistic Neighbor Discovery Engine",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Surface(
                color = GlassSurfaceCard,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, GlassSurfaceBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = NeonCyan, shape = CircleShape, modifier = Modifier.size(6.dp)) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${activePeers.size} Peers Near",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Visual Radar Sweep Component
        Card(
            colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassSurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Background Concentric Circles
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(Color.Transparent, CircleShape)
                        .border(1.dp, NeonCyan.copy(alpha = 0.2f), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(Color.Transparent, CircleShape)
                        .border(1.dp, NeonCyan.copy(alpha = 0.4f), CircleShape)
                )

                // Rotating Radar Sweep Needle
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .rotate(rotationAngle)
                        .background(
                            brush = Brush.sweepGradient(
                                colors = listOf(Color.Transparent, NeonCyan.copy(alpha = 0.4f))
                            ),
                            shape = CircleShape
                        )
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📡 MESH DISCOVERY ACTIVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Scanning BLE • Wi-Fi Direct • LoRa OTG",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Battery Duty Cycle Card
        Card(
            colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔋 Adaptive Duty Cycle Throttle",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Surface(
                        color = EmergencyGreen.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Normal (85% Duty)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmergencyGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { 0.78f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = SafetyAmber,
                    trackColor = ObsidianBackground
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Battery level: 78% • Active scan cycle: 15s scan / 15s idle",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Connected & Relaying Mesh Nodes (${activePeers.size})",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(activePeers) { peer ->
                PeerRowCard(peer)
            }
        }
    }
}

@Composable
fun PeerRowCard(peer: PeerNode) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GlassSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.deviceModel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = Color(0xFF1E242F),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = peer.transport,
                            fontSize = 9.sp,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "ID: ${peer.nodeId} • Hop distance: ${peer.hopDistance} hop • Last seen ${peer.lastSeenSec}s ago",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Surface(
                color = Color(0xFF1E242F),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "${peer.rssiDbm} dBm",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SafetyAmber,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
