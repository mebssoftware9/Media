package com.media.app

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
//  LIVING WORLDS (6.1)
//
//  Now Playing becomes a place. The cover decides which world it is; the
//  track's own analysis (SoundShape.kt, BeatGrid.kt) moves it. It is drawn
//  ON TOP of everything Via already does - the living backdrop, the light
//  field, the edge light, the rings, the cone - and multiplies none of it.
//  Every existing effect looks exactly as it did in 6.0.
//
//  WHAT EACH WORLD IS
//    NIGHT RAIN  dark, moody covers. Rain in depth, a skyline around the
//                cover with lit windows, its reflection. The kick is a gust
//                of wind; a snare lights or darkens a window.
//    FIREFLIES   greens and yellows. Fireflies on their own slow paths,
//                blinking; shafts of light through the canopy. The kick
//                lights them; the hats quicken the blinking.
//    OCEAN       blues and cyans. A horizon under the cover, the cover's
//                light as a moon path on the water. The bass lifts the
//                swell; the kick sends a ripple; the hats make it glint.
//    COSMOS      violets, dark purples. A star field in depth that turns
//                slowly, a ring of light orbiting the cover, nebulae in the
//                cover's colours. The kick warps the stars; the hats
//                twinkle them.
//    DREAM       pinks, pastels. Bokeh drifting up, aurora ribbons behind
//                the cover. The bass moves the ribbons; the kick swells the
//                bokeh.
//    EMBERS      reds, oranges. Sparks rising on the heat, a glow from
//                below. The kick throws them up.
//    MINIMAL     covers with no colour. A monochrome floor in perspective
//                and fine dust; the kick is a line of light running out.
//
//  RULES
//    - Measured, never invented: every reaction is a kick, snare, hats or
//      bass value from the track's own analysis. No analysis yet (first
//      play) or spoken word: the world moves on its own, calmly, and
//      reacts to nothing.
//    - Bounded: nothing flashes the screen. Light is local - a firefly, a
//      star, a ripple - and every pulse has a ceiling.
//    - Calm under the words: the world fades to a floor below the cover,
//      where the title and controls are (WorldFrame.veil).
//    - Paused settles: the world slows to a still picture and stops asking
//      for frames.
//    - Deterministic: positions come from the track's id, so a song's
//      world is the same every time it plays.
//    - Pure: this file has no Android in it. LivingWorldLayer.kt draws it
//      with Compose; the unit tests draw it to PNG with AWT.
// ============================================================================

enum class WorldKind { NIGHT_RAIN, FIREFLIES, OCEAN, COSMOS, DREAM, EMBERS, MINIMAL }

/** The Settings choice: Auto (from the cover), Off, or one world for every song. */
enum class WorldMode(val kind: WorldKind?) {
    AUTO(null),
    OFF(null),
    NIGHT_RAIN(WorldKind.NIGHT_RAIN),
    FIREFLIES(WorldKind.FIREFLIES),
    OCEAN(WorldKind.OCEAN),
    COSMOS(WorldKind.COSMOS),
    DREAM(WorldKind.DREAM),
    EMBERS(WorldKind.EMBERS),
    MINIMAL(WorldKind.MINIMAL);

