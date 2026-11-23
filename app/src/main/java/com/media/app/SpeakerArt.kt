package com.media.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
//  THE SPEAKER (the artwork button's third state)
//
//  A whole studio monitor stands in the cover's place: a graphite cabinet,
//  a tweeter that flicks on the snare, a bass port, and the woofer with
//  the cover on its dust cap. The cover becomes the dust cap of a loudspeaker driver, and the driver
//  moves the way a real one does: the kick drives the cone, which is a
//  mass on a spring - out fast, back, a little past rest, settled. The
//  cone, the rubber surround and the cap all move together; the surround's
//  roll catches more light as it flexes out, and the cap throws a deeper
//  shadow onto the cone. Nothing else pulses: no rings, no glow. The
//  hardware does the work.
//
//  Drawn as its own layer in the cover's place; the flat cover fades out
//  as it fades in (PlayerSurface.kt). The frame loop runs only while the
//  speaker is showing and the music is playing.
// ============================================================================

/**
 * The cone's excursion, 0 at rest, about 1 on a full kick, briefly
 * negative on the way back. A damped spring driven by the beat's kick
 * envelope (BeatPulse.kt's cone): stiffness and damping give the
 * underdamped settle of a real woofer, about 15% overshoot.
 */
@Composable
private fun rememberExcursion(beat: BeatState, running: Boolean): () -> Float {
    val x = remember { mutableFloatStateOf(0f) }
    val latest by rememberUpdatedState(beat)
    LaunchedEffect(running) {
        if (!running) { x.floatValue = 0f; return@LaunchedEffect }
        var v = 0f
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.033f)
                last = now
                val drive = latest.cone.coerceIn(0f, 1.2f)
                val k = 1400f
                val c = 38f
                // Sub-steps keep the spring stable at 60 and 120 Hz alike.
                val n = 4
                val h = dt / n
                repeat(n) {
                    val a = k * (drive - x.floatValue) - c * v
                    v += a * h
                    x.floatValue += v * h
                }
            }
        }
    }
    return { x.floatValue }
}

/**
 * A whole loudspeaker in the cover's square: a studio-monitor cabinet with
 * a tweeter, the woofer ([item]'s artwork on its dust cap) and a bass
 * port. [strength] scales the travel: the display tier's reactive level.
 */
@Composable
fun SpeakerDriver(
    item: AppMediaItem?,
    beat: BeatState,
    running: Boolean,
    strength: Float,
    side: Dp,
    modifier: Modifier = Modifier
) {
    val excursion = rememberExcursion(beat, running)
    val cabW = side * 0.62f
    val wooferD = cabW * 0.80f
    val wooferTop = side * 0.545f - wooferD / 2f
    val capSide = wooferD * 0.44f
    Box(modifier.size(side).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        // The shadow the cabinet casts on whatever it stands on.
        Canvas(Modifier.fillMaxSize()) { drawFloorShadow(cabW.toPx()) }
        Box(
            Modifier.size(width = cabW, height = side)
                .graphicsLayer {
                    // A heavy kick shivers the whole box, very slightly, the
                    // way a real cabinet does on a shelf.
                    translationY = -0.8f * density * excursion().coerceAtLeast(0f) * strength
                }
        ) {
            // Cabinet, baffle, tweeter, port and badge.
            Canvas(Modifier.fillMaxSize()) {
                drawCabinet()
                drawTweeter(tweeterKick(beat) * strength)
                drawPort()
            }
            // The woofer, the cover on its dust cap.
            Box(
                Modifier.align(Alignment.TopCenter).offset(y = wooferTop).size(wooferD),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) { drawDriver(excursion() * strength) }
                if (item != null) {
                    Box(
                        Modifier.size(capSide)
                            .graphicsLayer {
                                val s = 1f + 0.10f * excursion() * strength
                                scaleX = s; scaleY = s
                            }
                            .clip(CircleShape)
                    ) {
                        CoverArt(item, Modifier.fillMaxSize(), corner = 0, targetPx = 512)
                        // The dome: light from the top left, the rim falling away.
                        Canvas(Modifier.fillMaxSize()) { drawDome(excursion() * strength) }
                    }
                }
            }
        }
    }
}

/** The tweeter's flick on the snare: out at once, gone in about 90 ms. */
private fun tweeterKick(beat: BeatState): Float {
    val hit = beat.snareNanos
    if (hit == 0L) return 0f
    val since = beat.frameNanos - hit
    if (since !in 0..90_000_000L) return 0f
    val f = 1f - since / 90_000_000f
    return f * f * beat.snarePower
}

