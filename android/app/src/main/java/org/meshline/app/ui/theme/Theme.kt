package org.meshline.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* ---------------------------------------------------------------------------
 * Palette
 *
 * The app is used at night, outdoors, on a phone whose battery is a resource
 * being rationed. So the ground stays near-black — it costs the least on OLED
 * and keeps the accents legible in the dark — and colour is spent only where it
 * carries meaning: red is distress, green is verified, amber is "true but not
 * yet checked", cyan is the mesh itself.
 * ------------------------------------------------------------------------- */

/** Page ground. Near-black rather than pure black, so elevation is visible. */
val ObsidianBackground = Color(0xFF070A11)

/** One step above the ground — used for sheets and the nav bar. */
val ObsidianElevated = Color(0xFF0C111A)

/** Card fill. Paired with [CardSheen] for the glass gradient. */
val GlassSurfaceCard = Color(0xFF121826)
val GlassSurfaceHigh = Color(0xFF19212F)
val GlassSurfaceBorder = Color(0xFF232C3D)
val GlassSurfaceBorderStrong = Color(0xFF36415A)

/** Distress. Reserved for SOS and destructive actions — nothing decorative. */
val SignalRed = Color(0xFFFF4438)
val SignalRedDeep = Color(0xFF8C1710)
val SignalRedGlow = Color(0x4DFF4438)

/** The mesh itself: links, transport, anything about carrying a packet. */
val NeonCyan = Color(0xFF3FE0FF)
val NeonCyanDeep = Color(0xFF0B6E8C)

/** True, but unconfirmed — public channels and unverified identities. */
val SafetyAmber = Color(0xFFFFB443)
val SafetyAmberDeep = Color(0xFF8A5605)

/** Confirmed: a verified peer, a delivered message, a live relay. */
val EmergencyGreen = Color(0xFF3DDC97)
val EmergencyGreenDeep = Color(0xFF0F7554)

/** Cryptography — keys, epochs, safety numbers. */
val CipherViolet = Color(0xFF9B8CFF)

val TextPrimary = Color(0xFFEDF2F9)
val TextSecondary = Color(0xFFA8B3C7)
val TextMuted = Color(0xFF6E7A92)

/* ---------------------------------------------------------------------------
 * Brushes
 * ------------------------------------------------------------------------- */

/** The faint top-down sheen that makes a card read as glass rather than paint. */
val CardSheen = Brush.verticalGradient(
    listOf(Color(0x14FFFFFF), Color(0x00FFFFFF), Color(0x0A000000))
)

/** A filled control in an accent colour: bright at the top-left, deep at the far corner. */
fun accentFill(accent: Color): Brush = Brush.linearGradient(
    listOf(accent, accent.copy(alpha = 0.72f).compositeOver(Color(0xFF0A0E16)))
)

/** A wash used behind an accented card so the colour bleeds rather than blocks. */
fun accentWash(accent: Color): Brush = Brush.verticalGradient(
    listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.03f))
)

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f
    )
}

/* ---------------------------------------------------------------------------
 * Type
 *
 * Two families only. The sans carries language; the monospace carries anything
 * a user might have to read out loud or compare digit by digit — node ids,
 * safety numbers, coordinates, counts.
 * ------------------------------------------------------------------------- */

val MeshMono = FontFamily.Monospace

val MeshTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1).sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    ),
    /** The eyebrow style: small, wide-tracked, upper-case at the call site. */
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.6.sp
    )
)

private val DarkColorScheme = darkColorScheme(
    primary = SignalRed,
    onPrimary = Color.White,
    secondary = NeonCyan,
    onSecondary = Color(0xFF031017),
    tertiary = EmergencyGreen,
    onTertiary = Color(0xFF00190F),
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = GlassSurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = GlassSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = GlassSurfaceBorder,
    outlineVariant = GlassSurfaceBorderStrong,
    error = SignalRed,
    onError = Color.White,
    scrim = Color(0xCC03060B)
)

@Composable
fun MeshLineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MeshTypography,
        content = content
    )
}