    companion object {
        fun fromName(name: String?): WorldMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

// ------------------------------------------------------------ the cover --

/**
 * What a cover looks like, measured: its hue (weighted by chroma, so the
 * coloured parts speak and the greys do not), how colourful it is, how
 * bright, and how much of its colour is warm.
 */
class CoverReading(
    /** Degrees, or null: the cover has no colour to speak of. */
    val hue: Float?,
    /** Mean chroma (max - min channel), 0..1. Stable on dark pixels, unlike HSL saturation. */
    val chroma: Float,
    /** Mean luma, 0..1. */
    val luma: Float,
    /** Share of the cover's colour that is red, orange or amber, 0..1. */
    val warm: Float
)

/** Reads a cover from its ARGB pixels (any size; Via passes the 128px smoothed cover). */
fun readCover(pixels: IntArray): CoverReading {
    if (pixels.isEmpty()) return CoverReading(null, 0f, 0f, 0f)
    var hx = 0.0
    var hy = 0.0
    var weight = 0.0
    var warm = 0.0
    var chroma = 0.0
    var luma = 0.0
    for (c in pixels) {
        val r = ((c shr 16) and 0xff) / 255f
        val g = ((c shr 8) and 0xff) / 255f
        val b = (c and 0xff) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        luma += 0.2126 * r + 0.7152 * g + 0.0722 * b
        chroma += d
        if (d > 0.02f) {
            var h = when (hi) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (h < 0f) h += 360f
            val rad = h * PI / 180.0
            hx += cos(rad) * d
            hy += sin(rad) * d
            weight += d
            if (h < 48f || h >= 345f) warm += d
        }
    }
    val n = pixels.size.toDouble()
    val meanChroma = (chroma / n).toFloat()
    val hue = if (weight / n < 0.03) null else ((atan2(hy, hx) * 180.0 / PI + 360.0) % 360.0).toFloat()
    return CoverReading(
        hue = hue,
        chroma = meanChroma.coerceIn(0f, 1f),
        luma = (luma / n).toFloat().coerceIn(0f, 1f),
        warm = if (weight > 0.0) (warm / weight).toFloat() else 0f
    )
}

/** Which world a cover belongs to. Deterministic: the same cover is always the same world. */
fun chooseWorld(r: CoverReading): WorldKind {
    val h = r.hue ?: return WorldKind.MINIMAL
    if (r.chroma < 0.06f) return WorldKind.MINIMAL
    // Dark and barely coloured: a moody cover is a night in the rain.
    if (r.chroma < 0.12f && r.luma < 0.30f) return WorldKind.NIGHT_RAIN
    if (r.warm > 0.55f) return WorldKind.EMBERS
    return when {
        h < 48f || h >= 345f -> WorldKind.EMBERS
        h < 160f -> WorldKind.FIREFLIES
        h < 250f -> if (r.luma < 0.20f) WorldKind.NIGHT_RAIN else WorldKind.OCEAN
        h < 295f -> if (r.luma > 0.55f) WorldKind.DREAM else WorldKind.COSMOS
        else -> if (r.luma < 0.25f) WorldKind.COSMOS else WorldKind.DREAM
    }
}

/**
 * How much of a world a tier draws: particle counts scale with it. Enhanced
 * runs on 4 GB phones, so it draws a little over half; a tier the frame
 * monitor has had to lighten (its light field taken off) draws less again.
 */
fun worldBudget(level: QualityLevel, relieved: Boolean): Float {
    val base = when (level) {
        QualityLevel.ENHANCED -> 0.60f
        QualityLevel.PREMIUM -> 0.85f
        QualityLevel.ULTRA -> 1.00f
    }
    return if (relieved) base * 0.6f else base
}

// ------------------------------------------------------------ colour --

/** The world's colours: [main] light, [second] light, [bright] highlights. ARGB, opaque. */
class WorldPalette(val main: Int, val second: Int, val bright: Int)

internal fun mixArgb(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 0xff) * (1f - k) + ((b shr s) and 0xff) * k).toInt().coerceIn(0, 255)
    return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private const val WHITE = -1 // 0xFFFFFFFF, opaque white

/**
 * The cover's colours, bent towards what each world is made of: fireflies
 * stay warm-green and embers stay orange whatever the cover, but both take
 * on its hue. A cover with no colour gives the world's own.
 */
fun worldPalette(kind: WorldKind, accent: Int?, glow: Int?, tones: IntArray): WorldPalette {
    val a = accent ?: glow ?: tones.firstOrNull()
    val second = tones.getOrNull(1) ?: glow ?: a
    return when (kind) {
        WorldKind.NIGHT_RAIN -> WorldPalette(
            main = a?.let { mixArgb(0xFF4F7CFF.toInt(), it, 0.5f) } ?: 0xFF4F7CFF.toInt(),
            second = second?.let { mixArgb(0xFFFFC46B.toInt(), it, 0.25f) } ?: 0xFFFFC46B.toInt(),
            bright = a?.let { mixArgb(0xFFCFE0FF.toInt(), it, 0.2f) } ?: 0xFFCFE0FF.toInt()
        )
        WorldKind.FIREFLIES -> WorldPalette(
            main = a?.let { mixArgb(0xFFD8FF6A.toInt(), it, 0.3f) } ?: 0xFFD8FF6A.toInt(),
            second = second?.let { mixArgb(0xFFFFF2B0.toInt(), it, 0.3f) } ?: 0xFFFFF2B0.toInt(),
            bright = 0xFFFFFDE8.toInt()
        )
        WorldKind.OCEAN -> {
            val m = a ?: 0xFF5FD4FF.toInt()
            WorldPalette(main = m, second = second ?: 0xFF2F6BFF.toInt(), bright = mixArgb(m, WHITE, 0.65f))
        }
        WorldKind.COSMOS -> WorldPalette(
            main = a ?: 0xFF8B6BFF.toInt(),
            second = second ?: 0xFF3FA8FF.toInt(),
            bright = a?.let { mixArgb(WHITE, it, 0.18f) } ?: 0xFFF2F4FF.toInt()
        )
        WorldKind.DREAM -> WorldPalette(
            main = a ?: 0xFFFF8AD8.toInt(),
            second = second ?: 0xFF8AB4FF.toInt(),
            bright = mixArgb(a ?: 0xFFFF8AD8.toInt(), WHITE, 0.7f)
        )
        WorldKind.EMBERS -> WorldPalette(
            main = a?.let { mixArgb(0xFFFFA040.toInt(), it, 0.35f) } ?: 0xFFFFA040.toInt(),
            second = second?.let { mixArgb(0xFFFF4A1C.toInt(), it, 0.3f) } ?: 0xFFFF4A1C.toInt(),
            bright = 0xFFFFE6B0.toInt()
        )
        WorldKind.MINIMAL -> WorldPalette(
            main = 0xFFE6E8EE.toInt(), second = 0xFF9CA3B4.toInt(), bright = WHITE
        )
    }
}

// ------------------------------------------------------------ signals --

/**
 * What the world hears, updated every frame by [hear]. Mutable so a frame
 * allocates nothing.
 */
class WorldSignal {
    /** 1 while playing, easing to 0 when paused: the world's own speed. */
    var energy = 0f
    /** Smoothed band levels, 0..1. */
    var bass = 0f
    var mid = 0f
    var hats = 0f
    /** The latest kick and snare as decaying pulses, 0..1. */
    var kick = 0f
    var snare = 0f
    /** Counters, so a world can start something once per hit. */
    var kicks = 0
    var snares = 0
    var kickPower = 0f
    var snarePower = 0f

    internal var nextMoment = 0
    internal var lastMs = Float.NaN

    fun hitKick(p: Float) {
        kicks++
        kickPower = p.coerceIn(0f, 1f)
        kick = max(kick, kickPower)
    }

    fun hitSnare(p: Float) {
        snares++
        snarePower = p.coerceIn(0f, 1f)
        snare = max(snare, snarePower)
    }

    /** Paused and settled: nothing is heard and nothing is still fading. */
    internal fun rest() {
        bass = 0f; mid = 0f; hats = 0f; kick = 0f; snare = 0f
    }

