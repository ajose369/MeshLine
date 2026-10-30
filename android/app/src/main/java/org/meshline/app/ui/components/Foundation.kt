package org.meshline.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.meshline.app.ui.theme.CardSheen
import org.meshline.app.ui.theme.EmergencyGreen
import org.meshline.app.ui.theme.GlassSurfaceBorder
import org.meshline.app.ui.theme.GlassSurfaceBorderStrong
import org.meshline.app.ui.theme.GlassSurfaceCard
import org.meshline.app.ui.theme.GlassSurfaceHigh
import org.meshline.app.ui.theme.MeshMono
import org.meshline.app.ui.theme.NeonCyan
import org.meshline.app.ui.theme.ObsidianBackground
import org.meshline.app.ui.theme.SafetyAmber
import org.meshline.app.ui.theme.TextMuted
import org.meshline.app.ui.theme.TextPrimary
import org.meshline.app.ui.theme.TextSecondary
import org.meshline.app.ui.theme.accentFill
import org.meshline.app.ui.theme.accentWash

/**
 * The shared surface language: one card, one button, one field, one backdrop.
 *
 * Every screen is built from these rather than from ad-hoc `Surface`s, so a
 * change to the corner radius or the border treatment lands everywhere at once
 * and no screen quietly drifts.
 */

val CardShape = RoundedCornerShape(20.dp)
val SmallCardShape = RoundedCornerShape(14.dp)
val PillShape = RoundedCornerShape(50)

/* ---------------------------------------------------------------------------
 * Backdrop
 * ------------------------------------------------------------------------- */

/**
 * The animated ground the whole app sits on: two very slow accent glows over
 * near-black, plus a faint survey grid.
 *
 * The glow takes the colour of whichever screen is open, so switching tabs
 * shifts the entire room rather than just the content. It runs at a crawl —
 * a 30-second cycle — because this is an app people stare at while waiting for
 * help, and a lively background would be exhausting.
 */
@Composable
fun MeshBackdrop(accent: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Backdrop")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Drift"
    )
    val glow by animateColorAsState(accent, tween(700), label = "Glow")

    Canvas(modifier) {
        drawRect(ObsidianBackground)

        val w = size.width
        val h = size.height

        val top = Offset(w * (0.86f - 0.16f * drift), h * (0.02f + 0.06f * drift))
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.17f), Color.Transparent),
                center = top,
                radius = w * 1.05f
            ),
            size = Size(w, h)
        )

        val bottom = Offset(w * (0.08f + 0.14f * drift), h * (0.92f - 0.05f * drift))
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(NeonCyan.copy(alpha = 0.07f), Color.Transparent),
                center = bottom,
                radius = w * 0.95f
            ),
            size = Size(w, h)
        )

        // Survey grid. Deliberately at the threshold of visibility — it gives
        // the dark ground a sense of depth without ever competing with text.
        val step = 26.dp.toPx()
        val dot = 1.1f
        var y = step
        while (y < h) {
            var x = step
            while (x < w) {
                drawCircle(Color.White.copy(alpha = 0.028f), dot, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/* ---------------------------------------------------------------------------
 * Cards
 * ------------------------------------------------------------------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    selected: Boolean = false,
    shape: Shape = CardShape,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = when {
        selected && accent != null -> accent.copy(alpha = 0.85f)
        accent != null -> accent.copy(alpha = 0.34f)
        else -> GlassSurfaceBorder
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) GlassSurfaceHigh else GlassSurfaceCard)
            .then(if (accent != null) Modifier.background(accentWash(accent)) else Modifier)
            .background(CardSheen)
            .border(1.dp, border, shape)
            .then(
                when {
                    onClick != null && onLongClick != null ->
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            )
            .padding(contentPadding),
        content = content
    )
}

/* ---------------------------------------------------------------------------
 * Type helpers
 * ------------------------------------------------------------------------- */

/** A small tracked label with a leading rule, used above every section title. */
@Composable
fun Eyebrow(text: String, accent: Color = TextMuted, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(14.dp)
                .height(1.dp)
                .background(accent.copy(alpha = 0.7f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
    }
}

@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    accent: Color,
    subtitleColor: Color = TextSecondary,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(eyebrow, accent)
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

/** A dot-and-label chip. The dot is the state; the label explains it. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), PillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        } else {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontSize = 10.sp
        )
    }
}

/** A label/value row. Values that a user might read aloud are set in mono. */
@Composable
fun ReadoutRow(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    mono: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (mono) MeshMono else null,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GlassSurfaceBorder.copy(alpha = 0.6f))
    )
}

/* ---------------------------------------------------------------------------
 * Controls
 * ------------------------------------------------------------------------- */

/**
 * The primary action. Filled with an accent gradient when live, and visibly
 * inert when not — a disabled control that still looks tappable is a cruelty in
 * an app whose main button summons help.
 */
@Composable
fun MeshButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = NeonCyan,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 52.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.975f else 1f, label = "Press")
    val onAccent = if (accent.luminanceIsHigh()) Color(0xFF04070D) else Color.White

    Row(
        modifier = modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(SmallCardShape)
            .then(
                if (enabled) {
                    Modifier.background(accentFill(accent))
                } else {
                    Modifier
                        .background(GlassSurfaceHigh)
                        .border(1.dp, GlassSurfaceBorder, SmallCardShape)
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) onAccent else TextMuted,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) onAccent else TextMuted
        )
    }
}

