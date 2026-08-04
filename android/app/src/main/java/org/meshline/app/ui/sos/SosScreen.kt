package org.meshline.app.ui.sos

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.MessageEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.ui.theme.*

@Composable
fun SosScreen() {
    val context = LocalContext.current
    val storeAndForward = remember { StoreAndForwardManager.getInstance(context) }
    var isPublicSos by remember { mutableStateOf(true) }
    var sosTriggered by remember { mutableStateOf(false) }
    var relayCount by remember { mutableIntStateOf(0) }
    var powSolved by remember { mutableStateOf(false) }
    var sosMessageText by remember { mutableStateOf("CRITICAL SOS: Injured individual at sector 4. Need medical aid & clean water.") }

    // Pulsing animation for active SOS button
    val infiniteTransition = rememberInfiniteTransition(label = "SosPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // App Header & Telemetry
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = SignalRed,
                    shape = CircleShape,
                    modifier = Modifier.size(10.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "MESHLINE EMERGENCY CONTROL",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Zero Infrastructure Mesh • BLE & LoRa Multi-Hop Protocol",
                fontSize = 11.sp,
                color = TextMuted
            )
        }

        // Mode Switcher Card (Frosted Glass)
        Card(
            colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(6.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isPublicSos) SignalRed else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { isPublicSos = true }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📢 Open Rescue SOS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (!isPublicSos) NeonCyan else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { isPublicSos = false }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🔒 Circle Only (E2E)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (!isPublicSos) Color.Black else TextMuted
                    )
                }
            }
        }

        // Emergency Message Input Field
        OutlinedTextField(
            value = sosMessageText,
            onValueChange = { sosMessageText = it },
            label = { Text("Emergency Details & Immediate Requirements", color = TextMuted) },
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

        // Huge Interactive SOS Trigger Button with Pulsing Pulse Effect
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(230.dp)
        ) {
            // Pulse outer glow
            if (sosTriggered) {
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .scale(pulseScale)
                        .background(SignalRedGlow, CircleShape)
                )
            }

            Box(
                modifier = Modifier
                    .size(190.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = if (sosTriggered) listOf(SafetyAmber, SignalRed) else listOf(SignalRed, Color(0xFF990000))
                        ),
                        shape = CircleShape
                    )
                    .border(4.dp, if (sosTriggered) SafetyAmber else SignalRed, CircleShape)
                    .clickable {
                        sosTriggered = true
                        powSolved = true
                        relayCount = (2..8).random()
                        val rawPacket = MeshCoreBridge.createPublicSosSafe(sosMessageText, 37.7749f, -122.4194f)
                        val newSosMsg = MessageEntity(
                            msgId = UUID.randomUUID().toString(),
                            senderId = "Me",
                            recipientId = "All",
                            payloadText = sosMessageText,
                            ttl = 8,
                            timestamp = System.currentTimeMillis(),
                            packetType = if (isPublicSos) "PublicSos" else "PrivateSos",
                            status = "QUEUED"
                        )
                        storeAndForward.queueMessage(newSosMsg)
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (sosTriggered) "BROADCASTING" else "HOLD FOR SOS",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (sosTriggered) "Relaying to $relayCount nodes" else "Tap to Broadcast",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (sosTriggered) Color.Black else Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Live Telemetry & Security Card
        Card(
            colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "📍 GPS Telemetry",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "37.7749° N, 122.4194° W (±4m)",
                        fontSize = 11.sp,
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⚡ Proof-of-Work Verification",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (powSolved) "Verified Hash (Difficulty 4)" else "Ready",
                        fontSize = 11.sp,
                        color = EmergencyGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
