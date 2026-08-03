package org.meshline.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshline.app.ui.theme.EmergencyGreen
import org.meshline.app.ui.theme.SafetyYellow

data class ChatMessage(
    val id: String,
    val sender: String,
    val text: String,
    val timestamp: String,
    val isMe: Boolean,
    val status: String
)

@Composable
fun ChatScreen() {
    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage("1", "Rescue Team A", "Is anyone near Sector 3 bridge?", "10:14 AM", false, "Relayed (2 hops)"),
                ChatMessage("2", "Me", "We have 4 people at shelter 2, water supplies low", "10:16 AM", true, "Delivered ACK"),
                ChatMessage("3", "Node #8fa2", "Water point verified at Community Center", "10:19 AM", false, "Relayed (1 hop)")
            )
        )
    }
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Offline Mesh Chat",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "E2E Noise-XX Ratchet Encrypted",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(msg)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Type offline message...") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        messages = messages + ChatMessage(
                            id = System.currentTimeMillis().toString(),
                            sender = "Me",
                            text = inputText,
                            timestamp = "Just now",
                            isMe = true,
                            status = "Sending to Mesh..."
                        )
                        inputText = ""
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Send")
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
                containerColor = if (message.isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.isMe) {
                    Text(
                        text = message.sender,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SafetyYellow
                    )
                }
                Text(
                    text = message.text,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = message.timestamp,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = message.status,
                        fontSize = 10.sp,
                        color = EmergencyGreen
                    )
                }
            }
        }
    }
}