/** A quieter action: outline only, for anything that is not the main path. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = TextSecondary,
    icon: ImageVector? = null,
    height: Dp = 46.dp
) {
    Row(
        modifier = modifier
            .height(height)
            .clip(SmallCardShape)
            .background(accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.4f), SmallCardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

/** The one text field shape used everywhere, so no screen invents its own. */
@Composable
fun MeshTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    accent: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    supporting: String? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 4,
    shape: Shape = SmallCardShape
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label?.let { { Text(it, color = TextMuted, fontSize = 12.sp) } },
        placeholder = { Text(placeholder, color = TextMuted.copy(alpha = 0.7f), fontSize = 13.sp) },
        supportingText = supporting?.let {
            { Text(it, color = TextMuted, fontSize = 10.sp) }
        },
        singleLine = singleLine,
        maxLines = maxLines,
        shape = shape,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent.copy(alpha = 0.8f),
            unfocusedBorderColor = GlassSurfaceBorder,
            cursorColor = accent,
            focusedContainerColor = GlassSurfaceCard,
            unfocusedContainerColor = GlassSurfaceCard,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = accent
        )
    )
}

/* ---------------------------------------------------------------------------
 * States
 * ------------------------------------------------------------------------- */

/**
 * What a screen shows when it has nothing to show.
 *
 * These are load-bearing in MeshLine: an empty radar or an empty pin list is
 * information about the mesh, not a gap to be filled with a placeholder.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    accent: Color = TextMuted,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(SmallCardShape)
                .background(accent.copy(alpha = 0.08f))
                .border(1.dp, accent.copy(alpha = 0.22f), SmallCardShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

/** A card carrying a result or a warning, with the accent stated on the edge. */
@Composable
fun NoticeCard(
    title: String,
    body: String,
    accent: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier.fillMaxWidth(), accent = accent, contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
                Spacer(Modifier.height(3.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Brand
 * ------------------------------------------------------------------------- */

/** The wordmark's companion glyph: a node with two propagating arcs. */
@Composable
fun MeshMark(modifier: Modifier = Modifier, color: Color = NeonCyan) {
    Canvas(modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val unit = size.minDimension / 2f
        drawCircle(color, unit * 0.24f, centre)
        drawCircle(color.copy(alpha = 0.55f), unit * 0.58f, centre, style = Stroke(width = unit * 0.11f))
        drawCircle(color.copy(alpha = 0.25f), unit * 0.92f, centre, style = Stroke(width = unit * 0.09f))
    }
}

/* ---------------------------------------------------------------------------
 * Internals
 * ------------------------------------------------------------------------- */

/** Whether an accent needs dark text on top of it. */
private fun Color.luminanceIsHigh(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) > 0.55f

/** Border used by controls that sit directly on the backdrop. */
val OutlineStroke = BorderStroke(1.dp, GlassSurfaceBorderStrong)

/**
 * Colour for a peer's trust state, kept in one place so the radar, the chat
 * header, and the channel list can never disagree about what green means.
 */
fun trustColor(hasSession: Boolean, verified: Boolean): Color = when {
    !hasSession -> TextMuted
    verified -> EmergencyGreen
    else -> SafetyAmber
}
