package org.meshline.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SignalRed = Color(0xFFFF3B30)
val SafetyYellow = Color(0xFFFFCC00)
val EmergencyGreen = Color(0xFF34C759)
val DarkSlateBackground = Color(0xFF121418)
val DarkSurfaceCard = Color(0xFF1E222A)
val TextLightPrimary = Color(0xFFF2F4F7)
val TextSecondary = Color(0xFF98A2B3)

private val DarkColorScheme = darkColorScheme(
    primary = SignalRed,
    secondary = SafetyYellow,
    tertiary = EmergencyGreen,
    background = DarkSlateBackground,
    surface = DarkSurfaceCard,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = TextLightPrimary,
    onSurface = TextLightPrimary
)

@Composable
fun MeshLineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
