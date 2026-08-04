package org.meshline.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.meshline.app.db.MessageEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.ui.theme.*

data class ChatMessage(
    val id: String,
    val sender: String,
    val text: String,
    val timestamp: String,
    val isMe: Boolean,
    val status: String,
    val hopCount: Int
)

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(millis))
}

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val storeAndForward = remember { StoreAndForwardManager.getInstance(context) }
    val dbMessages by storeAndForward.messagesFlow.collectAsState()
    var selectedChannel by remember { mutableIntStateOf(0) } // 0 = Broadcast, 1 = Field Rescue, 2 = Family Circle
    
    val messages = dbMessages.map { entity ->
        ChatMessage(
            id = entity.msgId,
            sender = entity.senderId,
            text = entity.payloadText,
            timestamp = formatTimestamp(entity.timestamp),
            isMe = entity.senderId == "Me",
            status = entity.status,
            hopCount = (8 - entity.ttl).coerceAtLeast(1)
        )
    }
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(16.dp)
    ) {
        // Header & Encryption Security Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Offline Mesh Messaging",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Serverless Noise-XX Double Ratchet E2EE",
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
                    Surface(color = EmergencyGreen, shape = CircleShape, modifier = Modifier.size(6.dp)) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "3 Hops Active",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EmergencyGreen
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Channel Selector Pill Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val channels = listOf("🌐 Open Mesh", "🚑 Field Rescue", "🏠 Family Circle")
            channels.forEachIndexed { index, name ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selectedChannel == index) NeonCyan else GlassSurfaceCard,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedChannel = index }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedChannel == index) Color.Black else TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Message List Thread
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(msg)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Message Input Dock
        Card(
            colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, GlassSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Send peer-to-peer message...", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            val newMsg = MessageEntity(
                                msgId = UUID.randomUUID().toString(),
                                senderId = "Me",
                                recipientId = "All",
                                payloadText = inputText,
                                ttl = 8,
                                timestamp = System.currentTimeMillis(),
                                packetType = "Chat",
                                status = "QUEUED"
                            )
                            storeAndForward.queueMessage(newMsg)
                            inputText = ""
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    modifier = Modifier.size(44.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("➔", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isMe) Alignment.End else Alignment.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isMe) Color(0xFF003847) else GlassSurfaceCard
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isMe) 16.dp else 4.dp,
                bottomEnd = if (message.isMe) 4.dp else 16.dp
            ),
            border = BorderStroke(1.dp, if (message.isMe) NeonCyan.copy(alpha = 0.4f) else GlassSurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.isMe) {
                    Text(
                        text = message.sender,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SafetyAmber
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = message.text,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.timestamp,
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        color = Color(0xFF1E242F),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "🔗 ${message.status}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmergencyGreen,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
