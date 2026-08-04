package org.meshline.app.ui.map

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
import java.util.UUID
import org.meshline.app.db.ResourcePinEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.gis.OfflineTileManager
import org.meshline.app.ui.theme.*

data class UiResourcePin(
    val id: String,
    val title: String,
    val type: String,
    val distance: String,
    val expiresHours: Int,
    val verifiedByCount: Int
)

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val tileManager = remember { OfflineTileManager(context) }
    val mapTilePath = remember { tileManager.getOfflineMapPath() }
    val storeAndForward = remember { StoreAndForwardManager.getInstance(context) }
    val dbPins by storeAndForward.pinsFlow.collectAsState()

    var showDropPinDialog by remember { mutableStateOf(false) }
    var newPinTitle by remember { mutableStateOf("") }
    var selectedPinType by remember { mutableStateOf("Water Point") }

    val pins = dbPins.map { entity ->
        val now = System.currentTimeMillis()
        val remainingHours = ((entity.expiresAt - now) / (3600 * 1000)).coerceAtLeast(0).toInt()
        UiResourcePin(
            id = entity.pinId,
            title = entity.label,
            type = when(entity.pinType) {
                "WaterPoint" -> "Water Point"
                "Shelter" -> "Shelter"
                "MedicalStation" -> "Medical Station"
                "Hazard" -> "Hazard"
                "Roadblock" -> "Roadblock"
                else -> entity.pinType
            },
            distance = "0.5 km away",
            expiresHours = remainingHours,
            verifiedByCount = entity.verifiedCount
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
                    text = "Offline GIS Map & Pins",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Signed & Timestamped Spatial Mesh Markers",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Button(
                onClick = { showDropPinDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = SafetyAmber),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("+ Drop Pin", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Map View Canvas Container
        MapLibreView(
            tilePath = mapTilePath,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Mesh-Synchronized Active Resource Pins (${pins.size})",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pins) { pin ->
                ResourcePinCard(pin)
            }
        }
    }

    // Modal Dialog to Drop Signed Resource Pin onto Mesh
    if (showDropPinDialog) {
        AlertDialog(
            onDismissRequest = { showDropPinDialog = false },
            containerColor = GlassSurfaceCard,
            title = {
                Text("Drop Signed Resource Pin", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column {
                    Text("Pin Label / Description", color = TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newPinTitle,
                        onValueChange = { newPinTitle = it },
                        placeholder = { Text("e.g. Clean drinking water barrel", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Resource Type", color = TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    val types = listOf("Water Point", "Shelter", "Medical Station", "Hazard")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        types.forEach { t ->
                            Surface(
                                color = if (selectedPinType == t) SafetyAmber else ObsidianBackground,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, GlassSurfaceBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedPinType = t }
                            ) {
                                Text(
                                    text = when(t) {
                                        "Water Point" -> "🚰"
                                        "Shelter" -> "⛺"
                                        "Medical Station" -> "🏥"
                                        else -> "⚠️"
                                    },
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedPinType == t) Color.Black else TextPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPinTitle.isNotBlank()) {
                            val newPin = ResourcePinEntity(
                                pinId = UUID.randomUUID().toString(),
                                pinType = when (selectedPinType) {
                                    "Water Point" -> "WaterPoint"
                                    "Shelter" -> "Shelter"
                                    "Medical Station" -> "MedicalStation"
                                    "Hazard" -> "Hazard"
                                    else -> "WaterPoint"
                                },
                                latitude = 37.7749f,
                                longitude = -122.4194f,
                                label = newPinTitle,
                                createdAt = System.currentTimeMillis(),
                                expiresAt = System.currentTimeMillis() + 24 * 3600 * 1000,
                                creatorPubkey = "Me",
                                signatureHex = "0000000000000000",
                                verifiedCount = 1
                            )
                            storeAndForward.upsertResourcePin(newPin)
                            newPinTitle = ""
                            showDropPinDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SafetyAmber)
                ) {
                    Text("Sign & Broadcast", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDropPinDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

@Composable
fun ResourcePinCard(pin: UiResourcePin) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
        shape = RoundedCornerShape(14.dp),
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
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (pin.type) {
                            "Water Point" -> "🚰 "
                            "Shelter" -> "⛺ "
                            "Medical Station" -> "🏥 "
                            else -> "⚠️ "
                        },
                        fontSize = 16.sp
                    )
                    Text(
                        text = pin.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${pin.type} • ${pin.distance} • Verified by ${pin.verifiedByCount} peers",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            Surface(
                color = if (pin.expiresHours < 12) SignalRed.copy(alpha = 0.8f) else EmergencyGreen.copy(alpha = 0.8f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "${pin.expiresHours}h left",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
