package org.meshline.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn here rather than pulled from a dependency.
 *
 * `material-icons-extended` is roughly a 40 MB artifact for a handful of
 * glyphs, and none of its glyphs say "beacon", "mesh radar", or "route
 * blocked". These are 24×24 line drawings on a common stroke weight, tinted by
 * the caller through [androidx.compose.material3.Icon], so a single icon reads
 * correctly in every accent.
 */
object MeshIcons {

    /** Concentric arcs around a solid core — a transmitter, used for SOS. */
    val Beacon: ImageVector = icon("Beacon") {
        dot(12f, 12f, 2.3f)
        line { moveTo(8.5f, 15.5f); arcTo(5f, 5f, 0f, false, true, 8.5f, 8.5f) }
        line { moveTo(15.5f, 8.5f); arcTo(5f, 5f, 0f, false, true, 15.5f, 15.5f) }
        line { moveTo(6f, 18f); arcTo(8.5f, 8.5f, 0f, false, true, 6f, 6f) }
        line { moveTo(18f, 6f); arcTo(8.5f, 8.5f, 0f, false, true, 18f, 18f) }
    }

    /** A speech bubble with the tail on the left. */
    val Chat: ImageVector = icon("Chat") {
        line {
            moveTo(6f, 4.5f)
            lineTo(18f, 4.5f)
            arcTo(3f, 3f, 0f, false, true, 21f, 7.5f)
            lineTo(21f, 14f)
            arcTo(3f, 3f, 0f, false, true, 18f, 17f)
            lineTo(9.4f, 17f)
            lineTo(6f, 20.6f)
            lineTo(6f, 17f)
            arcTo(3f, 3f, 0f, false, true, 3f, 14f)
            lineTo(3f, 7.5f)
            arcTo(3f, 3f, 0f, false, true, 6f, 4.5f)
            close()
        }
    }

    /** A map pin: teardrop with a hollow centre. */
    val Pin: ImageVector = icon("Pin") {
        line {
            moveTo(12f, 21.2f)
            lineTo(7.5f, 13.2f)
            arcTo(6.2f, 6.2f, 0f, true, true, 16.5f, 13.2f)
            close()
        }
        line { circleAt(12f, 9f, 2.3f) }
    }

    /** A radar face: dish outline with the sweep arm at eleven o'clock. */
    val Radar: ImageVector = icon("Radar") {
        line { circleAt(12f, 12f, 9f) }
        line { circleAt(12f, 12f, 4.4f) }
        line { moveTo(12f, 12f); lineTo(18.4f, 5.6f) }
        dot(16.8f, 15.4f, 1.5f)
    }

    /** A shield — identity, verification, anything about trust. */
    val Shield: ImageVector = icon("Shield") {
        line {
            moveTo(12f, 2.6f)
            lineTo(19.5f, 5.7f)
            lineTo(19.5f, 11.4f)
            curveTo(19.5f, 16.4f, 16.3f, 19.8f, 12f, 21.4f)
            curveTo(7.7f, 19.8f, 4.5f, 16.4f, 4.5f, 11.4f)
            lineTo(4.5f, 5.7f)
            close()
        }
    }

    /** A padlock — an established encrypted session. */
    val Lock: ImageVector = icon("Lock") {
        line {
            moveTo(7.5f, 10.5f)
            lineTo(7.5f, 8f)
            arcTo(4.5f, 4.5f, 0f, false, true, 16.5f, 8f)
            lineTo(16.5f, 10.5f)
        }
        line {
            moveTo(6f, 10.5f)
            lineTo(18f, 10.5f)
            lineTo(18f, 20f)
            lineTo(6f, 20f)
            close()
        }
        dot(12f, 15.2f, 1.4f)
    }

    val Check: ImageVector = icon("Check") {
        line { moveTo(4.8f, 12.6f); lineTo(9.8f, 17.6f); lineTo(19.2f, 6.8f) }
    }

    /** Send: an upward arrow, used on the composer. */
    val Send: ImageVector = icon("Send") {
        line { moveTo(12f, 20f); lineTo(12f, 4.8f) }
        line { moveTo(5.8f, 11f); lineTo(12f, 4.8f); lineTo(18.2f, 11f) }
    }

    val Plus: ImageVector = icon("Plus") {
        line { moveTo(12f, 5f); lineTo(12f, 19f) }
        line { moveTo(5f, 12f); lineTo(19f, 12f) }
    }

    /** Two people — a private group. */
    val Group: ImageVector = icon("Group") {
        line { circleAt(9.2f, 8f, 3.1f) }
        line {
            moveTo(3.4f, 20f)
            curveTo(3.4f, 16.3f, 6f, 14.2f, 9.2f, 14.2f)
            curveTo(12.4f, 14.2f, 15f, 16.3f, 15f, 20f)
        }
        line {
            moveTo(16.2f, 14.6f)
            curveTo(18.9f, 15.4f, 20.6f, 17.2f, 20.6f, 20f)
        }
        line {
            moveTo(15.2f, 5.5f)
            curveTo(17.3f, 6.1f, 18.5f, 8.3f, 17.9f, 10.4f)
            curveTo(17.5f, 11.7f, 16.5f, 12.7f, 15.2f, 13.1f)
        }
    }

