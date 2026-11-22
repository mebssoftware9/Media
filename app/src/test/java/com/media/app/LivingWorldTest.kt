package com.media.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivingWorldTest {

    // ------------------------------------------------------------ choosing --

    @Test
    fun eachSyntheticCoverChoosesItsOwnWorld() {
        for (kind in WorldKind.entries) {
            val reading = readCover(WorldStage.cover(kind))
            assertEquals("cover made for $kind", kind, chooseWorld(reading))
        }
    }

    @Test
    fun greyCoverHasNoHueAndIsMinimal() {
        val grey = IntArray(64 * 64) { i -> val v = 40 + (i % 64) * 3; (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        val r = readCover(grey)
        assertEquals(null, r.hue)
        assertEquals(WorldKind.MINIMAL, chooseWorld(r))
    }

    @Test
    fun emptyCoverIsMinimal() {
        assertEquals(WorldKind.MINIMAL, chooseWorld(readCover(IntArray(0))))
    }

    @Test
    fun modeNamesRoundTripAndUnknownIsAuto() {
        for (m in WorldMode.entries) assertEquals(m, WorldMode.fromName(m.name))
        assertEquals(WorldMode.AUTO, WorldMode.fromName(null))
        assertEquals(WorldMode.AUTO, WorldMode.fromName("SOMETHING_ELSE"))
        assertEquals(null, WorldMode.AUTO.kind)
        assertEquals(null, WorldMode.OFF.kind)
    }

    @Test
    fun budgetGrowsWithTierAndShrinksWhenRelieved() {
        val e = worldBudget(QualityLevel.ENHANCED, false)
        val p = worldBudget(QualityLevel.PREMIUM, false)
        val u = worldBudget(QualityLevel.ULTRA, false)
        assertTrue(e < p && p < u)
        assertTrue(worldBudget(QualityLevel.ULTRA, true) < u)
    }

    // ------------------------------------------------------------ motion --

    private class Recorder : WorldPainter {
        override var fade = 1f
        var calls = 0
        var hash = 17L
        var bad: String? = null
        private fun take(name: String, vararg v: Float) {
            calls++
            for (x in v) {
                if (!x.isFinite()) bad = "$name got $x"
                hash = hash * 31 + x.toRawBits()
            }
        }
        private fun alpha(name: String, a: Float) {
            if (a < -1e-4f || a > 1.2f) bad = "$name alpha $a"
        }
        override fun glow(x: Float, y: Float, r: Float, argb: Int, alpha: Float) { take("glow", x, y, r, alpha); alpha("glow", alpha) }
        override fun dot(x: Float, y: Float, r: Float, argb: Int, alpha: Float) { take("dot", x, y, r, alpha); alpha("dot", alpha) }
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, argb: Int, alpha: Float) { take("line", x0, y0, x1, y1, width, alpha); alpha("line", alpha) }
        override fun ring(x: Float, y: Float, r: Float, width: Float, argb: Int, alpha: Float) { take("ring", x, y, r, width, alpha); alpha("ring", alpha) }
        override fun oval(x: Float, y: Float, rx: Float, ry: Float, width: Float, argb: Int, alpha: Float) { take("oval", x, y, rx, ry, width, alpha); alpha("oval", alpha) }
        override fun rect(left: Float, top: Float, right: Float, bottom: Float, argb: Int, alpha: Float) { take("rect", left, top, right, bottom, alpha); alpha("rect", alpha) }
        override fun band(top: Float, bottom: Float, argb: Int, alpha: Float) { take("band", top, bottom, alpha); alpha("band", alpha) }
        override fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, argb: Int, alphaTop: Float, alphaBottom: Float) {
            take("quad", x0, y0, x1, y1, x2, y2, x3, y3, alphaTop, alphaBottom); alpha("quad", alphaTop); alpha("quad", alphaBottom)
        }
        override fun ribbon(n: Int, xs: FloatArray, tops: FloatArray, bottoms: FloatArray, argb: Int, alpha: Float) {
            for (i in 0 until n) take("ribbon", xs[i], tops[i], bottoms[i])
            alpha("ribbon", alpha)
        }
    }

    private fun frame() = WorldFrame().also { WorldStage.Layout(1080, 2400).frame(it) }

    @Test
    fun everyWorldStaysFiniteAndBoundedThroughAMinuteOfMusic() {
        for (kind in WorldKind.entries) {
            val scene = WorldScene.create(kind, 7L, 1f)
            val sig = WorldSignal()
            val groove = WorldStage.Groove()
            val pal = worldPalette(kind, null, null, IntArray(0))
            val f = frame()
            repeat(60 * 60) { i ->
                groove.step(sig, 1f / 60f)
                scene.advance(1f / 60f, sig)
                if (i % 30 == 0) {
                    val r = Recorder()
                    scene.draw(r, f, sig, pal)
                    assertEquals("$kind: ${r.bad}", null, r.bad)
                    assertTrue("$kind draws something", r.calls > 20)
                }
            }
        }
    }

    @Test
    fun sameSongSameWorld() {
        for (kind in WorldKind.entries) {
            fun run(seed: Long): Long {
                val scene = WorldScene.create(kind, seed, 1f)
                val sig = WorldSignal()
                val groove = WorldStage.Groove()
                repeat(240) { groove.step(sig, 1f / 60f); scene.advance(1f / 60f, sig) }
                return Recorder().also { scene.draw(it, frame(), sig, worldPalette(kind, null, null, IntArray(0))) }.hash
            }
            assertEquals("$kind is deterministic", run(99L), run(99L))
            assertNotEquals("$kind differs per song", run(99L), run(100L))
        }
    }

    @Test
    fun pausedWorldSettlesIntoAStillPicture() {
        for (kind in WorldKind.entries) {
            val scene = WorldScene.create(kind, 3L, 1f)
            val sig = WorldSignal()
            val groove = WorldStage.Groove()
            repeat(180) { groove.step(sig, 1f / 60f); scene.advance(1f / 60f, sig) }
            assertTrue(sig.energy > 0.9f)
            // Pause: seven seconds to settle.
            repeat(420) { groove.step(sig, 1f / 60f, playing = false); scene.advance(1f / 60f, sig) }
            assertEquals("$kind settled", 0f, sig.energy, 0f)
            val pal = worldPalette(kind, null, null, IntArray(0))
            val a = Recorder().also { scene.draw(it, frame(), sig, pal) }.hash
            repeat(60) { groove.step(sig, 1f / 60f, playing = false); scene.advance(1f / 60f, sig) }
            val b = Recorder().also { scene.draw(it, frame(), sig, pal) }.hash
            assertEquals("$kind is still when paused", a, b)
        }
    }

    // ------------------------------------------------------------ hearing --

    /** Ten seconds at 120 BPM: a kick every half second, snares between, hats throughout. */
    private fun groove(): SoundShape {
        val n = SoundShape.HZ * 10
        val low = FloatArray(n)
        val mid = FloatArray(n)
        val high = FloatArray(n) { 0.3f + 0.2f * ((it % 6) / 6f) }
        for (i in 0 until n) {
            val k = i % 25
            low[i] = if (k < 6) 1f - k / 6f else 0.05f
            val s = (i + 12) % 50
            mid[i] = if (s < 4) 1f - s / 4f else 0.08f
        }
        return SoundShape(low, mid, high)
    }

    @Test
    fun hearsKicksFromTheTracksOwnAnalysis() {
        val shape = groove()
        val sig = WorldSignal()
        var ms = 0L
        repeat(60 * 8) {
            ms += 16
            hear(sig, shape, ms, volume = 1f, playing = true, speed = 1f, dt = 1f / 60f)
        }
        assertTrue("hits heard: ${sig.kicks} kicks, ${sig.snares} snares", sig.kicks >= 4 && sig.kicks + sig.snares >= 8)
        assertTrue(sig.energy > 0.95f)
    }

    @Test
    fun silenceAndNoAnalysisHearNothing() {
        val shape = groove()
        val muted = WorldSignal()
        val none = WorldSignal()
        var ms = 0L
        repeat(60 * 8) {
            ms += 16
            hear(muted, shape, ms, volume = 0f, playing = true, speed = 1f, dt = 1f / 60f)
            hear(none, null, ms, volume = 1f, playing = true, speed = 1f, dt = 1f / 60f)
        }
        assertEquals(0, muted.kicks + muted.snares)
        assertEquals(0, none.kicks + none.snares)
        assertEquals(0f, muted.bass, 1e-6f)
        // Still moving on its own: playing means the world runs, calmly.
        assertTrue(none.energy > 0.95f)
    }

    @Test
    fun aSeekFiresNothingSkipped() {
        val shape = groove()
        val sig = WorldSignal()
        hear(sig, shape, 1000, 1f, true, 1f, 1f / 60f)
        val before = sig.kicks
        hear(sig, shape, 9000, 1f, true, 1f, 1f / 60f)
        assertEquals(before, sig.kicks)
    }

    // ------------------------------------------------------------ visible --

    /**
     * The point of the feature: you can see it. Each world must visibly
     * change a real share of the screen against Via's own backdrop - the
     * 6.0.x attempt drew layers at 2.5-5% opacity in the cover's own colour,
     * which changed close to nothing. Measured over the whole 540 x 1200
     * stand-in Now Playing, cover and controls included.
     */
    @Test
    fun everyWorldIsClearlyVisible() {
        for (kind in WorldKind.entries) {
            val plain = WorldStage.render(kind, 540, 1200, 6.03f, withWorld = false)
            val lit = WorldStage.render(kind, 540, 1200, 6.03f, withWorld = true)
            var changed = 0
            for (y in 0 until 1200) for (x in 0 until 540) {
                if (lumaDiff(plain.getRGB(x, y), lit.getRGB(x, y)) > 0.03f) changed++
            }
            val share = changed / (540f * 1200f)
            println("Living Worlds visibility: $kind changes ${"%.1f".format(share * 100)}% of the screen")
            assertTrue("$kind changes only ${share * 100}% of the screen", share > 0.01f)
        }
    }

    private fun lumaDiff(a: Int, b: Int): Float {
        fun l(c: Int) = (0.2126f * ((c shr 16) and 0xff) + 0.7152f * ((c shr 8) and 0xff) + 0.0722f * (c and 0xff)) / 255f
        return kotlin.math.abs(l(a) - l(b))
    }
}
