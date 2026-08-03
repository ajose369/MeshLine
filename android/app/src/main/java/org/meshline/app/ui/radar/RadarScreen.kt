package org.meshline.app.ui.radar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshline.app.transport.UsbSerialManager
import org.meshline.app.ui.theme.EmergencyGreen
import org.meshline.app.ui.theme.SafetyYellow

data class PeerNode(
    val nodeId: String,
    val deviceModel: String,
    val rssiDbm: Int,
    val hopDistance: Int,
    val lastSeenSec: Int
)

@Composable
fun RadarScreen() {
    val context = LocalContext.current
    val usbManager = remember { UsbSerialManager(context) }
    var loraDevices by remember { mutableStateOf(usbManager.scanConnectedDevices()) }

    val activePeers = listOf(
        PeerNode("a1b2", "Pixel 7 Pro (BLE)", -62, 1, 2),
        PeerNode("c3d4", "Galaxy S22 (BLE)", -78, 1, 5),
        PeerNode("e5f6", "Heltec V3 (LoRa 915MHz)", -45, 1, 1),
        PeerNode("7890", "OnePlus 11 (BLE)", -91, 3, 18)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Mesh Topology & Radar Diagnostics",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Real-time BLE / Wi-Fi Direct / LoRa Hardware Discovery",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // LoRa Hardware Bridge Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LoRa Hardware Bridge",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Surface(
                        color = SafetyYellow,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "USB OTG Standby",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Band: 915 MHz • SF7 • BW 125 kHz • Range: ~5km",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Duty Cycle Battery Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Adaptive Duty Cycle State",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Surface(
                        color = EmergencyGreen,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Normal (85% Relay Rate)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { 0.78f },
                    modifier = Modifier.fillMaxWidth(),
                    color = SafetyYellow,
                    trackColor = Color(0xFF2C323D)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Battery level: 78% • Duty Cycle: 15s active / 15s idle",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Active Mesh Neighbors (${activePeers.size})",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
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
                Text(
                    text = "${peer.deviceModel} (ID: ${peer.nodeId})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Hop distance: ${peer.hopDistance} hop • Last seen ${peer.lastSeenSec}s ago",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Surface(
                color = Color(0xFF2C323D),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "${peer.rssiDbm} dBm",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SafetyYellow,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