    /** Frame-rate independent smoothing: rises fast, falls slowly. */
    internal fun follow(dt: Float, bassT: Float, midT: Float, hatsT: Float) {
        bass = ease(bass, bassT, dt, 0.06f, 0.45f)
        mid = ease(mid, midT, dt, 0.08f, 0.40f)
        hats = ease(hats, hatsT, dt, 0.04f, 0.25f)
        kick *= exp(-dt / 0.16f)
        snare *= exp(-dt / 0.22f)
    }

    private fun ease(v: Float, target: Float, dt: Float, rise: Float, fall: Float): Float {
        val tau = if (target > v) rise else fall
        return v + (target.coerceIn(0f, 1f) - v) * (1f - exp(-dt / tau))
    }
}

/** How far ahead of the audible position to read: a frame reaches the screen about two vsyncs later. */
private const val WORLD_LEAD_MS = 30f

/**
 * One frame of listening. [clockMs] is the player's own position (what you
 * hear). Moments fire on the frame playback crosses them, as in
 * BeatPulse.kt; a seek or a resume after a while fires nothing skipped.
 * Silent, paused, or no analysis: nothing is heard.
 */
fun hear(
    sig: WorldSignal, shape: SoundShape?, clockMs: Long, volume: Float,
    playing: Boolean, speed: Float, dt: Float
) {
    val target = if (playing) 1f else 0f
    // Comes up in about half a second; settles over about two.
    val tau = if (target > sig.energy) 0.35f else 0.9f
    sig.energy += (target - sig.energy) * (1f - exp(-dt / tau))
    if (!playing && sig.energy < 0.004f) sig.energy = 0f
    var bassT = 0f
    var midT = 0f
    var hatsT = 0f
    val v = volume.coerceIn(0f, 1f)
    if (shape != null && playing && v > 0.01f) {
        val sp = speed.coerceAtLeast(0.1f)
        val ms = clockMs + WORLD_LEAD_MS * sp
        val pos = ms.toLong()
        bassT = shape.low.levelAt(pos) * v
        midT = shape.mid.levelAt(pos) * v
        hatsT = shape.high.levelAt(pos) * v
        val m = shape.moments
        val last = sig.lastMs
        if (last.isNaN() || ms < last - 100f || ms > last + 500f) {
            sig.nextMoment = m.firstAtOrAfter(ms)
        } else {
            var i = sig.nextMoment
            while (i < m.size && m.timeMs(i) <= ms) {
                val p = m.power[i] * v
                if (m.kick[i]) sig.hitKick(p) else sig.hitSnare(p)
                i++
            }
            sig.nextMoment = i
        }
        sig.lastMs = ms
    } else {
        sig.lastMs = Float.NaN
    }
    sig.follow(dt, bassT, midT, hatsT)
    if (!playing && sig.energy == 0f) sig.rest()
}

// ------------------------------------------------------------ drawing --

/**
 * Where the world is drawn: the screen, the cover in it, and where the
 * words start. Mutable, set every frame.
 */
class WorldFrame {
    var w = 1f
    var h = 1f
    /** Pixels per dp. */
    var density = 1f
    var coverCx = 0.5f
    var coverCy = 0.3f
    var coverSize = 1f
    /** Where the title starts: below this the world calms (veil). */
    var textTop = 1f
    /** The phone's tilt, -1..1 each way (Premium and Ultra); 0 otherwise. */
    var tiltX = 0f
    var tiltY = 0f

    val coverTop: Float get() = coverCy - coverSize / 2f
    val coverBottom: Float get() = coverCy + coverSize / 2f

    /**
     * Full strength above the words; down to [VEIL_FLOOR] over a fifth of
     * the screen below where they start, so the title and controls always
     * read.
     */
    fun veil(y: Float): Float {
        if (y <= textTop) return 1f
        val t = ((y - textTop) / (h * 0.20f)).coerceIn(0f, 1f)
        return 1f - t * (1f - VEIL_FLOOR)
    }

    companion object {
        const val VEIL_FLOOR = 0.30f
    }
}

/** What a world draws with. Compose on the phone, AWT in the tests. Colours are opaque ARGB; alpha separate. */
interface WorldPainter {
    /** Multiplies every alpha: the world's fade in and out. */
    var fade: Float
    /** A soft round light, brightest at the centre. Additive on a dark theme. */
    fun glow(x: Float, y: Float, r: Float, argb: Int, alpha: Float)
    fun dot(x: Float, y: Float, r: Float, argb: Int, alpha: Float)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, argb: Int, alpha: Float)
    fun ring(x: Float, y: Float, r: Float, width: Float, argb: Int, alpha: Float)
    fun oval(x: Float, y: Float, rx: Float, ry: Float, width: Float, argb: Int, alpha: Float)
    fun rect(left: Float, top: Float, right: Float, bottom: Float, argb: Int, alpha: Float)
    /** A full-width horizontal band, transparent at both edges, [alpha] in the middle. */
    fun band(top: Float, bottom: Float, argb: Int, alpha: Float)
    /** A quad (clockwise from top-left) shaded from [alphaTop] at its top to [alphaBottom] at its bottom. */
    fun quad(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        argb: Int, alphaTop: Float, alphaBottom: Float
    )
    /**
     * A ribbon of [n] columns at [xs]: from [tops] down to [bottoms],
     * transparent at its top edge, [alpha] at its bottom edge.
     */
    fun ribbon(n: Int, xs: FloatArray, tops: FloatArray, bottoms: FloatArray, argb: Int, alpha: Float)
}

