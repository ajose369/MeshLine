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
import org.meshline.app.service.MeshRelayService
import org.meshline.app.ui.chat.ChatScreen
import org.meshline.app.ui.map.MapScreen
import org.meshline.app.ui.radar.RadarScreen
import org.meshline.app.ui.sos.SosScreen
import org.meshline.app.ui.theme.MeshLineTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

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
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Text("🚨", fontSize = MaterialTheme.typography.titleMedium.fontSize) },
                                label = { Text("SOS") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Text("💬", fontSize = MaterialTheme.typography.titleMedium.fontSize) },
                                label = { Text("Chat") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Text("🗺️", fontSize = MaterialTheme.typography.titleMedium.fontSize) },
                                label = { Text("Map") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Text("📡", fontSize = MaterialTheme.typography.titleMedium.fontSize) },
                                label = { Text("Radar") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
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
