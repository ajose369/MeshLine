package org.meshline.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.permissions.MeshPermissions
import org.meshline.app.service.MeshRelayService
import org.meshline.app.ui.chat.ChatScreen
import org.meshline.app.ui.map.MapScreen
import org.meshline.app.ui.radar.RadarScreen
import org.meshline.app.ui.sos.SosScreen
import org.meshline.app.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise the secure core on the main thread before any screen can
        // ask it for a node id. It is idempotent and fast.
        MeshCoreBridge.initialise(this)

        setContent {
            MeshLineTheme {
                MeshLineApp(
                    onStartRelay = ::startRelayService
                )
            }
        }
    }

    /**
     * Starts the relay service. Called only once the blocking permissions are
     * granted; the service itself re-checks and refuses to run without them.
     */
    private fun startRelayService() {
        if (!MeshPermissions.hasRequired(this)) return
        if (!MeshCoreBridge.isReady()) return
        try {
            val intent = Intent(this, MeshRelayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Starting a foreground service is disallowed from the background on
            // newer releases; the user can retry from the UI.
            e.printStackTrace()
        }
    }
}

@Composable
private fun MeshLineApp(onStartRelay: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermissions by remember { mutableStateOf(MeshPermissions.hasRequired(context)) }
    var permissionRequested by remember { mutableStateOf(false) }

    // Permissions can be revoked in Settings while the app is backgrounded, so
    // re-check on every resume rather than trusting the value from launch.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermissions = MeshPermissions.hasRequired(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequested = true
        hasPermissions = MeshPermissions.hasRequired(context)
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) onStartRelay()
    }

    when {
        MeshCoreBridge.status == MeshCoreBridge.Status.LIBRARY_MISSING ||
            MeshCoreBridge.status == MeshCoreBridge.Status.INIT_FAILED ->
            BlockingMessage(
                title = "MeshLine cannot run",
                body = MeshCoreBridge.unavailableReason(),
                actionLabel = null,
                onAction = {}
            )

        !hasPermissions -> PermissionGate(
            permissionRequested = permissionRequested,
            onRequest = { launcher.launch(MeshPermissions.all()) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }
        )

        else -> MainScaffold()
    }
}

@Composable
private fun MainScaffold() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(containerColor = GlassSurfaceCard, contentColor = TextPrimary) {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(tab.icon, fontSize = 18.sp) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = tab.accent,
                            selectedTextColor = tab.accent,
                            indicatorColor = tab.accent.copy(alpha = 0.2f)
                        )
                    )
                }
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

private data class Tab(val icon: String, val label: String, val accent: Color)

private val TABS = listOf(
    Tab("🚨", "SOS", SignalRed),
    Tab("💬", "Chat", NeonCyan),
    Tab("📍", "Pins", SafetyAmber),
    Tab("📡", "Radar", EmergencyGreen)
)

/**
 * Explains what MeshLine needs and why before asking for it.
 *
 * The mesh genuinely cannot operate without these grants, so this is a hard
 * gate rather than a dismissible prompt — but the reasons are stated in plain
 * language, and the location rationale says exactly when a position is shared.
 */
@Composable
private fun PermissionGate(
    permissionRequested: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val missing = remember(permissionRequested) { MeshPermissions.missingRequired(context) }

    Surface(color = ObsidianBackground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                "MeshLine needs a few permissions",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = TextPrimary
            )
            Text(
                "MeshLine passes messages directly between nearby phones, with no " +
                    "cell network or internet. That only works with the permissions below.",
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 19.sp
            )

            missing.forEach { permission ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = GlassSurfaceCard),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GlassSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            MeshPermissions.label(permission),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            MeshPermissions.rationale(permission),
                            fontSize = 12.sp,
                            color = TextMuted,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SignalRed)
            ) {
                Text("Grant permissions", color = Color.White, fontWeight = FontWeight.Bold)
            }

            if (permissionRequested) {
                // After a denial Android may stop showing the system dialog, so
                // offer the only route left rather than looping on a dead button.
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Still blocked? Open app settings", color = NeonCyan, fontSize = 12.sp)
                }
            }

            Text(
                "MeshLine has no account, no server, and no analytics. Your location " +
                    "leaves this device only in an SOS or a pin you choose to send.",
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun BlockingMessage(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Surface(color = ObsidianBackground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SignalRed)
            Spacer(Modifier.height(12.dp))
            Text(body, fontSize = 13.sp, color = TextMuted, lineHeight = 19.sp)
            if (actionLabel != null) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = SignalRed)
                ) {
                    Text(actionLabel, color = Color.White)
                }
            }
        }
    }
}
