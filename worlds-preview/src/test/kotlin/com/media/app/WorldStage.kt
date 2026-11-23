package com.media.app

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A stand-in for Now Playing, for the previews: the cover as blurred light
 * behind everything with Via's scrim over it (as LivingBackdrop draws),
 * the world, then the cover, the title and the controls on top - the same
 * order as PlayerSurface. Sizes are a 412 x 915 dp phone.
 */
object WorldStage {
    const val DP_W = 412f
    const val DP_H = 915f

    /** A synthetic cover for each world: two colours and a sun, 128 px. */
    fun cover(kind: WorldKind): IntArray {
        val (a, b) = when (kind) {
            WorldKind.NIGHT_RAIN -> 0xFF0B1426.toInt() to 0xFF1E3350.toInt()
            WorldKind.FIREFLIES -> 0xFF1B5E20.toInt() to 0xFF8BC34A.toInt()
            WorldKind.OCEAN -> 0xFF1565C0.toInt() to 0xFF4FC3F7.toInt()
            WorldKind.COSMOS -> 0xFF22093F.toInt() to 0xFF6A1B9A.toInt()
            WorldKind.DREAM -> 0xFFF8BBD0.toInt() to 0xFFD1B3F0.toInt()
            WorldKind.EMBERS -> 0xFFB3260A.toInt() to 0xFFFF8F00.toInt()
            WorldKind.MINIMAL -> 0xFF202024.toInt() to 0xFFCFCFD4.toInt()
        }
        val n = 128
        return IntArray(n * n) { i ->
            val x = i % n
            val y = i / n
            val t = (x + y) / (2f * n)
            val base = mixArgb(a, b, t)
            val dx = x - n * 0.64f
            val dy = y - n * 0.36f
            val sun = (1f - sqrt(dx * dx + dy * dy) / (n * 0.22f)).coerceIn(0f, 1f)
            mixArgb(base, mixArgb(b, 0xFFFFFFFF.toInt(), 0.45f), sun * 0.8f)
        }
    }

    /** Palette as the phone derives it (accent, glow, tones), roughly: the cover's two colours. */
    fun palette(kind: WorldKind, cover: IntArray): WorldPalette {
        val a = cover[cover.size / 2 + 64]
        val b = cover[cover.size - 1]
        val r = readCover(cover)
        return if (r.hue == null) worldPalette(kind, null, null, IntArray(0))
        else worldPalette(kind, brighten(b), brighten(a), intArrayOf(a, b))
    }

    private fun brighten(c: Int): Int = mixArgb(c, 0xFFFFFFFF.toInt(), 0.25f)

    class Layout(val w: Int, val h: Int) {
        val density = w / DP_W
        val coverSide = w * 0.76f
        val coverX = (w - coverSide) / 2f
        val coverY = (24f + 56f) * density
        val textTop = coverY + coverSide + 20f * density
        fun frame(f: WorldFrame) {
            f.w = w.toFloat(); f.h = h.toFloat(); f.density = density
            f.coverCx = w / 2f; f.coverCy = coverY + coverSide / 2f; f.coverSize = coverSide
            f.textTop = textTop
        }
    }

