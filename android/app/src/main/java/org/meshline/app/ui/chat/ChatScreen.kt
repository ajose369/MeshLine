package org.meshline.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.GroupEntity
import org.meshline.app.db.MessageEntity
import org.meshline.app.db.MessageStatus
import org.meshline.app.db.PeerNodeEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.ui.components.*
import org.meshline.app.ui.theme.*

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

/**
 * What the user is currently talking to.
 *
 * Modelled explicitly rather than as a nullable peer, because the three cases
 * have genuinely different privacy properties and the UI has to be able to say
 * so plainly: the public channel is readable by anyone in range, a direct
 * conversation is end-to-end encrypted to one device, and a group is end-to-end
 * encrypted to a membership list.
 */
private sealed interface Conversation {
    data object Public : Conversation
    data class Direct(val peer: PeerNodeEntity) : Conversation
    data class Private(val group: GroupEntity) : Conversation
}

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val store = remember { StoreAndForwardManager.getInstance(context) }
    val messages by store.messagesFlow.collectAsState()
    val peers by store.peersFlow.collectAsState()
    val groups by store.groupsFlow.collectAsState()

    var conversation by remember { mutableStateOf<Conversation>(Conversation.Public) }
    var inputText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var verifyingPeer by remember { mutableStateOf<PeerNodeEntity?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var managingGroup by remember { mutableStateOf<GroupEntity?>(null) }

    // Keep the selection pointing at live data as peers come and go and groups
    // are re-keyed. A stale copy would show an out-of-date member list or a
    // session state that has since changed.
    LaunchedEffect(peers, groups) {
        when (val current = conversation) {
            is Conversation.Direct ->
                conversation = peers.firstOrNull { it.nodeId == current.peer.nodeId }
                    ?.let { Conversation.Direct(it) }
                    ?: Conversation.Public

            is Conversation.Private ->
                conversation = groups.firstOrNull { it.groupId == current.group.groupId }
                    ?.let { Conversation.Private(it) }
                    ?: Conversation.Public

            Conversation.Public -> Unit
        }
        managingGroup?.let { open ->
            managingGroup = groups.firstOrNull { it.groupId == open.groupId }
        }
    }

    val conversationId = when (val c = conversation) {
        Conversation.Public -> MessageEntity.BROADCAST_RECIPIENT
        is Conversation.Direct -> c.peer.nodeId
        is Conversation.Private -> c.group.groupId
    }
    val visibleMessages = remember(messages, conversationId) {
        messages.filter { it.conversationId == conversationId }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(visibleMessages.size, conversationId) {
        if (visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        ConversationHeader(conversation)

        Spacer(Modifier.height(14.dp))

        // Every channel here is real: the public broadcast, groups this device
        // actually holds a key for, and peers it has actually heard from.
        // Nothing is invented, because a channel that does not exist cannot
        // carry a message and showing one would be a lie about reachability.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ChannelChip(
                    label = "Public",
                    icon = MeshIcons.Broadcast,
                    accent = SafetyAmber,
                    selected = conversation is Conversation.Public,
                    onClick = { conversation = Conversation.Public; notice = null }
                )
            }

            items(groups, key = { it.groupId }) { group ->
                ChannelChip(
                    label = group.name,
                    icon = MeshIcons.Group,
                    accent = EmergencyGreen,
                    selected = (conversation as? Conversation.Private)?.group?.groupId == group.groupId,
                    dot = if (group.isAdmin) CipherViolet else null,
                    onClick = { conversation = Conversation.Private(group); notice = null },
                    onLongClick = { managingGroup = group }
                )
            }

            items(peers, key = { it.nodeId }) { peer ->
                ChannelChip(
                    label = peer.displayName,
                    icon = if (peer.hasSecureSession) MeshIcons.Lock else MeshIcons.Chat,
                    accent = trustColor(peer.hasSecureSession, peer.isVerified),
                    selected = (conversation as? Conversation.Direct)?.peer?.nodeId == peer.nodeId,
                    dot = trustColor(peer.hasSecureSession, peer.isVerified),
                    onClick = { conversation = Conversation.Direct(peer); notice = null }
                )
            }

            item {
                ChannelChip(
                    label = "New group",
                    icon = MeshIcons.Plus,
                    accent = NeonCyan,
                    selected = false,
                    onClick = { creatingGroup = true }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        (conversation as? Conversation.Direct)?.peer?.let { peer ->
            if (peer.hasSecureSession && !peer.isVerified) {
                UnverifiedBanner(onVerify = { verifyingPeer = peer })
                Spacer(Modifier.height(10.dp))
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (visibleMessages.isEmpty()) {
                EmptyState(
                    icon = when (conversation) {
                        is Conversation.Private -> MeshIcons.Group
                        is Conversation.Direct -> MeshIcons.Lock
                        Conversation.Public -> MeshIcons.Broadcast
                    },
                    title = when {
                        conversation is Conversation.Private -> "Nothing sent in this group yet"
                        peers.isEmpty() -> "No devices in range yet"
                        else -> "No messages here yet"
                    },
                    body = when {
                        conversation is Conversation.Private ->
                            "Only members can read what you send here."
                        peers.isEmpty() ->
                            "MeshLine keeps looking in the background."
                        else -> "Pick a channel above and say something."
                    },
                    accent = conversationAccent(conversation),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(visibleMessages, key = { it.msgId }) { message ->
                        MessageBubble(message, showSender = conversation is Conversation.Private)
                    }
                }
            }
        }

        notice?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MeshIcons.Warning,
                    contentDescription = null,
                    tint = SafetyAmber,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyAmber
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val directPeer = (conversation as? Conversation.Direct)?.peer
        if (directPeer != null && !directPeer.hasSecureSession) {
            MeshButton(
                text = "Set up encrypted link",
                onClick = {
                    notice = if (store.startHandshake(directPeer.nodeId)) {
                        "Setting up an encrypted link with ${directPeer.displayName}…"
                    } else {
                        "Could not start the secure link. Stay in range and try again."
                    }
                },
                accent = NeonCyan,
                icon = MeshIcons.Lock,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Composer(
                inputText = inputText,
                onTextChange = { inputText = it },
                accent = conversationAccent(conversation),
                canSend = MeshCoreBridge.isReady() &&
                    inputText.isNotBlank() &&
                    conversation !is Conversation.Public,
                onSend = {
                    when (val c = conversation) {
                        is Conversation.Direct -> {
                            notice = if (store.sendChat(c.peer.nodeId, inputText)) {
                                inputText = ""
                                null
                            } else {
                                "Message not sent: the encrypted link is not ready."
                            }
                        }

                        is Conversation.Private -> {
                            notice = if (store.sendGroupChat(c.group.groupId, inputText)) {
                                inputText = ""
                                null
                            } else {
                                "Message not sent: this device no longer holds the group key."
                            }
                        }

                        Conversation.Public -> Unit
                    }
                }
            )

            if (conversation is Conversation.Public) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pick a group or a device above to send something private. " +
                        "Use the SOS tab to reach everyone in range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    verifyingPeer?.let { peer ->
        VerifyPeerDialog(
            peer = peer,
            store = store,
            onDismiss = { verifyingPeer = null }
        )
    }

    if (creatingGroup) {
        CreateGroupDialog(
            onDismiss = { creatingGroup = false },
            onCreate = { name ->
                creatingGroup = false
                val id = store.createGroup(name)
                notice = if (id != null) {
                    "Group created. Add members from the peers you have an encrypted link with."
                } else {
                    "Could not create the group."
                }
            }
        )
    }

    managingGroup?.let { group ->
        ManageGroupDialog(
            group = group,
            peers = peers,
            store = store,
            onDismiss = { managingGroup = null },
            onNotice = { notice = it }
        )
    }
}

private fun conversationAccent(conversation: Conversation): Color = when (conversation) {
    Conversation.Public -> SafetyAmber
    is Conversation.Private -> EmergencyGreen
    is Conversation.Direct -> trustColor(
        conversation.peer.hasSecureSession,
        conversation.peer.isVerified
    )
}

@Composable
private fun ConversationHeader(conversation: Conversation) {
    val (title, subtitle, accent) = when (conversation) {
        Conversation.Public -> Triple(
            "Public channel",
            "Anyone in range can read this",
            SafetyAmber
        )

        is Conversation.Direct -> when {
            !conversation.peer.hasSecureSession -> Triple(
                conversation.peer.displayName,
                "Not yet encrypted — set up a secure link first",
                SafetyAmber
            )
            conversation.peer.isVerified -> Triple(
                conversation.peer.displayName,
                "Noise-XX end-to-end • verified in person",
                EmergencyGreen
            )
            else -> Triple(
                conversation.peer.displayName,
                "Noise-XX end-to-end • identity unverified",
                SafetyAmber
            )
        }

        is Conversation.Private -> Triple(
            conversation.group.name,
            "Private group • encrypted to ${conversation.group.memberCount} members",
            EmergencyGreen
        )
    }

    ScreenHeader(
        eyebrow = "Mesh chat",
        title = title,
        subtitle = subtitle,
        subtitleColor = accent,
        accent = accent,
        trailing = {
            val secure = conversation !is Conversation.Public &&
                (conversation as? Conversation.Direct)?.peer?.hasSecureSession != false
            StatusPill(
                text = if (secure) "encrypted" else "in the clear",
                color = if (secure) accent else SafetyAmber,
                icon = if (secure) MeshIcons.Lock else MeshIcons.Broadcast
            )
        }
    )
}

/**
 * Shown while a session is encrypted but nobody has checked who is on the other
 * end. Deliberately not dismissible-and-forgotten: an unverified session is
 * exactly the situation a man in the middle needs to stay in.
 */
@Composable
private fun UnverifiedBanner(onVerify: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SmallCardShape)
            .background(SafetyAmber.copy(alpha = 0.1f))
            .border(1.dp, SafetyAmber.copy(alpha = 0.45f), SmallCardShape)
            .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            MeshIcons.Shield,
            contentDescription = null,
            tint = SafetyAmber,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Encrypted, but this identity has not been checked in person.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "VERIFY",
            style = MaterialTheme.typography.labelSmall,
            color = SafetyAmber,
            modifier = Modifier
                .clip(PillShape)
                .clickable(onClick = onVerify)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Composer(
    inputText: String,
    onTextChange: (String) -> Unit,
    accent: Color,
    canSend: Boolean,
    onSend: () -> Unit
) {
    val sendScale by animateFloatAsState(if (canSend) 1f else 0.9f, tween(180), label = "Send")

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MeshTextField(
            value = inputText,
            onValueChange = { if (it.length <= 500) onTextChange(it) },
            placeholder = "Message",
            accent = accent,
            maxLines = 3,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .size(50.dp)
                .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                .clip(CircleShape)
                .background(
                    if (canSend) {
                        accentFill(accent)
                    } else {
                        Brush.linearGradient(listOf(GlassSurfaceHigh, GlassSurfaceCard))
                    }
                )
                .border(
                    1.dp,
                    if (canSend) Color.Transparent else GlassSurfaceBorder,
                    CircleShape
                )
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                MeshIcons.Send,
                contentDescription = "Send",
                tint = if (canSend) Color(0xFF04070D) else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelChip(
    label: String,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    dot: Color? = null,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(if (selected) accent.copy(alpha = 0.16f) else GlassSurfaceCard)
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.8f) else GlassSurfaceBorder,
                PillShape
            )
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(start = 11.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) accent else TextSecondary,
                modifier = Modifier.size(15.dp)
            )
            if (dot != null) {
                Box(
                    Modifier
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, showSender: Boolean) {
    val isMe = message.isOutgoing
    val shape = if (isMe) {
        RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 288.dp)
                .clip(shape)
                .background(
                    if (isMe) {
                        Brush.linearGradient(
                            listOf(NeonCyan.copy(alpha = 0.22f), NeonCyan.copy(alpha = 0.08f))
                        )
                    } else {
                        Brush.linearGradient(listOf(GlassSurfaceCard, GlassSurfaceCard))
                    }
                )
                .border(
                    1.dp,
                    if (isMe) NeonCyan.copy(alpha = 0.45f) else GlassSurfaceBorder,
                    shape
                )
                .padding(horizontal = 13.dp, vertical = 10.dp)
        ) {
            if (!isMe || showSender) {
                Text(
                    text = if (isMe) "you" else message.senderId.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MeshMono,
                    color = if (isMe) NeonCyan else CipherViolet
                )
                Spacer(Modifier.height(4.dp))
            }

            Text(
                text = message.payloadText,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTimestamp(message.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MeshMono,
                    fontSize = 9.sp,
                    color = TextMuted
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (message.encrypted) MeshIcons.Lock else MeshIcons.Broadcast,
                    contentDescription = null,
                    tint = if (message.encrypted) EmergencyGreen else SafetyAmber,
                    modifier = Modifier.size(10.dp)
                )
                if (isMe) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (message.status) {
                            MessageStatus.QUEUED -> "waiting for a peer"
                            MessageStatus.SENT -> "relayed"
                            MessageStatus.DELIVERED -> "delivered"
                            MessageStatus.FAILED -> "not sent"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp,
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