/** A soft elliptical shadow under the cabinet. */
private fun DrawScope.drawFloorShadow(cabW: Float) {
    val cy = size.height * 0.985f
    val w = cabW * 1.25f
    drawOval(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(center.x, cy), radius = w / 2f
        ),
        topLeft = Offset(center.x - w / 2f, cy - size.height * 0.035f),
        size = androidx.compose.ui.geometry.Size(w, size.height * 0.07f)
    )
}

/** The box: matte graphite, a lit bevel, a recessed baffle and a satin sheen. */
private fun DrawScope.drawCabinet() {
    val w = size.width
    val h = size.height
    val rad = androidx.compose.ui.geometry.CornerRadius(w * 0.07f)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF24252A), Color(0xFF141519), Color(0xFF0C0C0E))),
        cornerRadius = rad
    )
    // Satin sheen down the left of the front, as off a studio light.
    drawRoundRect(
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = 0.045f), 0.35f to Color.White.copy(alpha = 0.015f), 0.6f to Color.Transparent
        ),
        cornerRadius = rad
    )
    // The bevel: light on the top and left edges, falling off to the right.
    drawRoundRect(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f), Color.Transparent),
            start = Offset(0f, 0f), end = Offset(w, h)
        ),
        cornerRadius = rad, style = Stroke(w * 0.012f)
    )
    // The baffle, set a little in, with a shadowed edge.
    val inset = w * 0.045f
    val bRad = androidx.compose.ui.geometry.CornerRadius(w * 0.05f)
    drawRoundRect(
        Color(0xFF17181C),
        topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(w - inset * 2, h - inset * 2),
        cornerRadius = bRad
    )
    drawRoundRect(
        Color.Black.copy(alpha = 0.6f),
        topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(w - inset * 2, h - inset * 2),
        cornerRadius = bRad, style = Stroke(w * 0.008f)
    )
}

/** The tweeter near the top: a machined plate, a waveguide, a small dome. */
private fun DrawScope.drawTweeter(kick: Float) {
    val c = Offset(size.width / 2f, size.height * 0.145f)
    val r = size.width * 0.14f
    drawCircle(
        Brush.sweepGradient(
            0f to Metal2, 0.2f to Metal1, 0.4f to Metal0, 0.6f to Metal1, 0.8f to Metal2, 1f to Metal2, center = c
        ),
        radius = r, center = c
    )
    drawCircle(Color.White.copy(alpha = 0.10f), radius = r - 0.5f, center = c, style = Stroke(1f))
    // The waveguide: a shallow dark horn.
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFF050506), Color(0xFF1A1B20), Color(0xFF2A2C32)),
            center = c, radius = r * 0.78f
        ),
        radius = r * 0.78f, center = c
    )
    // The dome, flicking forward on the snare.
    val d = r * 0.30f * (1f + 0.12f * kick)
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFD9DDE3), Color(0xFF6B7079), Color(0xFF202126)),
            center = c - Offset(d * 0.35f, d * 0.4f), radius = d * 1.3f
        ),
        radius = d, center = c
    )
    // Two small screws on the plate.
    for (sgn in listOf(-1f, 1f)) {
        val p = Offset(c.x + sgn * r * 0.90f * 0.7071f, c.y + r * 0.90f * 0.7071f)
        drawCircle(Color(0xFF4A4E56), radius = r * 0.055f, center = p)
    }
}

/** The bass port: a rounded slot near the foot, its lip catching the light. */
private fun DrawScope.drawPort() {
    val w = size.width * 0.52f
    val h = size.height * 0.045f
    val tl = Offset((size.width - w) / 2f, size.height * 0.875f)
    val rad = androidx.compose.ui.geometry.CornerRadius(h / 2f)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.Black, Color(0xFF0A0A0C)), startY = tl.y, endY = tl.y + h),
        topLeft = tl, size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = rad
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f)), startY = tl.y, endY = tl.y + h),
        topLeft = tl, size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = rad, style = Stroke(1.2f)
    )
    // A small brushed-metal badge below the port.
    val bw = size.width * 0.14f
    val bh = size.height * 0.012f
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFF3A3D44), Color(0xFF8C919A), Color(0xFF3A3D44))),
        topLeft = Offset((size.width - bw) / 2f, size.height * 0.935f),
        size = androidx.compose.ui.geometry.Size(bw, bh),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bh / 2f)
    )
}

private val Metal0 = Color(0xFF2E3036)
private val Metal1 = Color(0xFF15161A)
private val Metal2 = Color(0xFF3A3D44)