    /** A globe — the public, unencrypted channel. */
    val Broadcast: ImageVector = icon("Broadcast") {
        line { circleAt(12f, 12f, 8.8f) }
        line { moveTo(3.2f, 12f); lineTo(20.8f, 12f) }
        line {
            moveTo(12f, 3.2f)
            curveTo(14.6f, 5.9f, 15.9f, 8.9f, 15.9f, 12f)
            curveTo(15.9f, 15.1f, 14.6f, 18.1f, 12f, 20.8f)
            curveTo(9.4f, 18.1f, 8.1f, 15.1f, 8.1f, 12f)
            curveTo(8.1f, 8.9f, 9.4f, 5.9f, 12f, 3.2f)
            close()
        }
    }

    val Warning: ImageVector = icon("Warning") {
        line {
            moveTo(12f, 3.4f)
            lineTo(21.6f, 20f)
            lineTo(2.4f, 20f)
            close()
        }
        line { moveTo(12f, 9.6f); lineTo(12f, 14.4f) }
        dot(12f, 17.2f, 1.2f)
    }

    /** Panic wipe. */
    val Wipe: ImageVector = icon("Wipe") {
        line { moveTo(3.6f, 6.8f); lineTo(20.4f, 6.8f) }
        line { moveTo(9.4f, 6.8f); lineTo(9.4f, 4.2f); lineTo(14.6f, 4.2f); lineTo(14.6f, 6.8f) }
        line { moveTo(5.8f, 6.8f); lineTo(7f, 20.2f); lineTo(17f, 20.2f); lineTo(18.2f, 6.8f) }
        line { moveTo(10.3f, 10.4f); lineTo(10.7f, 16.8f) }
        line { moveTo(13.7f, 10.4f); lineTo(13.3f, 16.8f) }
    }

    /* ---- resource pin types ---- */

    val Water: ImageVector = icon("Water") {
        line {
            moveTo(12f, 3.2f)
            curveTo(15.6f, 7.6f, 18f, 11.2f, 18f, 14f)
            arcTo(6f, 6f, 0f, true, true, 6f, 14f)
            curveTo(6f, 11.2f, 8.4f, 7.6f, 12f, 3.2f)
            close()
        }
    }

    val Shelter: ImageVector = icon("Shelter") {
        line { moveTo(3.2f, 11.4f); lineTo(12f, 3.8f); lineTo(20.8f, 11.4f) }
        line { moveTo(5.6f, 9.6f); lineTo(5.6f, 20.2f); lineTo(18.4f, 20.2f); lineTo(18.4f, 9.6f) }
    }

    val Medical: ImageVector = icon("Medical") {
        line {
            moveTo(9.6f, 3.4f)
            lineTo(14.4f, 3.4f)
            lineTo(14.4f, 9.6f)
            lineTo(20.6f, 9.6f)
            lineTo(20.6f, 14.4f)
            lineTo(14.4f, 14.4f)
            lineTo(14.4f, 20.6f)
            lineTo(9.6f, 20.6f)
            lineTo(9.6f, 14.4f)
            lineTo(3.4f, 14.4f)
            lineTo(3.4f, 9.6f)
            lineTo(9.6f, 9.6f)
            close()
        }
    }

    val Blocked: ImageVector = icon("Blocked") {
        line {
            moveTo(2.8f, 8.6f)
            lineTo(21.2f, 8.6f)
            lineTo(21.2f, 15.4f)
            lineTo(2.8f, 15.4f)
            close()
        }
        line { moveTo(8.4f, 8.6f); lineTo(4.6f, 15.4f) }
        line { moveTo(14.2f, 8.6f); lineTo(10.4f, 15.4f) }
        line { moveTo(20f, 8.6f); lineTo(16.2f, 15.4f) }
    }

    /** Returns the icon that matches a `ResourcePinEntity.pinType`. */
    fun forPinType(pinType: String): ImageVector = when (pinType) {
        "WaterPoint" -> Water
        "Shelter" -> Shelter
        "MedicalStation" -> Medical
        "Hazard" -> Warning
        "Roadblock" -> Blocked
        else -> Pin
    }
}

/* ---------------------------------------------------------------------------
 * Builders
 * ------------------------------------------------------------------------- */

private const val STROKE = 1.7f

private fun icon(name: String, body: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(body).build()

/** A stroked sub-path. White because [androidx.compose.material3.Icon] tints it. */
private fun ImageVector.Builder.line(pathBuilder: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = pathBuilder
    )
}

/** A filled disc. */
private fun ImageVector.Builder.dot(cx: Float, cy: Float, r: Float) {
    path(fill = SolidColor(Color.White)) { circleAt(cx, cy, r) }
}

/** A full circle as two half-arcs, which is all `PathBuilder` can express. */
private fun PathBuilder.circleAt(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}
