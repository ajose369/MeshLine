package org.meshline.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.meshline.app.service.MeshRelayService
import org.meshline.app.ui.chat.ChatScreen
import org.meshline.app.ui.map.MapScreen
import org.meshline.app.ui.radar.RadarScreen
import org.meshline.app.ui.sos.SosScreen
import org.meshline.app.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start background Mesh Relay Service
        val serviceIntent = Intent(this, MeshRelayService::class.java)
        try {
            startService(serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MeshLineTheme {
                var selectedTab by remember { mutableIntStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            containerColor = GlassSurfaceCard,
                            contentColor = TextPrimary
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Text("🚨", fontSize = 18.sp) },
                                label = { Text("SOS", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = SignalRed,
                                    selectedTextColor = SignalRed,
                                    indicatorColor = SignalRedGlow
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Text("💬", fontSize = 18.sp) },
                                label = { Text("Chat", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NeonCyan,
                                    selectedTextColor = NeonCyan,
                                    indicatorColor = Color(0xFF003847)
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Text("🗺️", fontSize = 18.sp) },
                                label = { Text("Map", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = SafetyAmber,
                                    selectedTextColor = SafetyAmber,
                                    indicatorColor = Color(0xFF332600)
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Text("📡", fontSize = 18.sp) },
                                label = { Text("Radar", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = EmergencyGreen,
                                    selectedTextColor = EmergencyGreen,
                                    indicatorColor = Color(0xFF0A3314)
                                )
                            )
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = ObsidianBackground
                    ) {
                        when (selectedTab) {
                            0 -> SosScreen()
                            1 -> ChatScreen()
                            2 -> MapScreen()
                            3 -> RadarScreen()
                        }
                    }
                }
            }
        }
    }
}
