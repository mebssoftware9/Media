package com.media.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ============================================================================
//  REACTIVE LYRICS
//
//  Lyrics used to swap on a fade, at the player's half-second tick. Now the
//  line being sung lives with the song:
//
//      the sweep   the line fills with colour left to right as it is sung,
//                  timed to the player's own clock every frame
//      the beat    on each kick the line swells a little and its glow
//                  flares (BeatPulse's cone, read in the draw pass)
//      the entry   a new line rises into place with a flash of light
//
//  LRC gives one time per line, so the sweep is paced over the line's
//  expected length (about 75 ms a character), never past the next line.
// ============================================================================

/** The player's position every frame while playing; [fallback] otherwise. */
@Composable
fun rememberSmoothPosition(fallback: Long, playing: Boolean): State<Long> {
    val pos = remember { mutableLongStateOf(fallback) }
    LaunchedEffect(playing, fallback) {
        if (!playing) { pos.longValue = fallback; return@LaunchedEffect }
        while (true) {
            withFrameNanos { pos.longValue = PlayerClock.positionMs() ?: fallback }
        }
    }
    return pos
}

/** How far through line [i] the singing is, 0..1. */
fun lineProgress(lines: List<LyricLine>, i: Int, positionMs: Long, offsetMs: Long): Float {
    if (i !in lines.indices) return 0f
    val start = lines[i].timeMs - offsetMs
    val next = lines.getOrNull(i + 1)?.let { it.timeMs - offsetMs } ?: Long.MAX_VALUE
    val estimate = (600L + 75L * lines[i].text.length).coerceAtLeast(1_500L)
    val end = minOf(next, start + estimate)
    if (end <= start) return 1f
    return ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

/** Text filled in [lit] up to [p], [dim] after, with a soft edge. */
fun sweepBrush(p: Float, lit: Color, dim: Color): Brush {
    val e = 0.04f
    return Brush.horizontalGradient(
        0f to lit,
        (p - e).coerceIn(0f, 1f) to lit,
        (p + e).coerceIn(0f, 1f) to dim,
        1f to dim
    )
}

/**
 * The line being sung, under the cover, with the next one waiting below it.
 * Tap opens the full lyrics.
 */
@Composable
fun SungLine(
    lines: List<LyricLine>,
    statePositionMs: Long,
    playing: Boolean,
    offsetMs: Long,
    accent: Color,
    beat: BeatState?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val pos by rememberSmoothPosition(statePositionMs, playing)
    val i = Lrc.activeIndex(lines, pos, offsetMs)
    val now = lines.getOrNull(i)?.text?.takeIf { it.isNotBlank() } ?: "♪"
    val next = lines.getOrNull(i + 1)?.text.orEmpty()
    val p = if (i >= 0) lineProgress(lines, i, pos, offsetMs) else 0f
    // Each new line rises in from below with a flash of light.
    val rise = remember { Animatable(0f) }
    LaunchedEffect(i) {
        rise.snapTo(1f)
        rise.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }
    val cream = MediaColors.Cream
    val base = Typo.Section
    // Centred: the line rises in from below and opens out from the middle
    // to its full width, and the next line waits, centred, beneath it.
    Column(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = onClick
        ),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(
            now,
            style = base.copy(
                fontWeight = FontWeight.Bold,
                brush = sweepBrush(p, accent, cream),
                shadow = Shadow(accent.copy(alpha = 0.55f + 0.35f * rise.value), blurRadius = 22f + 30f * rise.value)
            ),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                // The kick, read here in the draw pass: no recomposition.
                val k = beat?.push ?: 0f
                val r = rise.value
                // Up from below, and in from the sides: it starts narrow at
                // the centre and opens out to its full width.
                scaleX = (1f + 0.035f * k) * (1f - 0.22f * r)
                scaleY = (1f + 0.035f * k) * (1f - 0.08f * r)
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = 26.dp.toPx() * r
                alpha = 1f - 0.85f * r
            }
        )
        if (next.isNotBlank()) {
            Text(
                next,
                style = Typo.Body,
                color = MediaColors.CreamFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    .graphicsLayer { alpha = 0.75f - 0.5f * rise.value }
            )
        }
    }
}

/** The active line's style in the full lyrics: the sweep and a glow that breathes with the beat. */
fun activeLyricStyle(base: TextStyle, p: Float, accent: Color, cream: Color, glow: Boolean): TextStyle =
    base.copy(
        fontWeight = FontWeight.Bold,
        brush = sweepBrush(p, accent, cream),
        shadow = if (glow) Shadow(accent.copy(alpha = 0.6f), blurRadius = 30f) else null
    )
