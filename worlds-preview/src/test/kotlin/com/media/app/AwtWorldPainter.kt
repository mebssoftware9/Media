package com.media.app

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster

/**
 * Draws a world with AWT, for the previews and the visibility test. The same
 * calls the phone makes (LivingWorldLayer.kt's ComposeWorldPainter), drawn
 * the same way: soft lights add to what is under them on a dark theme.
 */
class AwtWorldPainter(private val g: Graphics2D, private val width: Int) : WorldPainter {
    override var fade: Float = 1f

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun c(argb: Int, alpha: Float): Color {
        val a = (alpha * fade).coerceIn(0f, 1f)
        return Color((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff, (a * 255f).toInt())
    }

    private inline fun additive(block: () -> Unit) {
        val old = g.composite
        g.composite = AddComposite
        block()
        g.composite = old
    }

    override fun glow(x: Float, y: Float, r: Float, argb: Int, alpha: Float) {
        if (alpha * fade < 0.004f || r < 0.5f) return
        val paint = RadialGradientPaint(
            Point2D.Float(x, y), r,
            floatArrayOf(0f, 0.25f, 0.6f, 1f),
            arrayOf(c(argb, alpha), c(argb, alpha * 0.62f), c(argb, alpha * 0.16f), c(argb, 0f))
        )
        additive {
            g.paint = paint
            g.fill(Ellipse2D.Float(x - r, y - r, r * 2f, r * 2f))
        }
    }

    override fun dot(x: Float, y: Float, r: Float, argb: Int, alpha: Float) {
        if (alpha * fade < 0.004f) return
        g.paint = c(argb, alpha)
        g.fill(Ellipse2D.Float(x - r, y - r, r * 2f, r * 2f))
    }

    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, argb: Int, alpha: Float) {
        if (alpha * fade < 0.004f) return
        g.paint = c(argb, alpha)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(x0, y0, x1, y1))
    }

    override fun ring(x: Float, y: Float, r: Float, width: Float, argb: Int, alpha: Float) =
        oval(x, y, r, r, width, argb, alpha)

    override fun oval(x: Float, y: Float, rx: Float, ry: Float, width: Float, argb: Int, alpha: Float) {
        if (alpha * fade < 0.004f) return
        g.paint = c(argb, alpha)
        g.stroke = BasicStroke(width)
        g.draw(Ellipse2D.Float(x - rx, y - ry, rx * 2f, ry * 2f))
    }

    override fun rect(left: Float, top: Float, right: Float, bottom: Float, argb: Int, alpha: Float) {
        if (alpha * fade < 0.004f || right <= left || bottom <= top) return
        g.paint = c(argb, alpha)
        g.fill(Rectangle2D.Float(left, top, right - left, bottom - top))
    }

    override fun band(top: Float, bottom: Float, argb: Int, alpha: Float) {
        if (alpha * fade < 0.004f || bottom <= top + 1f) return
        g.paint = LinearGradientPaint(
            0f, top, 0f, bottom, floatArrayOf(0f, 0.5f, 1f),
            arrayOf(c(argb, 0f), c(argb, alpha), c(argb, 0f))
        )
        g.fill(Rectangle2D.Float(0f, top, width.toFloat(), bottom - top))
    }

    override fun quad(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        argb: Int, alphaTop: Float, alphaBottom: Float
    ) {
        if (alphaTop * fade < 0.004f && alphaBottom * fade < 0.004f) return
        val top = minOf(y0, y1, y2, y3)
        val bottom = maxOf(y0, y1, y2, y3).coerceAtLeast(top + 1f)
        val path = Path2D.Float().apply { moveTo(x0, y0); lineTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); closePath() }
        g.paint = LinearGradientPaint(0f, top, 0f, bottom, floatArrayOf(0f, 1f), arrayOf(c(argb, alphaTop), c(argb, alphaBottom)))
        g.fill(path)
    }

    override fun ribbon(n: Int, xs: FloatArray, tops: FloatArray, bottoms: FloatArray, argb: Int, alpha: Float) {
        if (alpha * fade < 0.004f || n < 2) return
        val path = Path2D.Float()
        path.moveTo(xs[0], tops[0])
        var minTop = tops[0]
        var maxBottom = bottoms[0]
        for (i in 1 until n) { path.lineTo(xs[i], tops[i]); minTop = minOf(minTop, tops[i]) }
        for (i in n - 1 downTo 0) { path.lineTo(xs[i], bottoms[i]); maxBottom = maxOf(maxBottom, bottoms[i]) }
        path.closePath()
        val paint = LinearGradientPaint(
            0f, minTop, 0f, maxBottom.coerceAtLeast(minTop + 1f), floatArrayOf(0f, 0.7f, 1f),
            arrayOf(c(argb, 0f), c(argb, alpha), c(argb, alpha * 0.55f))
        )
        additive {
            g.paint = paint
            g.fill(path)
        }
    }
}

/** Additive light, as BlendMode.Plus on the phone: each channel adds, clipped at white. */
object AddComposite : Composite {
    override fun createContext(src: ColorModel, dst: ColorModel, hints: RenderingHints?): CompositeContext =
        object : CompositeContext {
            override fun dispose() {}
            override fun compose(s: Raster, d: Raster, out: WritableRaster) {
                val w = minOf(s.width, d.width)
                val h = minOf(s.height, d.height)
                val sp = IntArray(4)
                val dp = IntArray(4)
                for (y in 0 until h) for (x in 0 until w) {
                    s.getPixel(s.minX + x, s.minY + y, sp)
                    d.getPixel(d.minX + x, d.minY + y, dp)
                    val a = sp[3] / 255f
                    dp[0] = minOf(255, dp[0] + (sp[0] * a).toInt())
                    dp[1] = minOf(255, dp[1] + (sp[1] * a).toInt())
                    dp[2] = minOf(255, dp[2] + (sp[2] * a).toInt())
                    dp[3] = maxOf(dp[3], sp[3])
                    out.setPixel(out.minX + x, out.minY + y, dp)
                }
            }
        }
}