    /** The backdrop alone (cover light + scrim), as Via draws it without a world. */
    fun drawBackdrop(g: Graphics2D, l: Layout, cover: IntArray) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x0B0B0F)
        g.fill(Rectangle2D.Float(0f, 0f, l.w.toFloat(), l.h.toFloat()))
        val n = 128
        val samples = listOf(cover[(n / 4) * n + n / 4], cover[(n / 2) * n + (3 * n / 4)], cover[(3 * n / 4) * n + n / 3])
        val spots = listOf(0.25f to 0.18f, 0.80f to 0.40f, 0.35f to 0.70f)
        for ((i, s) in samples.withIndex()) {
            val (fx, fy) = spots[i]
            val c = Color(s)
            val r = l.w * 0.95f
            g.paint = RadialGradientPaint(
                Point2D.Float(l.w * fx, l.h * fy), r, floatArrayOf(0f, 1f),
                arrayOf(Color(c.red, c.green, c.blue, 200), Color(c.red, c.green, c.blue, 0))
            )
            g.fill(Rectangle2D.Float(0f, 0f, l.w.toFloat(), l.h.toFloat()))
        }
        // The scrim: 0.40 at the top to 0.58 at the bottom, as LivingBackdrop.
        g.paint = GradientPaint(0f, 0f, Color(11, 11, 15, 102), 0f, l.h.toFloat(), Color(11, 11, 15, 148))
        g.fill(Rectangle2D.Float(0f, 0f, l.w.toFloat(), l.h.toFloat()))
    }

    /** The cover, the words and the controls, above the world. */
    fun drawForeground(g: Graphics2D, l: Layout, cover: IntArray, title: String) {
        val d = l.density
        val img = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 128, 128, cover, 0, 128)
        val shape = RoundRectangle2D.Float(l.coverX, l.coverY, l.coverSide, l.coverSide, 18f * d * 2, 18f * d * 2)
        val oldClip = g.clip
        g.clip = shape
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, l.coverX.toInt(), l.coverY.toInt(), l.coverSide.toInt(), l.coverSide.toInt(), null)
        g.clip = oldClip
        val cream = Color(0xF4, 0xEF, 0xE6)
        val dim = Color(0xF4, 0xEF, 0xE6, 150)
        var y = l.textTop + 28f * d
        g.color = cream
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (24f * d).toInt())
        runCatching { g.drawString(title, 24f * d, y) }
        y += 26f * d
        g.color = dim
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, (16f * d).toInt())
        runCatching { g.drawString("Via · Living Worlds", 24f * d, y) }
        // Seek bar.
        y += 40f * d
        g.stroke = BasicStroke(4f * d, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Color(0xF4, 0xEF, 0xE6, 60)
        g.drawLine((24 * d).toInt(), y.toInt(), (l.w - 24 * d).toInt(), y.toInt())
        g.color = cream
        g.drawLine((24 * d).toInt(), y.toInt(), (l.w * 0.42f).toInt(), y.toInt())
        // Transport: previous, play, next.
        y += 70f * d
        val cx = l.w / 2f
        g.color = cream
        g.fill(Ellipse2D.Float(cx - 34f * d, y - 34f * d, 68f * d, 68f * d))
        g.color = Color(0x0B0B0F)
        g.fill(Rectangle2D.Float(cx - 9f * d, y - 12f * d, 6f * d, 24f * d))
        g.fill(Rectangle2D.Float(cx + 3f * d, y - 12f * d, 6f * d, 24f * d))
        g.color = cream
        for (side in listOf(-1f, 1f)) {
            val bx = cx + side * 96f * d
            g.fill(Ellipse2D.Float(bx - 6f * d, y - 6f * d, 12f * d, 12f * d))
        }
    }

    /**
     * A 120 BPM groove: kick on every beat, snare on 2 and 4, hats on the
     * eighths, the bass swelling with each kick. One call per frame.
     */
    class Groove {
        private var t = 0f
        private var nextKick = 0f
        private var nextSnare = 0.5f
        fun step(sig: WorldSignal, dt: Float, playing: Boolean = true) {
            val target = if (playing) 1f else 0f
            sig.energy += (target - sig.energy) * (1f - kotlin.math.exp(-dt / if (playing) 0.35f else 0.9f))
            if (!playing && sig.energy < 0.004f) sig.energy = 0f
            if (playing) {
                t += dt
                if (t >= nextKick) { sig.hitKick(0.95f); nextKick += 0.5f }
                if (t >= nextSnare) { sig.hitSnare(0.75f); nextSnare += 1.0f }
                val sinceKick = (t % 0.5f)
                val bass = max(0f, 1f - sinceKick / 0.35f)
                val hats = if ((t % 0.25f) < 0.06f) 0.8f else 0.25f
                sig.follow(dt, bass * 0.9f, 0.4f, hats)
            } else {
                sig.follow(dt, 0f, 0f, 0f)
                if (sig.energy == 0f) sig.rest()
            }
        }
    }

    /** One frame of [kind] after [seconds] of the groove, drawn [w] x [h]. */
    fun render(kind: WorldKind, w: Int, h: Int, seconds: Float, withWorld: Boolean = true, budget: Float = 1f): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val l = Layout(w, h)
        val cover = cover(kind)
        drawBackdrop(g, l, cover)
        if (withWorld) {
            val scene = WorldScene.create(kind, 42L, budget)
            val sig = WorldSignal()
            val groove = Groove()
            val dt = 1f / 60f
            var elapsed = 0f
            while (elapsed < seconds) {
                groove.step(sig, dt)
                scene.advance(dt, sig)
                elapsed += dt
            }
            val f = WorldFrame().also { l.frame(it) }
            scene.draw(AwtWorldPainter(g, w), f, sig, palette(kind, cover))
        }
        drawForeground(g, l, cover, title(kind))
        g.dispose()
        return img
    }

    fun title(kind: WorldKind): String = when (kind) {
        WorldKind.NIGHT_RAIN -> "Night Rain"
        WorldKind.FIREFLIES -> "Fireflies"
        WorldKind.OCEAN -> "Ocean"
        WorldKind.COSMOS -> "Cosmos"
        WorldKind.DREAM -> "Dream"
        WorldKind.EMBERS -> "Embers"
        WorldKind.MINIMAL -> "Minimal"
    }
}
