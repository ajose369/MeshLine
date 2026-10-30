package org.meshline.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.db.StoreAndForwardManager
import org.meshline.app.permissions.MeshPermissions
import org.meshline.app.service.MeshRelayService
import org.meshline.app.ui.about.AboutSheet
import org.meshline.app.ui.chat.ChatScreen
import org.meshline.app.ui.components.*
import org.meshline.app.ui.map.MapScreen
import org.meshline.app.ui.radar.RadarScreen
import org.meshline.app.ui.sos.SosScreen
import org.meshline.app.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Drawn edge to edge: the backdrop runs under the status and navigation
        // bars, and every surface inside applies its own inset padding.
        enableEdgeToEdge()
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
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    hasPermissions = MeshPermissions.hasRequired(context)

                // Android can kill a backgrounded process without further
                // warning, so this is the last reliable point at which sessions
                // and messages can be written down.
                Lifecycle.Event.ON_PAUSE ->
                    if (MeshCoreBridge.isReady()) {
                        StoreAndForwardManager.getInstance(context).persistNow()
                    }

                else -> Unit
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
                body = MeshCoreBridge.unavailableReason()
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

/* ---------------------------------------------------------------------------
 * Shell
 * ------------------------------------------------------------------------- */

@Composable
private fun MainScaffold() {
    val context = LocalContext.current
    val store = remember { StoreAndForwardManager.getInstance(context) }
    val peers by store.peersFlow.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    val tab = TABS[selectedTab]

    Box(Modifier.fillMaxSize()) {
        MeshBackdrop(tab.accent, Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
        ) {
            BrandBar(peerCount = peers.size, onOpenAbout = { showAbout = true })

            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        (
                            fadeIn(tween(240)) +
                                slideInVertically(tween(280)) { full -> full / 22 }
                            ) togetherWith fadeOut(tween(140))
                    },
                    label = "Tab"
                ) { index ->
                    when (index) {
                        0 -> SosScreen()
                        1 -> ChatScreen()
                        2 -> MapScreen()
                        else -> RadarScreen()
                    }
                }
            }

            MeshNavBar(selected = selectedTab, onSelect = { selectedTab = it })
        }
    }

    if (showAbout) {
        AboutSheet(onDismiss = { showAbout = false })
    }
}

/**
 * The persistent header. It carries the one fact that matters on every screen —
 * whether anything is out there — so the user never has to switch to the radar
 * to find out.
 */
@Composable
private fun BrandBar(peerCount: Int, onOpenAbout: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenAbout)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MeshMark(Modifier.size(20.dp), color = NeonCyan)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "MESHLINE",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 3.2.sp,
                color = TextPrimary
            )
        }
        Spacer(Modifier.weight(1f))
        StatusPill(
            text = when {
                !MeshCoreBridge.isReady() -> "core offline"
                peerCount == 0 -> "searching"
                peerCount == 1 -> "1 device"
                else -> "$peerCount devices"
            },
            color = when {
                !MeshCoreBridge.isReady() -> SignalRed
                peerCount == 0 -> TextMuted
                else -> EmergencyGreen
            }
        )
    }
}

@Composable
private fun MeshNavBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(ObsidianElevated.copy(alpha = 0.94f))
            .border(1.dp, GlassSurfaceBorder, RoundedCornerShape(24.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TABS.forEachIndexed { index, tab ->
            NavItem(
                tab = tab,
                selected = index == selected,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NavItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint by animateColorAsState(
        if (selected) tab.accent else TextMuted,
        tween(220),
        label = "NavTint"
    )
    val fill by animateColorAsState(
        if (selected) tab.accent.copy(alpha = 0.14f) else Color.Transparent,
        tween(220),
        label = "NavFill"
    )
    val lift by animateFloatAsState(if (selected) 1f else 0f, tween(240), label = "NavLift")
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer { translationY = -1.5f * lift }
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            letterSpacing = 1.1.sp,
            color = tint
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .size(width = 14.dp, height = 2.dp)
                .clip(CircleShape)
                .background(tab.accent.copy(alpha = 0.9f * lift))
        )
    }
}

private data class Tab(val icon: ImageVector, val label: String, val accent: Color)

private val TABS = listOf(
    Tab(MeshIcons.Beacon, "SOS", SignalRed),
    Tab(MeshIcons.Chat, "CHAT", NeonCyan),
    Tab(MeshIcons.Pin, "PINS", SafetyAmber),
    Tab(MeshIcons.Radar, "RADAR", EmergencyGreen)
)

/* ---------------------------------------------------------------------------
 * Gates
 * ------------------------------------------------------------------------- */

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

    Box(Modifier.fillMaxSize()) {
        MeshBackdrop(NeonCyan, Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MeshMark(Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "MESHLINE",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 3.2.sp,
                    color = TextPrimary
                )
            }

            Spacer(Modifier.height(10.dp))

            Eyebrow("Before the mesh can carry anything", NeonCyan)
            Text(
                "A few permissions,\nand no accounts.",
                style = MaterialTheme.typography.displayMedium,
                color = TextPrimary
            )
            Text(
                "MeshLine passes messages directly between nearby phones, with no " +
                    "cell network or internet. That only works with the permissions below.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(Modifier.height(4.dp))

            missing.forEach { permission ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(15.dp)
                ) {
                    Text(
                        MeshPermissions.label(permission),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        MeshPermissions.rationale(permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            MeshButton(
                text = "Grant permissions",
                onClick = onRequest,
                accent = NeonCyan,
                icon = MeshIcons.Shield,
                modifier = Modifier.fillMaxWidth()
            )

            if (permissionRequested) {
                // After a denial Android may stop showing the system dialog, so
                // offer the only route left rather than looping on a dead button.
                GhostButton(
                    text = "Still blocked? Open app settings",
                    onClick = onOpenSettings,
                    accent = TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                "MeshLine has no account, no server, and no analytics. Your location " +
                    "leaves this device only in an SOS or a pin you choose to send.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun BlockingMessage(title: String, body: String) {
    Box(Modifier.fillMaxSize()) {
        MeshBackdrop(SignalRed, Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(SignalRed.copy(alpha = 0.12f))
                    .border(1.dp, SignalRed.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    MeshIcons.Warning,
                    contentDescription = null,
                    tint = SignalRed,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "MeshLine will not fall back to sending anything unencrypted.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}
