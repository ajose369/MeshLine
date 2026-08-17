package org.meshline.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshline.app.db.GroupEntity
import org.meshline.app.db.PeerNodeEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.ui.components.GlassCard
import org.meshline.app.ui.theme.*

/**
 * Shows the safety number for a peer so it can be compared out of band.
 *
 * This is the only defence against an attacker who was present at the very first
 * handshake: at that moment neither side has any prior key to check against, so
 * a man in the middle is authenticated to both and invisible to both. Reading
 * these digits aloud, or holding the two screens side by side, is what a radio
 * cannot do for you.
 *
 * The wording avoids telling the user they are "secure" once they tap the
 * button. What they are confirming is narrow and specific — that the digits
 * match — and the interface should not inflate that into a broader promise.
 */
@Composable
fun VerifyPeerDialog(
    peer: PeerNodeEntity,
    store: StoreAndForwardManager,
    onDismiss: () -> Unit
) {
    // Derivation is an iterated hash, so do it once per open rather than on
    // every recomposition.
    val safetyNumber = remember(peer.nodeId) { store.safetyNumber(peer.nodeId) }
    var verified by remember(peer.nodeId) { mutableStateOf(peer.isVerified) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianElevated,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(22.dp),
        title = { Text("Verify ${peer.displayName}", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (safetyNumber == null) {
                    Text(
                        "There is no encrypted session with this device yet, so there is " +
                            "nothing to verify. Set up the secure link first.",
                        fontSize = 12.sp,
                        color = SafetyAmber
                    )
                } else {
                    Text(
                        "Compare these numbers with the other person, in person or over a " +
                            "channel you already trust. Both devices must show exactly the same digits.",
                        fontSize = 11.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        accent = CipherViolet,
                        contentPadding = PaddingValues(14.dp)
                    ) {
                        Text(
                            text = safetyNumber,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.5.sp,
                            color = TextPrimary,
                            textAlign = TextAlign.Center,
                            lineHeight = 28.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (verified) {
                            "Marked as verified. If this person reinstalls MeshLine or " +
                                "changes device, the number changes and you should check again."
                        } else {
                            "If the numbers do not match, someone may be relaying between " +
                                "you. Do not send anything sensitive until they do."
                        },
                        fontSize = 10.sp,
                        color = if (verified) EmergencyGreen else SafetyAmber,
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = {
            if (safetyNumber != null) {
                TextButton(
                    onClick = {
                        val next = !verified
                        if (store.setPeerVerified(peer.nodeId, next)) {
                            verified = next
                        }
                    }
                ) {
                    Text(
                        if (verified) "Undo verification" else "The numbers match",
                        color = if (verified) SafetyAmber else EmergencyGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextMuted)
            }
        }
    )
}

/** Names a new private group. The key is generated by the core, never here. */
@Composable
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianElevated,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(22.dp),
        title = { Text("New private group", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Only members can read what is sent here. You can add anyone you " +
                        "already have an encrypted link with.",
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    placeholder = { Text("Group name", color = TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = GlassSurfaceBorder,
                        focusedContainerColor = GlassSurfaceCard,
                        unfocusedContainerColor = GlassSurfaceCard,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The name is stored on the members' devices only. It is never sent " +
                        "in the clear over the mesh.",
                    fontSize = 10.sp,
                    color = TextMuted,
                    lineHeight = 14.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("Create", color = NeonCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        }
    )
}

/**
 * Membership management for a group.
 *
 * Removal and rekeying report honestly: rotating a key only excludes someone
 * once every remaining member has received the new one, and a member who is out
 * of radio range at that moment has not. Reporting "done" regardless would leave
 * the admin believing a group is secured when part of it has gone dark.
 */
@Composable
fun ManageGroupDialog(
    group: GroupEntity,
    peers: List<PeerNodeEntity>,
    store: StoreAndForwardManager,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit
) {
    val invitable = remember(peers, group) {
        peers.filter { it.hasSecureSession && it.nodeId !in group.members }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianElevated,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(22.dp),
        title = {
            Column {
                Text(group.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${group.memberCount} members • key version ${group.epoch}",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp)) {
                if (!group.isAdmin) {
                    Text(
                        "Only the person who created this group can add or remove members.",
                        fontSize = 11.sp,
                        color = SafetyAmber,
                        lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Text("Members", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(group.members, key = { it }) { member ->
                        MemberRow(
                            label = member.take(12),
                            trailing = when {
                                member == group.adminId -> "admin"
                                else -> null
                            },
                            actionLabel = if (group.isAdmin && member != group.adminId) {
                                "Remove"
                            } else {
                                null
                            },
                            onAction = {
                                val report = store.removeFromGroup(group.groupId, member)
                                onNotice(
                                    when {
                                        report == null -> "Could not remove that member."
                                        report.isComplete ->
                                            "Removed. The group key was rotated and every " +
                                                "remaining member has the new one."
                                        else ->
                                            "Removed and the key was rotated, but " +
                                                "${report.unreachable.size} member(s) were out " +
                                                "of range and still need the new key. Rekey " +
                                                "again when they are back."
                                    }
                                )
                            }
                        )
                    }
                }

                if (group.isAdmin) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Add a member",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))

                    if (invitable.isEmpty()) {
                        Text(
                            "Nobody to add yet. You can only add devices you already have " +
                                "an encrypted link with, so set that up first.",
                            fontSize = 10.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 110.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(invitable, key = { it.nodeId }) { peer ->
                                MemberRow(
                                    label = peer.displayName,
                                    trailing = if (peer.isVerified) "verified" else "unverified",
                                    actionLabel = "Add",
                                    onAction = {
                                        onNotice(
                                            if (store.inviteToGroup(group.groupId, peer.nodeId)) {
                                                "Invite sent to ${peer.displayName}."
                                            } else {
                                                "Could not invite ${peer.displayName}."
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            val report = store.rekeyGroup(group.groupId)
                            onNotice(
                                when {
                                    report == null -> "Could not rotate the group key."
                                    report.isComplete ->
                                        "New group key sent to every member."
                                    else ->
                                        "New key sent, but ${report.unreachable.size} " +
                                            "member(s) were out of range and cannot read the " +
                                            "group until they get it."
                                }
                            )
                        }
                    ) {
                        Text(
                            "Rotate group key",
                            color = SafetyAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "Use this if a member's phone may have been taken. Everything sent " +
                            "afterwards will be unreadable to whoever holds the old key.",
                        fontSize = 10.sp,
                        color = TextMuted,
                        lineHeight = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    store.leaveGroup(group.groupId)
                    onNotice("You left ${group.name}. Its key was deleted from this device.")
                    onDismiss()
                }
            ) {
                Text("Leave group", color = SignalRed, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) }
        }
    )
}

@Composable
private fun MemberRow(
    label: String,
    trailing: String?,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = TextPrimary)
            trailing?.let {
                Text(
                    it,
                    fontSize = 9.sp,
                    color = if (it == "verified") EmergencyGreen else TextMuted
                )
            }
        }
        actionLabel?.let {
            TextButton(onClick = onAction) {
                Text(
                    it,
                    fontSize = 10.sp,
                    color = if (it == "Remove") SignalRed else NeonCyan
                )
            }
        }
    }
}