/** One world: its particles and how they move and draw. */
abstract class WorldScene(val kind: WorldKind, seed: Long, budget: Float) {
    protected val rnd = java.util.Random(seed xor (kind.ordinal + 1).toLong() * -7046029254386353131L)
    private val scale = budget.coerceIn(0.2f, 1f)

    /** World time in seconds. Starts part-way in, so a still world is never an empty one. */
    var time: Float = 14f + rnd.nextFloat() * 30f
        private set

    protected fun count(base: Int): Int = max(4, (base * scale).toInt())
    protected fun r01(): Float = rnd.nextFloat()
    protected fun rIn(a: Float, b: Float): Float = a + (b - a) * rnd.nextFloat()

    /** [dt] is wall time; the world moves at [WorldSignal.energy] of it, so a pause settles it. */
    fun advance(dt: Float, s: WorldSignal) {
        val sim = dt * s.energy
        // Settled: the picture holds exactly, fades and all.
        if (sim <= 0f) return
        time += sim
        step(sim, dt, s)
    }

    protected abstract fun step(sim: Float, dt: Float, s: WorldSignal)
    abstract fun draw(p: WorldPainter, f: WorldFrame, s: WorldSignal, pal: WorldPalette)

    companion object {
        fun create(kind: WorldKind, seed: Long, budget: Float): WorldScene = when (kind) {
            WorldKind.NIGHT_RAIN -> NightRain(seed, budget)
            WorldKind.FIREFLIES -> Fireflies(seed, budget)
            WorldKind.OCEAN -> Ocean(seed, budget)
            WorldKind.COSMOS -> Cosmos(seed, budget)
            WorldKind.DREAM -> Dream(seed, budget)
            WorldKind.EMBERS -> Embers(seed, budget)
            WorldKind.MINIMAL -> Minimal(seed, budget)
        }
    }
}

private fun smooth01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

// ---------------------------------------------------------- night rain --

private class NightRain(seed: Long, budget: Float) : WorldScene(WorldKind.NIGHT_RAIN, seed, budget) {
    private val n = count(170)
    private val x = FloatArray(n) { r01() * 1.2f - 0.1f }
    private val y = FloatArray(n) { r01() * 1.1f - 0.05f }
    private val z = FloatArray(n) { 0.3f + 0.7f * r01() * r01() }
    private var gust = 0f
    private var seenKicks = 0
    private var seenSnares = 0

    // The skyline: made once, from the seed.
    private val bN = 15
    private val bLeft = FloatArray(bN)
    private val bWidth = FloatArray(bN)
    private val bHeight = FloatArray(bN)
    private val winCols = 3
    private val winRows = 7
    private val lit = BooleanArray(bN * winCols * winRows)

    init {
        var cx = -0.04f
        for (i in 0 until bN) {
            bLeft[i] = cx
            bWidth[i] = rIn(0.055f, 0.10f)
            bHeight[i] = rIn(0.07f, 0.21f)
            cx += bWidth[i] * rIn(0.82f, 1.0f)
        }
        for (i in lit.indices) lit[i] = r01() < 0.30f
    }

    override fun step(sim: Float, dt: Float, s: WorldSignal) {
        if (s.kicks != seenKicks) {
            seenKicks = s.kicks
            gust = min(0.45f, gust + 0.28f * s.kickPower)
        }
        if (s.snares != seenSnares) {
            seenSnares = s.snares
            // A snare switches a few windows: lights going on and off across the city.
            repeat(1 + (s.snarePower * 2.5f).toInt()) {
                val i = rnd.nextInt(lit.size)
                lit[i] = !lit[i]
            }
        }
        gust *= exp(-dt / 0.7f)
        val wind = 0.16f + gust
        for (i in 0 until n) {
            val speed = (0.85f + 0.95f * z[i]) * (1f + 0.25f * s.bass)
            y[i] += speed * sim
            x[i] += wind * speed * sim * 0.5f
            if (y[i] > 1.06f) {
                y[i] = -0.06f - r01() * 0.08f
                x[i] = r01() * 1.2f - 0.15f
            }
            if (x[i] > 1.15f) x[i] -= 1.3f
        }
    }

    override fun draw(p: WorldPainter, f: WorldFrame, s: WorldSignal, pal: WorldPalette) {
        val w = f.w
        val h = f.h
        val d = f.density
        val base = f.coverBottom + h * 0.06f
        // City glow behind the skyline: the cover's colour as neon haze.
        p.glow(f.coverCx, base - h * 0.04f, w * 0.75f, pal.main, 0.30f + 0.30f * s.bass)
        // The skyline, standing behind and around the cover.
        for (i in 0 until bN) {
            val l = bLeft[i] * w
            val r = l + bWidth[i] * w
            val t = base - bHeight[i] * h
            p.rect(l, t, r, base, 0xFF04060B.toInt(), 0.78f * f.veil(t))
            val cellW = (r - l) / (winCols + 1)
            val cellH = (base - t) / (winRows + 1)
            for (c in 0 until winCols) for (row in 0 until winRows) {
                if (!lit[(i * winCols + c) * winRows + row]) continue
                val wx = l + cellW * (c + 1)
                val wy = t + cellH * (row + 1)
                val a = (0.55f + 0.35f * s.snare) * f.veil(wy)
                p.rect(wx - 1.6f * d, wy - 2.2f * d, wx + 1.6f * d, wy + 2.2f * d, pal.second, a)
                // Its reflection in the wet street below.
                val ry = base + (base - wy) * 0.35f
                p.line(wx, ry, wx, ry + 6f * d, 1.4f * d, pal.second, a * 0.35f * f.veil(ry))
            }
        }
        p.band(base - h * 0.03f, base + h * 0.07f, pal.main, (0.22f + 0.20f * s.bass) * f.veil(base))
        // Rain, nearest drops longest and brightest.
        val wind = 0.16f + gust
        for (i in 0 until n) {
            val px = x[i] * w + f.tiltX * z[i] * 10f * d
            val py = y[i] * h
            val len = (12f + 30f * z[i]) * d
            val a = (0.16f + 0.46f * z[i]) * (0.85f + 0.30f * s.hats) * f.veil(py)
            p.line(px, py, px - wind * len * 0.5f, py - len, (0.7f + 1.3f * z[i]) * d, pal.bright, a)
        }
    }
}

