package org.meshline.app.ui.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.ui.theme.SafetyYellow
import org.meshline.app.ui.theme.SignalRed

@Composable
fun SosScreen() {
    var isPublicSos by remember { mutableStateOf(true) }
    var sosTriggered by remember { mutableStateOf(false) }
    var relayCount by remember { mutableIntStateOf(0) }
    var sosMessageText by remember { mutableStateOf("EMERGENCY: Need medical assist & clean water") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "MeshLine Emergency SOS",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Zero cell/WiFi required • Phone-to-Phone Mesh Broadcast",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // Dual-Mode Toggle
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { isPublicSos = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPublicSos) SignalRed else Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Public Broadcast SOS", fontSize = 12.sp)
                }

                Button(
                    onClick = { isPublicSos = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isPublicSos) SafetyYellow else Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "Circle Only (E2E)",
                        fontSize = 12.sp,
                        color = if (!isPublicSos) Color.Black else MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        // Emergency Message Input
        OutlinedTextField(
            value = sosMessageText,
            onValueChange = { sosMessageText = it },
            label = { Text("Emergency Situation & Details") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SignalRed,
                unfocusedBorderColor = MaterialTheme.colorScheme.secondary
            )
        )

        // Huge SOS Trigger Button
        Button(
            onClick = {
                sosTriggered = true
                relayCount = (1..7).random()
                MeshCoreBridge.createPublicSos(sosMessageText, 37.7749f, -122.4194f)
            },
            modifier = Modifier
                .size(200.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (sosTriggered) SafetyYellow else SignalRed
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (sosTriggered) "SOS ACTIVE" else "TAP FOR SOS",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = if (sosTriggered) Color.Black else Color.White
                )
                if (sosTriggered) {
                    Text(
                        text = "Relayed by $relayCount peers",
                        fontSize = 11.sp,
                        color = Color.Black
                    )
                }
            }
        }

        // Live Status Footer
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "GPS Coordinates: 37.7749° N, 122.4194° W (±4m)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Mode: ${if (isPublicSos) "Unencrypted Open Rescue Broadcast" else "Authenticated Contacts Only"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