/** The basket, the bolts, the surround and the cone, with the cone at [x]. */
private fun DrawScope.drawDriver(x: Float) {
    val c = center
    val r = size.minDimension / 2f
    val out = x.coerceIn(-0.3f, 1.3f)

    // Frame: brushed dark metal, a sweep of light around it like a real
    // machined ring, a thin chamfer on its inner edge.
    drawCircle(
        Brush.sweepGradient(
            0f to Metal2, 0.12f to Metal1, 0.30f to Metal0, 0.45f to Metal1,
            0.62f to Metal2, 0.80f to Metal1, 1f to Metal2, center = c
        ),
        radius = r
    )
    drawCircle(Color.White.copy(alpha = 0.07f), radius = r - 0.5f, style = Stroke(1f))
    drawCircle(Color.Black.copy(alpha = 0.55f), radius = r * 0.955f, style = Stroke(r * 0.012f))

    // Eight bolts, each a tiny lit dome.
    for (b in 0 until 8) {
        val a = (b / 8f) * 2f * PI.toFloat() + PI.toFloat() / 8f
        val p = Offset(c.x + cos(a) * r * 0.90f, c.y + sin(a) * r * 0.90f)
        val br = r * 0.028f
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = br * 1.25f, center = p + Offset(br * 0.25f, br * 0.35f))
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFB8BEC8), Color(0xFF4A4E56), Color(0xFF1C1D21)),
                center = p - Offset(br * 0.35f, br * 0.4f), radius = br * 1.4f),
            radius = br, center = p
        )
    }

    // The surround: a half-roll of rubber. As the cone comes out the roll
    // stretches and its crest catches more light, from the top left.
    val s0 = r * 0.70f * (1f + 0.045f * out)
    val s1 = r * 0.86f
    val crest = 0.55f + 0.05f * out
    val lift = (0.10f + 0.16f * out.coerceAtLeast(0f)).coerceIn(0.04f, 0.32f)
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF060607),
            (s0 / s1) to Color(0xFF09090B),
            ((s0 / s1) + (1f - s0 / s1) * crest) to Color(0xFF2A2C31).copy(alpha = 1f),
            1f to Color(0xFF070708),
            center = c, radius = s1
        ),
        radius = s1
    )
    // The light on the roll's crest, strongest top-left.
    drawCircle(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = lift), Color.Transparent, Color.Transparent, Color.White.copy(alpha = lift * 0.15f)),
            start = Offset(c.x - s1, c.y - s1), end = Offset(c.x + s1, c.y + s1)
        ),
        radius = (s0 + s1) / 2f,
        style = Stroke((s1 - s0) * 0.55f)
    )

    // The cone: pressed paper, dark, lit from the top left; it grows a
    // little as it comes out and its rim darkens into the surround.
    val cr = s0
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF34363D), 0.55f to Color(0xFF1B1C21), 1f to Color(0xFF07070A),
            center = Offset(c.x - cr * 0.18f, c.y - cr * 0.22f), radius = cr * 1.15f
        ),
        radius = cr
    )
    // Fibre: faint concentric pressing lines across the paper.
    for (k in 1..6) {
        drawCircle(
            Color.White.copy(alpha = 0.028f),
            radius = cr * (0.58f + k * 0.065f),
            style = Stroke(1f)
        )
    }
    // The cap's shadow on the cone deepens as the cap comes forward.
    val capR = r * 0.44f * (1f + 0.10f * out)
    drawCircle(
        Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.55f + 0.25f * out.coerceAtLeast(0f)),
            0.75f to Color.Black.copy(alpha = 0.18f),
            1f to Color.Transparent,
            center = c + Offset(capR * 0.06f, capR * 0.10f), radius = capR * (1.35f + 0.15f * out)
        ),
        radius = capR * (1.35f + 0.15f * out),
        center = c + Offset(capR * 0.06f, capR * 0.10f)
    )
}

/** The dust cap's dome over the cover: a highlight and a falling-away rim. */
private fun DrawScope.drawDome(x: Float) {
    val r = size.minDimension / 2f
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = (0.26f + 0.10f * x).coerceIn(0f, 0.45f)),
            0.35f to Color.White.copy(alpha = 0.04f),
            0.7f to Color.Transparent,
            center = Offset(r * 0.62f, r * 0.55f), radius = r * 1.1f
        ),
        radius = r
    )
    drawCircle(
        Brush.radialGradient(
            0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f),
            center = center, radius = r
        ),
        radius = r
    )
    drawCircle(Color.White.copy(alpha = 0.16f), radius = r - 0.75f, style = Stroke(1.2f))
}