// ----------------------------------------------------------- fireflies --

private class Fireflies(seed: Long, budget: Float) : WorldScene(WorldKind.FIREFLIES, seed, budget) {
    private val n = count(46)
    private val hx = FloatArray(n) { r01() }
    private val hy = FloatArray(n) { rIn(0.04f, 0.78f) }
    private val z = FloatArray(n) { 0.3f + 0.7f * r01() }
    private val f1 = FloatArray(n) { rIn(0.13f, 0.32f) }
    private val f2 = FloatArray(n) { rIn(0.21f, 0.47f) }
    private val ph = FloatArray(n) { r01() * 6.283f }
    private val blinkRate = FloatArray(n) { rIn(0.5f, 1.1f) }
    private val blinkPhase = FloatArray(n) { r01() * 6.283f }
    private val flare = FloatArray(n)
    private var blinkClock = 0f
    private var seenKicks = 0

    override fun step(sim: Float, dt: Float, s: WorldSignal) {
        // The hats quicken the blinking, never past twice the rate.
        blinkClock += sim * (1f + 1.0f * s.hats)
        if (s.kicks != seenKicks) {
            seenKicks = s.kicks
            for (i in 0 until n) flare[i] = max(flare[i], s.kickPower * (0.45f + 0.55f * z[i]))
        }
        val k = exp(-dt / 0.45f)
        for (i in 0 until n) flare[i] *= k
    }

    override fun draw(p: WorldPainter, f: WorldFrame, s: WorldSignal, pal: WorldPalette) {
        val w = f.w
        val h = f.h
        val d = f.density
        val t = time
        // Shafts of light through the canopy, swaying slowly.
        for (k in 0 until 4) {
            val sway = sin(t * 0.21f + k * 1.7f) * w * 0.03f
            val x0 = w * (-0.08f + 0.26f * k) + sway
            val top = -h * 0.04f
            val bottom = f.coverBottom + h * 0.10f
            val drift = w * 0.30f
            val a = (0.07f + 0.08f * s.bass) * (0.75f + 0.25f * sin(t * 0.37f + k))
            p.quad(
                x0, top, x0 + w * 0.05f, top,
                x0 + drift + w * 0.17f, bottom, x0 + drift, bottom,
                pal.second, a, 0f
            )
        }
        p.glow(f.coverCx, f.coverBottom + h * 0.05f, w * 0.65f, pal.main, 0.16f + 0.14f * s.bass)
        for (i in 0 until n) {
            val px = (hx[i] + 0.07f * sin(t * f1[i] + ph[i]) + 0.03f * sin(t * f2[i] * 2.3f + ph[i] * 1.7f)) * w +
                f.tiltX * z[i] * 14f * d
            var yy = hy[i] - (t * 0.004f * z[i]) % 1f
            if (yy < -0.02f) yy += 0.84f
            val py = (yy + 0.04f * sin(t * f2[i] + ph[i] * 0.6f)) * h + f.tiltY * z[i] * 10f * d
            val b = sin(blinkPhase[i] + blinkClock * blinkRate[i] * 2.4f)
            val on = smooth01((b - 0.15f) / 0.85f)
            val lv = min(1f, 0.10f + 0.90f * on + flare[i])
            val v = f.veil(py)
            val r = (12f + 14f * z[i]) * d * (1f + 0.6f * flare[i])
            p.glow(px, py, r, pal.main, lv * 0.60f * v)
            p.dot(px, py, (1.3f + 1.2f * z[i]) * d, pal.bright, lv * 0.95f * v)
        }
    }
}

// --------------------------------------------------------------- ocean --

private class Ocean(seed: Long, budget: Float) : WorldScene(WorldKind.OCEAN, seed, budget) {
    private val glints = count(80)
    private val gx = FloatArray(glints) { (rnd.nextGaussian() * 0.45).toFloat() }
    private val gu = FloatArray(glints) { r01() }
    private val gph = FloatArray(glints) { r01() * 6.283f }
    private val rippleAge = FloatArray(4) { 9f }
    private val ripplePow = FloatArray(4)
    private val rippleX = FloatArray(4)
    private var nextRipple = 0
    private var seenKicks = 0
    private val stars = count(40)
    private val sx = FloatArray(stars) { r01() }
    private val sy = FloatArray(stars) { r01() }
    private val sph = FloatArray(stars) { r01() * 6.283f }

    override fun step(sim: Float, dt: Float, s: WorldSignal) {
        if (s.kicks != seenKicks) {
            seenKicks = s.kicks
            rippleAge[nextRipple] = 0f
            ripplePow[nextRipple] = s.kickPower
            rippleX[nextRipple] = rIn(-0.12f, 0.12f)
            nextRipple = (nextRipple + 1) % rippleAge.size
        }
        for (i in rippleAge.indices) rippleAge[i] += dt
    }

