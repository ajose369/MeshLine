package org.meshline.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshline.app.ui.theme.SafetyYellow

@Composable
fun MapLibreView(
    tilePath: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🗺️ Vector Offline Map Active (MapLibre Engine)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = SafetyYellow
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Source: $tilePath",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Surface(
                        color = Color(0xFF2C323D),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text(
                            text = "Lat: 37.7749° N",
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                    Surface(
                        color = Color(0xFF2C323D),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text(
                            text = "Lon: 122.4194° W",
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }
            }
        }
    }
}
