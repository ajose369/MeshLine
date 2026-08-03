package org.meshline.app.ui.map

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshline.app.gis.OfflineTileManager
import org.meshline.app.ui.theme.EmergencyGreen
import org.meshline.app.ui.theme.SafetyYellow
import org.meshline.app.ui.theme.SignalRed

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

    var pins by remember {
        mutableStateOf(
            listOf(
                UiResourcePin("1", "Clean Water Filtration Pump", "Water Point", "0.4 km away", 18, 5),
                UiResourcePin("2", "Community High Shelter (Generator Available)", "Shelter", "1.2 km away", 42, 12),
                UiResourcePin("3", "First Aid Kit & Triage", "Medical Station", "1.8 km away", 6, 3),
                UiResourcePin("4", "Collapsed Bridge / Roadblock", "Hazard", "0.9 km away", 24, 8)
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Offline GIS Resource Map",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "OpenStreetMap Vector Tile Extract • Mesh Synchronized Pins",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Vector Map Libre Canvas View
        MapLibreView(
            tilePath = mapTilePath,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Opportunistic Mesh Pins (${pins.size})",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Button(
                onClick = {
                    pins = pins + UiResourcePin(
                        id = System.currentTimeMillis().toString(),
                        title = "Emergency Water Barrel",
                        type = "Water Point",
                        distance = "0.1 km away",
                        expiresHours = 24,
                        verifiedByCount = 1
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = SafetyYellow),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("+ Drop Pin", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pins) { pin ->
                ResourcePinCard(pin)
            }
        }
    }
}

@Composable
fun ResourcePinCard(pin: UiResourcePin) {
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
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${pin.type} • ${pin.distance} • Verified by ${pin.verifiedByCount} peers",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Surface(
                color = if (pin.expiresHours < 12) SignalRed else EmergencyGreen,
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