    override fun draw(p: WorldPainter, f: WorldFrame, s: WorldSignal, pal: WorldPalette) {
        val w = f.w
        val h = f.h
        val d = f.density
        val t = time
        val hy = f.coverBottom + h * 0.035f
        val deep = h - hy
        // A few stars in the sky above the horizon.
        for (i in 0 until stars) {
            val py = sy[i] * (hy - h * 0.02f)
            val a = (0.25f + 0.35f * sin(sph[i] + t * 1.3f)).coerceAtLeast(0f) * f.veil(py)
            p.dot(sx[i] * w, py, 0.9f * d, pal.bright, a)
        }
        // The horizon: the cover's light where sky meets water.
        p.band(hy - h * 0.06f, hy + h * 0.05f, pal.main, 0.34f + 0.20f * s.bass)
        p.glow(f.coverCx, hy + deep * 0.12f, f.coverSize * 0.75f, pal.main, 0.30f + 0.22f * s.bass)
        // Swell lines, closer ones lower, longer and stronger; the bass lifts them.
        val lines = 9
        val segs = 28
        for (k in 0 until lines) {
            val u = (k + 1f) / lines
            val y0 = hy + deep * 0.62f * u.pow(1.6f)
            val amp = (1.5f + 9f * u) * d * (0.6f + 1.3f * s.bass)
            val wave = w * (0.16f + 0.45f * u)
            val phase = t * (0.5f + 0.25f * k) + k * 1.3f
            val a = (0.10f + 0.26f * u) * f.veil(y0)
            var lx = 0f
            var ly = y0 + amp * sin(phase)
            for (j in 1..segs) {
                val nx = w * j / segs
                val ny = y0 + amp * sin(nx / wave * 6.283f + phase)
                p.line(lx, ly, nx, ny, (0.8f + 1.6f * u) * d, pal.bright, a)
                lx = nx
                ly = ny
            }
        }
        // The moon path: the cover's light glinting on the water below it.
        for (i in 0 until glints) {
            val u = gu[i]
            val py = hy + deep * 0.6f * u.pow(1.4f)
            val spread = f.coverSize * (0.18f + 0.55f * u)
            val px = f.coverCx + gx[i] * spread + sin(t * 0.8f + gph[i]) * 4f * d
            val tw = 0.5f + 0.5f * sin(gph[i] + t * (2.0f + 2.5f * s.hats))
            val len = (5f + 20f * u) * d * (0.4f + 0.6f * tw)
            val a = (0.18f + 0.55f * tw * (0.5f + s.hats)) * (1f - 0.5f * u) * f.veil(py)
            p.line(px - len / 2f, py, px + len / 2f, py, (1.0f + 1.4f * u) * d, pal.bright, a.coerceAtMost(0.9f))
        }
        // A ripple from each kick, spreading across the water.
        for (i in rippleAge.indices) {
            val age = rippleAge[i]
            if (age > 2.4f) continue
            val k = age / 2.4f
            val rx = w * (0.08f + 0.62f * (1f - (1f - k) * (1f - k)))
            val cy = hy + deep * 0.14f
            val a = (1f - k) * (1f - k) * (0.30f + 0.55f * ripplePow[i]) * f.veil(cy)
            p.oval(f.coverCx + rippleX[i] * w, cy, rx, rx * 0.16f, (1.2f + 1.4f * (1f - k)) * d, pal.bright, a)
        }
    }
}

// -------------------------------------------------------------- cosmos --

private class Cosmos(seed: Long, budget: Float) : WorldScene(WorldKind.COSMOS, seed, budget) {
    private val n = count(160)
    private val sx = FloatArray(n) { r01() * 1.6f - 0.3f }
    private val sy = FloatArray(n) { r01() * 1.6f - 0.3f }
    private val sz = FloatArray(n) { 0.15f + 0.85f * r01() * r01() }
    private val tw = FloatArray(n) { r01() * 6.283f }
    private val orbit = count(44)
    private val oa = FloatArray(orbit) { r01() * 6.283f }
    private val orbR = FloatArray(orbit) { rIn(0.92f, 1.22f) }
    private val os = FloatArray(orbit) { rIn(0.10f, 0.22f) }
    private var warp = 0f
    private var seenKicks = 0

    override fun step(sim: Float, dt: Float, s: WorldSignal) {
        if (s.kicks != seenKicks) {
            seenKicks = s.kicks
            warp = max(warp, s.kickPower)
        }
        warp *= exp(-dt / 0.22f)
        for (i in 0 until orbit) oa[i] += sim * os[i] * (1f + 2.0f * s.kick) * (1.25f - orbR[i] * 0.25f)
    }

