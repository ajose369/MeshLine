package org.meshline.app.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.ResourcePinEntity
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.location.MeshLocationProvider
import org.meshline.app.ui.theme.*

/**
 * Resource pins, sorted by distance from the user.
 *
 * This deliberately does not draw a slippy map. Rendering a map needs vector
 * tiles that this build does not ship, and a blank or fabricated basemap in a
 * disaster app invites someone to navigate by it. A bearing and distance to each
 * verified pin is honest and works with no tiles at all.
 */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val store = remember { StoreAndForwardManager.getInstance(context) }
    val locationProvider = remember { MeshLocationProvider(context) }
    val pins by store.pinsFlow.collectAsState()

    var fix by remember { mutableStateOf<MeshLocationProvider.Fix?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Active position updates, for the same reason as the SOS screen: a pin
    // placed at a stale or absent fix points rescuers at the wrong place.
    LaunchedEffect(Unit) {
        locationProvider.positionUpdates().collect { fix = it }
    }

    LaunchedEffect(Unit) {
        while (true) {
            store.syncResourcePins()
            delay(5_000)
        }
    }

    val sortedPins = remember(pins, fix) {
        val current = fix
        if (current == null) {
            pins
        } else {
            pins.sortedBy {
                haversineMetres(current.latitude, current.longitude, it.latitude.toDouble(), it.longitude.toDouble())
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
                    "Resource Pins",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = fix?.let { "From ${it.formatted()}" }
                        ?: "Waiting for a position fix",
                    fontSize = 11.sp,
                    color = if (fix != null) TextMuted else SafetyAmber
                )
            }
            Button(
                onClick = { showAddSheet = !showAddSheet },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SafetyAmber),
                enabled = MeshCoreBridge.isReady()
            ) {
                Text("Add", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (showAddSheet) {
            AddPinCard(
                enabled = fix != null,
                onAdd = { label, type ->
                    val current = fix
                    notice = if (current == null) {
                        "A position fix is needed before placing a pin."
                    } else if (
                        store.createAndBroadcastPin(
                            label = label,
                            latitude = current.latitude.toFloat(),
                            longitude = current.longitude.toFloat(),
                            type = type,
                            expiresInSecs = 24 * 60 * 60
                        )
                    ) {
                        showAddSheet = false
                        "Pin signed and queued for the mesh."
                    } else {
                        "Pin could not be created."
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        notice?.let {
            Text(it, fontSize = 11.sp, color = SafetyAmber)
            Spacer(Modifier.height(8.dp))
        }

        if (sortedPins.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "No resource pins yet.\n\nPins arrive from other devices on " +
                        "the mesh, or you can add one for what you can see around you.",
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 18.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedPins, key = { it.pinId }) { pin ->
                    PinRow(pin, fix)
                }
            }
        }
    }
}

@Composable
private fun AddPinCard(enabled: Boolean, onAdd: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("WaterPoint") }

    Card(
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GlassSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Pin what you can see at your current position",
                fontSize = 11.sp,
                color = TextMuted
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { if (it.length <= 80) label = it },
                placeholder = { Text("e.g. Water tanker at the school gate", color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SafetyAmber,
                    unfocusedBorderColor = GlassSurfaceBorder,
                    focusedContainerColor = ObsidianBackground,
                    unfocusedContainerColor = ObsidianBackground,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                items(PIN_TYPES) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { type = entry.first }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = type == entry.first,
                            onClick = { type = entry.first },
                            colors = RadioButtonDefaults.colors(selectedColor = SafetyAmber)
                        )
                        Text(entry.second, fontSize = 12.sp, color = TextPrimary)
                    }
                }
            }

            Button(
                onClick = { onAdd(label.trim(), type) },
                enabled = enabled && label.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SafetyAmber)
            ) {
                Text(
                    if (enabled) "Sign and share pin" else "Position fix required",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PinRow(pin: ResourcePinEntity, fix: MeshLocationProvider.Fix?) {
    val accent = when (pin.pinType) {
        "WaterPoint" -> NeonCyan
        "Shelter" -> EmergencyGreen
        "MedicalStation" -> SignalRed
        "Hazard", "Roadblock" -> SafetyAmber
        else -> TextMuted
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GlassSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = PIN_TYPES.firstOrNull { it.first == pin.pinType }?.second ?: pin.pinType,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                if (fix != null) {
                    val metres = haversineMetres(
                        fix.latitude,
                        fix.longitude,
                        pin.latitude.toDouble(),
                        pin.longitude.toDouble()
                    )
                    Text(
                        text = "${formatDistance(metres)} ${bearingLabel(
                            fix.latitude,
                            fix.longitude,
                            pin.latitude.toDouble(),
                            pin.longitude.toDouble()
                        )}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(pin.label, fontSize = 13.sp, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "%.4f, %.4f • from ${pin.creatorId.take(8)}".format(
                    pin.latitude,
                    pin.longitude
                ),
                fontSize = 10.sp,
                color = TextMuted
            )
        }
    }
}

private val PIN_TYPES = listOf(
    "WaterPoint" to "Drinking water",
    "Shelter" to "Shelter",
    "MedicalStation" to "Medical help",
    "Hazard" to "Hazard",
    "Roadblock" to "Route blocked"
)

private const val EARTH_RADIUS_M = 6_371_000.0

private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
}

private fun formatDistance(metres: Double): String = when {
    metres < 1000 -> "${metres.roundToInt()} m"
    else -> "%.1f km".format(metres / 1000)
}

private fun bearingLabel(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(Math.toRadians(lat2))
    val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
        sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
    val bearing = (Math.toDegrees(atan2(y, x)) + 360) % 360
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[((bearing + 22.5) / 45).toInt() % 8]
}
