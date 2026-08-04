package org.meshline.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianBackground = Color(0xFF0B0E14)
val GlassSurfaceCard = Color(0xFF161B22)
val GlassSurfaceBorder = Color(0xFF2D3545)
val SignalRed = Color(0xFFFF3B30)
val SignalRedGlow = Color(0x66FF3B30)
val NeonCyan = Color(0xFF00E5FF)
val SafetyAmber = Color(0xFFFFB300)
val EmergencyGreen = Color(0xFF30D158)
val TextPrimary = Color(0xFFF0F4F8)
val TextMuted = Color(0xFF8B949E)

private val DarkColorScheme = darkColorScheme(
    primary = SignalRed,
    secondary = NeonCyan,
    tertiary = EmergencyGreen,
    background = ObsidianBackground,
    surface = GlassSurfaceCard,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun MeshLineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