    override fun draw(p: WorldPainter, f: WorldFrame, s: WorldSignal, pal: WorldPalette) {
        val w = f.w
        val h = f.h
        val d = f.density
        val t = time
        val cx = f.coverCx
        val cy = f.coverCy
        // Nebulae in the cover's colours, drifting and swelling with the bass.
        for (k in 0 until 3) {
            val nx = cx + w * (0.32f * sin(t * 0.031f + k * 2.1f))
            val ny = cy + h * (0.16f * cos(t * 0.027f + k * 1.4f)) + (k - 1) * h * 0.06f
            val col = if (k == 1) pal.second else pal.main
            p.glow(nx, ny, w * (0.55f + 0.12f * k), col, (0.20f + 0.20f * s.bass) * f.veil(ny))
        }
        // The star field turns slowly around the cover; nearer stars turn faster.
        for (i in 0 until n) {
            val z = sz[i]
            val a0 = t * 0.018f * (0.4f + z)
            val dx0 = (sx[i] - 0.5f) * w
            val dy0 = (sy[i] - 0.5f) * h
            val ca = cos(a0)
            val sa = sin(a0)
            var px = cx + dx0 * ca - dy0 * sa + f.tiltX * z * 16f * d
            var py = cy + dx0 * sa + dy0 * ca + f.tiltY * z * 12f * d
            if (px < -8f || px > w + 8f || py < -8f || py > h + 8f) continue
            val twinkle = 0.55f + 0.45f * sin(tw[i] + t * (1.2f + 3.5f * z))
            val sparkle = if (i % 3 == 0) s.hats * 0.7f else 0f
            val a = min(1f, (0.25f + 0.75f * z) * twinkle + sparkle) * f.veil(py)
            val size = (0.5f + 1.5f * z) * d
            // The kick warps space: stars streak outwards, nearest furthest.
            val push = warp * z * 26f * d
            if (push > 1.5f) {
                val ddx = px - cx
                val ddy = py - cy
                val len = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(1f)
                val ex = px + ddx / len * push
                val ey = py + ddy / len * push
                p.line(px, py, ex, ey, size * 1.2f, pal.bright, a)
                px = ex
                py = ey
            } else {
                p.dot(px, py, size, pal.bright, a)
            }
            if (z > 0.82f) p.glow(px, py, size * 6f, pal.main, a * 0.45f)
        }
        // A ring of light orbiting the cover, its near side brighter.
        val rx = f.coverSize * 0.66f
        val ry = rx * 0.24f
        val ringY = cy + f.coverSize * 0.40f
        p.oval(cx, ringY, rx, ry, 1.2f * d, pal.main, (0.30f + 0.30f * s.bass) * f.veil(ringY))
        for (i in 0 until orbit) {
            val px = cx + cos(oa[i]) * rx * orbR[i]
            val py = ringY + sin(oa[i]) * ry * orbR[i]
            val near = 0.55f + 0.45f * sin(oa[i])
            val a = (0.40f + 0.45f * near + 0.3f * s.kick).coerceAtMost(1f) * f.veil(py)
            p.glow(px, py, (5f + 6f * near) * d, pal.main, a * 0.7f)
            p.dot(px, py, (0.9f + 1.1f * near) * d, pal.bright, a)
        }
    }
}

// --------------------------------------------------------------- dream --

private class Dream(seed: Long, budget: Float) : WorldScene(WorldKind.DREAM, seed, budget) {
    private val n = count(26)
    private val bx = FloatArray(n) { r01() }
    private val by = FloatArray(n) { r01() }
    private val br = FloatArray(n) { rIn(16f, 62f) }
    private val bs = FloatArray(n) { rIn(0.008f, 0.026f) }
    private val bc = BooleanArray(n) { r01() < 0.5f }
    private val dust = count(70)
    private val dx = FloatArray(dust) { r01() }
    private val dy = FloatArray(dust) { r01() }
    private val dph = FloatArray(dust) { r01() * 6.283f }
    private val cols = 44
    private val xs = FloatArray(cols)
    private val tops = FloatArray(cols)
    private val bottoms = FloatArray(cols)
    private var swell = 0f
    private var seenKicks = 0

    override fun step(sim: Float, dt: Float, s: WorldSignal) {
        if (s.kicks != seenKicks) {
            seenKicks = s.kicks
            swell = max(swell, s.kickPower)
        }
        swell *= exp(-dt / 0.30f)
        for (i in 0 until n) {
            by[i] -= bs[i] * sim
            if (by[i] < -0.08f) {
                by[i] = 1.08f
                bx[i] = r01()
            }
        }
    }

    override fun draw(p: WorldPainter, f: WorldFrame, s: WorldSignal, pal: WorldPalette) {
        val w = f.w
        val h = f.h
        val d = f.density
        val t = time
        // Two aurora ribbons behind the cover; the bass lifts their swell.
        for (k in 0 until 2) {
            val baseY = f.coverTop + f.coverSize * (0.20f + 0.30f * k)
            val amp = (16f + 34f * s.bass) * d
            val thick = (70f + 40f * k) * d
            for (j in 0 until cols) {
                val x = w * j / (cols - 1)
                val y = baseY +
                    amp * sin(x / w * 5.2f + t * 0.42f + k * 2.1f) +
                    amp * 0.5f * sin(x / w * 11.0f - t * 0.31f + k)
                xs[j] = x
                bottoms[j] = y
                tops[j] = y - thick * (0.75f + 0.25f * sin(x / w * 7f + t * 0.5f))
            }
            val col = if (k == 0) pal.main else pal.second
            p.ribbon(cols, xs, tops, bottoms, col, (0.34f + 0.22f * s.bass) * f.veil(baseY))
            // Curtain rays rising from the ribbon, glinting with the hats.
            var j = 1
            while (j < cols - 1) {
                val a = (0.10f + 0.20f * s.hats) * (0.5f + 0.5f * sin(j * 1.7f + t))
                p.line(xs[j], bottoms[j], xs[j], tops[j] - thick * 0.4f, 1.5f * d, pal.bright, a * f.veil(bottoms[j]))
                j += 3
            }
        }
        // Bokeh drifting up; the kick swells it.
        for (i in 0 until n) {
            val px = (bx[i] + 0.03f * sin(t * 0.2f + i)) * w + f.tiltX * 12f * d
            val py = by[i] * h
            val r = br[i] * d * (1f + 0.22f * swell)
            val a = (0.16f + 0.16f * (br[i] / 62f)) * (1f + 0.5f * s.bass) * f.veil(py)
            p.glow(px, py, r, if (bc[i]) pal.main else pal.second, a.coerceAtMost(0.6f))
        }
        for (i in 0 until dust) {
            val px = (dx[i] + 0.02f * sin(t * 0.3f + dph[i])) * w
            val py = ((dy[i] - t * 0.006f) % 1f + 1f) % 1f * h
            val a = (0.25f + 0.35f * sin(dph[i] + t * 1.7f)).coerceAtLeast(0f) * f.veil(py)
            p.dot(px, py, 0.9f * d, pal.bright, a)
        }
    }
}

