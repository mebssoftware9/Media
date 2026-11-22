package com.media.app

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp

// ============================================================================
//  LIVING WORLDS on screen (LivingWorld.kt is the world itself).
//
//  One layer in Now Playing, above the living backdrop and its scrim, below
//  the rings, the edge light and the cover. It reads the track's analysis
//  itself, at the player's own clock, so it moves with the music whether or
//  not Reactive artwork (which moves the COVER) is on.
//
//  COST. One frame loop, only while Now Playing is open and the world is
//  moving. Paused, it settles and stops asking for frames. Particles are
//  arrays made once per song; a frame allocates nothing but the few
//  gradient brushes the bands and ribbons need. Every soft light is one
//  draw of a 64px sprite, not a gradient made per particle.
// ============================================================================

/** What the world needs from the player, passed down from HomeScaffold. */
class WorldFeed(
    val mode: WorldMode,
    /** This track's analysis; null for spoken word, video, or while analysis runs. */
    val shape: SoundShape?,
    val volume: Float,
    val playing: Boolean,
    val speed: Float,
    /** The player's own position, read every frame. */
    val clock: () -> Long
)

/** A soft white light, 64px, made once: every glow is this sprite, tinted. */
private val glowSprite: ImageBitmap by lazy {
    val n = 64
    val px = IntArray(n * n)
    val c = (n - 1) / 2f
    for (y in 0 until n) for (x in 0 until n) {
        val dx = (x - c) / c
        val dy = (y - c) / c
        val d2 = dx * dx + dy * dy
        val a = if (d2 >= 1f) 0f else exp(-d2 * 3.2f) * (1f - d2)
        px[y * n + x] = ((a * 255f).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
    }
    Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Draws a world into a Compose DrawScope. One per layer; [scope] is set each frame. */
internal class ComposeWorldPainter : WorldPainter {
    lateinit var scope: DrawScope
    override var fade: Float = 1f
    /** Dark theme: lights add to the backdrop. Light theme: they lie over it. */
    var additive: Boolean = true
    private val tints = HashMap<Int, ColorFilter>()
    private val path = Path()

    private fun a(alpha: Float) = (alpha * fade).coerceIn(0f, 1f)

    private fun tint(argb: Int): ColorFilter {
        if (tints.size > 48) tints.clear()
        return tints.getOrPut(argb) { ColorFilter.tint(Color(argb), BlendMode.SrcIn) }
    }

    override fun glow(x: Float, y: Float, r: Float, argb: Int, alpha: Float) {
        val al = a(alpha)
        if (al < 0.004f || r < 0.5f) return
        val s = (r * 2f).toInt().coerceAtLeast(2)
        scope.drawImage(
            image = glowSprite,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(glowSprite.width, glowSprite.height),
            dstOffset = IntOffset((x - r).toInt(), (y - r).toInt()),
            dstSize = IntSize(s, s),
            alpha = al,
            colorFilter = tint(argb),
            blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver,
            filterQuality = FilterQuality.Low
        )
    }

    override fun dot(x: Float, y: Float, r: Float, argb: Int, alpha: Float) {
        val al = a(alpha)
        if (al < 0.004f) return
        scope.drawCircle(Color(argb), radius = r, center = Offset(x, y), alpha = al)
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, argb: Int, alpha: Float) {
        val al = a(alpha)
        if (al < 0.004f) return
        scope.drawLine(Color(argb), Offset(x0, y0), Offset(x1, y1), strokeWidth = width, cap = StrokeCap.Round, alpha = al)
    }

    override fun ring(x: Float, y: Float, r: Float, width: Float, argb: Int, alpha: Float) {
        val al = a(alpha)
        if (al < 0.004f) return
        scope.drawCircle(Color(argb), radius = r, center = Offset(x, y), alpha = al, style = Stroke(width))
    }

    override fun oval(x: Float, y: Float, rx: Float, ry: Float, width: Float, argb: Int, alpha: Float) {
        val al = a(alpha)
        if (al < 0.004f) return
        scope.drawOval(Color(argb), topLeft = Offset(x - rx, y - ry), size = Size(rx * 2f, ry * 2f), alpha = al, style = Stroke(width))
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, argb: Int, alpha: Float) {
        val al = a(alpha)
        if (al < 0.004f || right <= left || bottom <= top) return
        scope.drawRect(Color(argb), topLeft = Offset(left, top), size = Size(right - left, bottom - top), alpha = al)
    }

    override fun band(top: Float, bottom: Float, argb: Int, alpha: Float) {
        val al = a(alpha)
        if (al < 0.004f || bottom <= top) return
        val c = Color(argb)
        scope.drawRect(
            Brush.verticalGradient(
                0f to c.copy(alpha = 0f), 0.5f to c.copy(alpha = al), 1f to c.copy(alpha = 0f),
                startY = top, endY = bottom
            ),
            topLeft = Offset(0f, top), size = Size(scope.size.width, bottom - top)
        )
    }

    override fun quad(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        argb: Int, alphaTop: Float, alphaBottom: Float
    ) {
        val at = a(alphaTop)
        val ab = a(alphaBottom)
        if (at < 0.004f && ab < 0.004f) return
        val c = Color(argb)
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        val top = minOf(y0, y1, y2, y3)
        val bottom = maxOf(y0, y1, y2, y3).coerceAtLeast(top + 1f)
        scope.drawPath(path, Brush.verticalGradient(listOf(c.copy(alpha = at), c.copy(alpha = ab)), startY = top, endY = bottom))
    }

    override fun ribbon(n: Int, xs: FloatArray, tops: FloatArray, bottoms: FloatArray, argb: Int, alpha: Float) {
        val al = a(alpha)
        if (al < 0.004f || n < 2) return
        val c = Color(argb)
        path.reset()
        path.moveTo(xs[0], tops[0])
        var minTop = tops[0]
        var maxBottom = bottoms[0]
        for (i in 1 until n) {
            path.lineTo(xs[i], tops[i])
            if (tops[i] < minTop) minTop = tops[i]
        }
        for (i in n - 1 downTo 0) {
            path.lineTo(xs[i], bottoms[i])
            if (bottoms[i] > maxBottom) maxBottom = bottoms[i]
        }
        path.close()
        scope.drawPath(
            path,
            Brush.verticalGradient(
                0f to c.copy(alpha = 0f), 0.7f to c.copy(alpha = al), 1f to c.copy(alpha = al * 0.55f),
                startY = minTop, endY = maxBottom.coerceAtLeast(minTop + 1f)
            ),
            blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver
        )
    }
}

/** The cover's reading from its smoothed light (CoverLight.kt), else from its palette. */
private fun coverReadingOf(light: CoverLight?, colors: ArtColors?): CoverReading? {
    light?.let { l ->
        runCatching {
            val bmp = l.backdrop.asAndroidBitmap()
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return readCover(px)
        }
    }
    if (colors == null) return null
    val argbs = buildList {
        colors.accent?.let { add(it.toArgb()) }
        colors.glow?.let { add(it.toArgb()) }
        colors.field.forEach { add(it.toArgb()) }
    }
    return if (argbs.isEmpty()) null else readCover(argbs.toIntArray())
}

/**
 * The world for what is playing, drawn full-screen. Placement comes from
 * the cover: [coverCx], [coverCy] and [coverSize] in pixels, [textTop]
 * where the title starts. [active]: Now Playing is open, so the world may
 * run its clock.
 */
@Composable
internal fun LivingWorldLayer(
    feed: WorldFeed,
    item: AppMediaItem?,
    light: CoverLight?,
    colors: ArtColors?,
    tilt: Tilt?,
    active: Boolean,
    coverCx: Float,
    coverCy: Float,
    coverSize: Float,
    textTop: Float,
    level: QualityLevel,
    relieved: Boolean,
    modifier: Modifier = Modifier
) {
    val reduced = LocalReducedMotion.current
    val darkTheme = MediaColors.Ink.luminance() < 0.5f
    val reading = remember(light, colors) { coverReadingOf(light, colors) }
    val kind = feed.mode.kind ?: reading?.let(::chooseWorld) ?: return
    val seed = item?.id ?: 0L
    val budget = worldBudget(level, relieved)
    val scene = remember(kind, seed, budget) { WorldScene.create(kind, seed, budget) }
    val palette = remember(kind, colors) {
        worldPalette(
            kind,
            colors?.accent?.toArgb(),
            colors?.glow?.toArgb(),
            colors?.field?.map { it.toArgb() }?.toIntArray() ?: IntArray(0)
        )
    }
    val signal = remember(scene) { WorldSignal() }
    val frame = remember { WorldFrame() }
    val painter = remember { ComposeWorldPainter() }
    var tick by remember { mutableLongStateOf(0L) }
    // A new song's world fades in rather than cutting.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(scene) {
        shown.snapTo(0f)
        shown.animateTo(1f, tween(900))
    }
    val latest by rememberUpdatedState(feed)
    val running = active && !reduced
    LaunchedEffect(scene, running, feed.playing, feed.shape) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            val more = withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                val f = latest
                hear(signal, f.shape, f.clock(), f.volume, f.playing, f.speed, dt)
                scene.advance(dt, signal)
                tick = now
                f.playing || signal.energy > 0f
            }
            // Paused and settled: a still picture asks for no frames.
            if (!more) break
        }
    }
    Canvas(modifier.graphicsLayer()) {
        // Read here, in the draw: each frame redraws this canvas and never recomposes.
        if (tick < 0L) return@Canvas
        frame.w = size.width
        frame.h = size.height
        frame.density = density
        frame.coverCx = coverCx
        frame.coverCy = coverCy
        frame.coverSize = coverSize
        frame.textTop = textTop
        frame.tiltX = tilt?.x ?: 0f
        frame.tiltY = tilt?.y ?: 0f
        painter.scope = this
        painter.additive = darkTheme
        painter.fade = shown.value
        scene.draw(painter, frame, signal, palette)
    }
}
