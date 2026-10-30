package org.meshline.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshline.app.BuildConfig
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.ui.components.*
import org.meshline.app.ui.theme.*

/**
 * This device, and this app's limits, in one place.
 *
 * The second half matters as much as the first. Everything MeshLine cannot do
 * is a thing someone might otherwise assume it does while standing in a
 * disaster, so the list of limits is in the app itself rather than only in a
 * store listing nobody reads twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StoreAndForwardManager.getInstance(context) }
    val peers by store.peersFlow.collectAsState()
    val groups by store.groupsFlow.collectAsState()

    val nodeId = remember { MeshCoreBridge.nodeIdHex() }
    val keyFingerprint = remember {
        MeshCoreBridge.publicKey()
            ?.take(8)
            ?.joinToString(" ") { "%02X".format(it) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ObsidianElevated,
        contentColor = TextPrimary,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 34.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(GlassSurfaceBorderStrong)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MeshMark(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "MESHLINE",
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 3.2.sp,
                        color = TextPrimary
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME}  ·  no account, no server",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Eyebrow("This device", NeonCyan)
                Spacer(Modifier.height(12.dp))
                ReadoutRow(
                    label = "Mesh node id",
                    value = nodeId ?: "unavailable",
                    valueColor = if (nodeId != null) NeonCyan else SignalRed,
                    mono = true
                )
                Spacer(Modifier.height(10.dp))
                HairlineDivider()
                Spacer(Modifier.height(10.dp))
                ReadoutRow(
                    label = "Identity key",
                    value = keyFingerprint ?: "unavailable",
                    valueColor = TextSecondary,
                    mono = true
                )
                Spacer(Modifier.height(10.dp))
                HairlineDivider()
                Spacer(Modifier.height(10.dp))
                ReadoutRow(
                    label = "Secure core",
                    value = if (MeshCoreBridge.isReady()) "Active" else "Unavailable",
                    valueColor = if (MeshCoreBridge.isReady()) EmergencyGreen else SignalRed
                )
                Spacer(Modifier.height(10.dp))
                HairlineDivider()
                Spacer(Modifier.height(10.dp))
                ReadoutRow(
                    label = "Devices in range",
                    value = "${peers.size}",
                    valueColor = if (peers.isEmpty()) TextMuted else EmergencyGreen
                )
                Spacer(Modifier.height(10.dp))
                HairlineDivider()
                Spacer(Modifier.height(10.dp))
                ReadoutRow(
                    label = "Private groups",
                    value = "${groups.size}",
                    valueColor = if (groups.isEmpty()) TextMuted else CipherViolet
                )
            }

            GlassCard(modifier = Modifier.fillMaxWidth(), accent = SafetyAmber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        MeshIcons.Warning,
                        contentDescription = null,
                        tint = SafetyAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "What MeshLine will not do",
                        style = MaterialTheme.typography.titleSmall,
                        color = SafetyAmber
                    )
                }
                Spacer(Modifier.height(12.dp))
                LIMITS.forEachIndexed { index, limit ->
                    if (index > 0) {
                        Spacer(Modifier.height(10.dp))
                        HairlineDivider()
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        limit.first,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        limit.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Text(
                "MeshLine has no analytics and contacts no server. Your position " +
                    "leaves this phone only inside an SOS or a pin you choose to send. " +
                    "Message history and keys are encrypted under a hardware-backed key " +
                    "and can be destroyed from the Radar tab.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

private val LIMITS = listOf(
    "Bluetooth LE only" to
        "Range is tens of metres, extended only by other phones running MeshLine. " +
            "This is not a satellite messenger, and there is no LoRa radio in this release.",
    "At most eight hops" to
        "In a chain of devices that is roughly seven phones between you and the far end.",
    "Delivery is best effort" to
        "Nothing guarantees an SOS reaches anyone. Your phone keeps trying while " +
            "anything is in range.",
    "A public SOS is not encrypted" to
        "Deliberately — it is what lets a stranger in range read it and come. Chats " +
            "and groups are end-to-end encrypted; an SOS is not.",
    "No offline map" to
        "Pins give distance and bearing rather than a basemap, because a fabricated " +
            "map in a disaster app invites someone to navigate by it."
)