// -------------------------------------------------------------- embers --

private class Embers(seed: Long, budget: Float) : WorldScene(WorldKind.EMBERS, seed, budget) {
    private val n = count(100)
    private val x = FloatArray(n) { r01() }
    private val y = FloatArray(n) { rIn(0.1f, 1.05f) }
    private val vy = FloatArray(n) { rIn(0.06f, 0.20f) }
    private val life = FloatArray(n) { r01() }
    private val rate = FloatArray(n) { rIn(0.10f, 0.28f) }
    private val size = FloatArray(n) { rIn(0.8f, 2.4f) }
    private val ph = FloatArray(n) { r01() * 6.283f }
    private var lift = 0f
    private var seenKicks = 0

    override fun step(sim: Float, dt: Float, s: WorldSignal) {
        if (s.kicks != seenKicks) {
            seenKicks = s.kicks
            lift = max(lift, s.kickPower)
        }
        lift *= exp(-dt / 0.35f)
        for (i in 0 until n) {
            y[i] -= vy[i] * sim * (1f + 2.2f * lift)
            x[i] += 0.025f * sin(time * 1.3f + ph[i]) * sim
            life[i] += rate[i] * sim
            if (y[i] < -0.04f || life[i] > 1f) {
                y[i] = rIn(0.82f, 1.06f)
                x[i] = r01()
                life[i] = 0f
            }
        }
    }

    override fun draw(p: WorldPainter, f: WorldFrame, s: WorldSignal, pal: WorldPalette) {
        val w = f.w
        val h = f.h
        val d = f.density
        val t = time
        // The heat below.
        p.glow(f.coverCx, h * 1.02f, w * 0.95f, pal.second, 0.30f + 0.25f * s.bass)
        p.glow(f.coverCx, f.coverBottom, f.coverSize * 0.9f, pal.main, 0.12f + 0.16f * s.bass)
        for (i in 0 until n) {
            val l = life[i]
            val px = x[i] * w + f.tiltX * 10f * d
            val py = y[i] * h
            val flicker = 0.6f + 0.4f * sin(t * 9f + ph[i])
            val a = (1f - l).pow(1.2f) * flicker * (0.55f + 0.45f * lift) * f.veil(py)
            if (a < 0.01f) continue
            val r = size[i] * d * (1f - 0.5f * l)
            val col = if (l < 0.5f) pal.main else pal.second
            p.glow(px, py, r * 5.5f, col, a * 0.55f)
            p.dot(px, py, r, pal.bright, min(1f, a * 1.1f))
        }
    }
}

// ------------------------------------------------------------- minimal --

private class Minimal(seed: Long, budget: Float) : WorldScene(WorldKind.MINIMAL, seed, budget) {
    private val dust = count(70)
    private val dx = FloatArray(dust) { r01() }
    private val dy = FloatArray(dust) { r01() }
    private val dph = FloatArray(dust) { r01() * 6.283f }
    private val pulseAge = FloatArray(3) { 9f }
    private val pulsePow = FloatArray(3)
    private var next = 0
    private var seenKicks = 0

    override fun step(sim: Float, dt: Float, s: WorldSignal) {
        if (s.kicks != seenKicks) {
            seenKicks = s.kicks
            pulseAge[next] = 0f
            pulsePow[next] = s.kickPower
            next = (next + 1) % pulseAge.size
        }
        for (i in pulseAge.indices) pulseAge[i] += dt
    }

    override fun draw(p: WorldPainter, f: WorldFrame, s: WorldSignal, pal: WorldPalette) {
        val w = f.w
        val h = f.h
        val d = f.density
        val t = time
        val hy = f.coverBottom + h * 0.04f
        val deep = h - hy
        // The horizon line, lit by the snare.
        p.line(0f, hy, w, hy, 1.2f * d, pal.main, (0.40f + 0.45f * s.snare) * f.veil(hy))
        p.band(hy - h * 0.03f, hy + h * 0.03f, pal.main, 0.12f + 0.10f * s.bass)
        // A floor in perspective: lines running out from under the cover.
        val rays = 11
        for (k in 0 until rays) {
            val u = k / (rays - 1f) - 0.5f
            p.line(f.coverCx + u * w * 0.35f, hy, f.coverCx + u * w * 2.4f, h, 1f * d, pal.second, 0.20f * f.veil(hy))
        }
        // Floor lines: the slow drift of the floor towards you.
        val rows = 7
        val scroll = (t * 0.09f) % 1f
        for (k in 0 until rows) {
            val u = ((k + scroll) / rows)
            val y = hy + deep * u * u
            p.line(0f, y, w, y, (0.6f + 1.2f * u) * d, pal.second, (0.10f + 0.22f * u) * f.veil(y))
        }
        // Each kick: a line of light running out across the floor.
        for (i in pulseAge.indices) {
            val age = pulseAge[i]
            if (age > 1.2f) continue
            val k = age / 1.2f
            val y = hy + deep * k * k
            val a = (1f - k) * (0.35f + 0.6f * pulsePow[i]) * f.veil(y)
            p.line(0f, y, w, y, (1.2f + 2.5f * k) * d, pal.bright, a)
        }
        for (i in 0 until dust) {
            val px = (dx[i] + 0.015f * sin(t * 0.21f + dph[i])) * w
            val py = ((dy[i] + t * 0.004f) % 1f) * h
            val a = (0.18f + 0.30f * sin(dph[i] + t * 0.9f)).coerceAtLeast(0f) * f.veil(py)
            p.dot(px, py, 0.8f * d, pal.bright, a)
        }
    }
}
