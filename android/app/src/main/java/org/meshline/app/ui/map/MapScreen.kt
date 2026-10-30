package org.meshline.app.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.meshline.app.ui.components.*
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
            .padding(horizontal = 18.dp)
    ) {
        ScreenHeader(
            eyebrow = "Signed resource pins",
            title = "What is around you",
            subtitle = fix?.let { "Bearings from ${it.formatted()}" }
                ?: "Waiting for a position fix",
            accent = SafetyAmber,
            subtitleColor = if (fix != null) TextSecondary else SafetyAmber,
            trailing = {
                AddButton(
                    expanded = showAddSheet,
                    enabled = MeshCoreBridge.isReady(),
                    onClick = { showAddSheet = !showAddSheet }
                )
            }
        )

        Spacer(Modifier.height(14.dp))

        AnimatedVisibility(
            visible = showAddSheet,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
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
        }

        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = SafetyAmber
            )
            Spacer(Modifier.height(10.dp))
        }

        if (sortedPins.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = MeshIcons.Pin,
                    title = "No resource pins yet",
                    body = "Pins arrive from other devices on the mesh, or you can add " +
                        "one for what you can see around you.",
                    accent = SafetyAmber
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(sortedPins, key = { it.pinId }) { pin ->
                    PinRow(pin, fix)
                }
            }
        }
    }
}

@Composable
private fun AddButton(expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(
                if (enabled) SafetyAmber.copy(alpha = 0.14f) else GlassSurfaceCard
            )
            .border(
                1.dp,
                if (enabled) SafetyAmber.copy(alpha = 0.55f) else GlassSurfaceBorder,
                CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            MeshIcons.Plus,
            contentDescription = if (expanded) "Close" else "Add a pin",
            tint = if (enabled) SafetyAmber else TextMuted,
            modifier = Modifier
                .size(18.dp)
                .rotate(if (expanded) 45f else 0f)
        )
    }
}

@Composable
private fun AddPinCard(enabled: Boolean, onAdd: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("WaterPoint") }

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = SafetyAmber) {
        Eyebrow("Pin what you can see here", SafetyAmber)
        Spacer(Modifier.height(12.dp))

        MeshTextField(
            value = label,
            onValueChange = { if (it.length <= 80) label = it },
            placeholder = "e.g. Water tanker at the school gate",
            accent = SafetyAmber,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PIN_TYPES.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { entry ->
                        TypeChip(
                            label = entry.second,
                            icon = MeshIcons.forPinType(entry.first),
                            accent = pinAccent(entry.first),
                            selected = type == entry.first,
                            onClick = { type = entry.first },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Keep the last row's chips the same width as a full row.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        MeshButton(
            text = if (enabled) "Sign and share pin" else "Position fix required",
            onClick = { onAdd(label.trim(), type) },
            accent = SafetyAmber,
            enabled = enabled && label.isNotBlank(),
            icon = MeshIcons.Shield,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TypeChip(
    label: String,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(SmallCardShape)
            .background(if (selected) accent.copy(alpha = 0.15f) else ObsidianElevated)
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.8f) else GlassSurfaceBorder,
                SmallCardShape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) accent else TextMuted,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            letterSpacing = 0.6.sp,
            color = if (selected) accent else TextMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun PinRow(pin: ResourcePinEntity, fix: MeshLocationProvider.Fix?) {
    val accent = pinAccent(pin.pinType)

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(SmallCardShape)
                    .background(accent.copy(alpha = 0.13f))
                    .border(1.dp, accent.copy(alpha = 0.3f), SmallCardShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    MeshIcons.forPinType(pin.pinType),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = PIN_TYPES.firstOrNull { it.first == pin.pinType }?.second ?: pin.pinType,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pin.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "%.4f, %.4f · signed by %s".format(
                        pin.latitude,
                        pin.longitude,
                        pin.creatorId.take(8)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MeshMono,
                    fontSize = 9.sp,
                    letterSpacing = 0.4.sp,
                    color = TextMuted
                )
            }

            if (fix != null) {
                val metres = haversineMetres(
                    fix.latitude,
                    fix.longitude,
                    pin.latitude.toDouble(),
                    pin.longitude.toDouble()
                )
                val degrees = bearingDegrees(
                    fix.latitude,
                    fix.longitude,
                    pin.latitude.toDouble(),
                    pin.longitude.toDouble()
                )
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BearingArrow(degrees, accent, Modifier.size(26.dp))
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = formatDistance(metres),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MeshMono,
                        color = TextPrimary
                    )
                    Text(
                        text = compassPoint(degrees),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

/**
 * A needle pointing at the pin.
 *
 * This is a true bearing from the user's coordinates, not a compass heading —
 * the phone's orientation is not consulted, so the needle points the way a map
 * would, with north up. The `N` tick is drawn for exactly that reason.
 */
@Composable
private fun BearingArrow(degrees: Double, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f

        drawCircle(
            color = accent.copy(alpha = 0.22f),
            radius = radius - 1f,
            center = centre,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
        )
        // North tick, so the needle is read against a fixed reference.
        drawCircle(
            color = TextMuted.copy(alpha = 0.6f),
            radius = 1.2f,
            center = Offset(centre.x, centre.y - radius + 3f)
        )

        val rad = Math.toRadians(degrees)
        val tip = Offset(
            centre.x + (radius * 0.66f) * sin(rad).toFloat(),
            centre.y - (radius * 0.66f) * cos(rad).toFloat()
        )
        val backAngle = 2.6
        val left = Offset(
            centre.x + (radius * 0.42f) * sin(rad + backAngle).toFloat(),
            centre.y - (radius * 0.42f) * cos(rad + backAngle).toFloat()
        )
        val right = Offset(
            centre.x + (radius * 0.42f) * sin(rad - backAngle).toFloat(),
            centre.y - (radius * 0.42f) * cos(rad - backAngle).toFloat()
        )

        drawPath(
            path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(left.x, left.y)
                lineTo(centre.x, centre.y)
                lineTo(right.x, right.y)
                close()
            },
            color = accent
        )
    }
}

private fun pinAccent(pinType: String): Color = when (pinType) {
    "WaterPoint" -> NeonCyan
    "Shelter" -> EmergencyGreen
    "MedicalStation" -> SignalRed
    "Hazard", "Roadblock" -> SafetyAmber
    else -> TextMuted
}

private val PIN_TYPES = listOf(
    "WaterPoint" to "Water",
    "Shelter" to "Shelter",
    "MedicalStation" to "Medical",
    "Hazard" to "Hazard",
    "Roadblock" to "Blocked"
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

private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(Math.toRadians(lat2))
    val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
        sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

private fun compassPoint(bearing: Double): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[((bearing + 22.5) / 45).toInt() % 8]
}
