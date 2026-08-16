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
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.MessageEntity
import org.meshline.app.db.MessageStatus
import org.meshline.app.db.PeerNodeEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.ui.theme.*

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val store = remember { StoreAndForwardManager.getInstance(context) }
    val messages by store.messagesFlow.collectAsState()
    val peers by store.peersFlow.collectAsState()

    // null means the public broadcast channel; otherwise a specific peer.
    var selectedPeer by remember { mutableStateOf<PeerNodeEntity?>(null) }
    var inputText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    // Drop the selection if the peer goes out of range.
    LaunchedEffect(peers) {
        selectedPeer?.let { current ->
            selectedPeer = peers.firstOrNull { it.nodeId == current.nodeId }
        }
    }

    val visibleMessages = remember(messages, selectedPeer) {
        val peer = selectedPeer
        if (peer == null) {
            messages.filter { it.recipientId == MessageEntity.BROADCAST_RECIPIENT }
        } else {
            messages.filter {
                it.senderId == peer.nodeId ||
                    (it.isOutgoing && it.recipientId == peer.nodeId)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(16.dp)
    ) {
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
                    text = selectedPeer?.let {
                        if (it.hasSecureSession) {
                            "End-to-end encrypted (Noise-XX)"
                        } else {
                            "Not yet encrypted — set up a secure link first"
                        }
                    } ?: "Public channel — anyone in range can read this",
                    fontSize = 11.sp,
                    color = when {
                        selectedPeer == null -> SafetyAmber
                        selectedPeer?.hasSecureSession == true -> EmergencyGreen
                        else -> SafetyAmber
                    }
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
                    Surface(
                        color = if (peers.isEmpty()) TextMuted else EmergencyGreen,
                        shape = CircleShape,
                        modifier = Modifier.size(6.dp)
                    ) {}
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (peers.isEmpty()) "No peers" else "${peers.size} in range",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (peers.isEmpty()) TextMuted else EmergencyGreen
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Channel selector: the public channel, plus one entry per real peer.
        // No invented groups — a channel that does not exist cannot carry a message.
        LazyColumn(
            modifier = Modifier.heightIn(max = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                ChannelChip(
                    label = "Public channel",
                    subtitle = "Unencrypted, readable by anyone nearby",
                    selected = selectedPeer == null,
                    accent = SafetyAmber,
                    onClick = { selectedPeer = null; notice = null }
                )
            }
            items(peers, key = { it.nodeId }) { peer ->
                ChannelChip(
                    label = peer.displayName,
                    subtitle = if (peer.hasSecureSession) {
                        "Encrypted • ${peer.transport}"
                    } else {
                        "Tap to set up encryption • ${peer.transport}"
                    },
                    selected = selectedPeer?.nodeId == peer.nodeId,
                    accent = if (peer.hasSecureSession) EmergencyGreen else NeonCyan,
                    onClick = { selectedPeer = peer; notice = null }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (visibleMessages.isEmpty()) {
                EmptyState(
                    text = if (peers.isEmpty()) {
                        "No devices in range yet.\nMeshLine keeps looking in the background."
                    } else {
                        "No messages here yet."
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleMessages, key = { it.msgId }) { message ->
                        MessageBubble(message)
                    }
                }
            }
        }

        notice?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                color = SafetyAmber,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        val peer = selectedPeer
        val needsHandshake = peer != null && !peer.hasSecureSession

        if (needsHandshake) {
            Button(
                onClick = {
                    notice = if (store.startHandshake(peer.nodeId)) {
                        "Setting up an encrypted link with ${peer.displayName}…"
                    } else {
                        "Could not start the secure link. Stay in range and try again."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("Set up encrypted link", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { if (it.length <= 500) inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message", color = TextMuted) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = GlassSurfaceBorder,
                        focusedContainerColor = GlassSurfaceCard,
                        unfocusedContainerColor = GlassSurfaceCard,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                val canSend = MeshCoreBridge.isReady() && inputText.isNotBlank() && peer != null

                FilledIconButton(
                    onClick = {
                        val target = peer ?: return@FilledIconButton
                        val sent = store.sendChat(target.nodeId, inputText)
                        notice = if (sent) {
                            inputText = ""
                            null
                        } else {
                            "Message not sent: the encrypted link is not ready."
                        }
                    },
                    enabled = canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = NeonCyan,
                        disabledContainerColor = GlassSurfaceBorder
                    )
                ) {
                    Text("→", fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            if (peer == null) {
                Text(
                    text = "Select a device above to send an encrypted message. " +
                        "Use the SOS tab to reach everyone in range.",
                    fontSize = 10.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelChip(
    label: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) accent.copy(alpha = 0.18f) else GlassSurfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) accent else GlassSurfaceBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) accent else TextPrimary
            )
            Text(subtitle, fontSize = 10.sp, color = TextMuted)
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    val isMe = message.isOutgoing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isMe) NeonCyan.copy(alpha = 0.16f) else GlassSurfaceCard,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (isMe) NeonCyan else GlassSurfaceBorder),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!isMe) {
                    Text(
                        text = message.senderId.take(10),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(message.payloadText, fontSize = 13.sp, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatTimestamp(message.timestampMillis),
                        fontSize = 9.sp,
                        color = TextMuted
                    )
                    Text(
                        if (message.encrypted) "encrypted" else "public",
                        fontSize = 9.sp,
                        color = if (message.encrypted) EmergencyGreen else SafetyAmber
                    )
                    if (isMe) {
                        Text(
                            when (message.status) {
                                MessageStatus.QUEUED -> "waiting for a peer"
                                MessageStatus.SENT -> "relayed"
                                MessageStatus.DELIVERED -> "delivered"
                                MessageStatus.FAILED -> "not sent"
                            },
                            fontSize = 9.sp,
                            color = when (message.status) {
                                MessageStatus.DELIVERED -> EmergencyGreen
                                MessageStatus.FAILED -> SignalRed
                                else -> TextMuted
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = TextMuted,
            lineHeight = 18.sp
        )
    }
}
