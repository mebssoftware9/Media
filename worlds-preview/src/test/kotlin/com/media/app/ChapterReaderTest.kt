package com.media.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Chapters from hand-built files: ID3 CHAP, Nero chpl and a QuickTime text track. */
class ChapterReaderTest {

    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be64(v: Long) = be32(v ushr 32) + be32(v and 0xFFFFFFFFL)
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun syncsafe(v: Int) = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(), ((v ushr 14) and 0x7F).toByte(),
        ((v ushr 7) and 0x7F).toByte(), (v and 0x7F).toByte()
    )
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun frame24(id: String, body: ByteArray) = ascii(id) + syncsafe(body.size) + byteArrayOf(0, 0) + body

    private fun chap(id: String, startMs: Long, title: String): ByteArray {
        val tit2 = frame24("TIT2", byteArrayOf(3) + title.toByteArray(Charsets.UTF_8))
        val body = ascii(id) + byteArrayOf(0) + be32(startMs) + be32(0) + be32(0xFFFFFFFFL) + be32(0xFFFFFFFFL) + tit2
        return frame24("CHAP", body)
    }

    private fun mp3(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b }
        return ascii("ID3") + byteArrayOf(4, 0, 0) + syncsafe(body.size) + body + ByteArray(64)
    }

    private fun atom(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { a, b -> a + b }
        return be32(8L + body.size) + ascii(type) + body
    }

    @Test
    fun readsId3Chapters() {
        val file = mp3(
            frame24("TIT2", byteArrayOf(3) + "Book".toByteArray()),
            chap("ch1", 0, "Opening"),
            chap("ch2", 61_000, "The Road"),
            chap("ch3", 3_725_500, "Ünïcode end")
        )
        val got = ChapterReader.read(ArraySource(file))
        assertEquals(listOf(0L, 61_000L, 3_725_500L), got.map { it.startMs })
        assertEquals(listOf("Opening", "The Road", "Ünïcode end"), got.map { it.title })
    }

    @Test
    fun readsNeroChapters() {
        fun entry(ms: Long, t: String): ByteArray {
            val b = t.toByteArray(Charsets.UTF_8)
            return be64(ms * 10_000L) + byteArrayOf(b.size.toByte()) + b
        }
        val chpl = atom("chpl", byteArrayOf(1, 0, 0, 0) + be32(0) + byteArrayOf(3) +
            entry(0, "One") + entry(90_000, "Two") + entry(200_000, "Three"))
        val file = atom("ftyp", ascii("M4B ")) + atom("moov", atom("udta", chpl)) + atom("mdat", ByteArray(32))
        val got = ChapterReader.read(ArraySource(file))
        assertEquals(listOf(0L, 90_000L, 200_000L), got.map { it.startMs })
        assertEquals(listOf("One", "Two", "Three"), got.map { it.title })
    }

    @Test
    fun readsQuickTimeChapterTrack() {
        // Samples: 2-byte length + text, one per chapter, all in one chunk.
        val titles = listOf("Prologue", "Part One", "Part Two")
        val samples = titles.map { be16(it.length) + ascii(it) }
        val ftyp = atom("ftyp", ascii("M4A "))
        fun build(mdatAt: Long): ByteArray {
            val timescale = 1000L
            val tkhd = atom("tkhd", ByteArray(4) + be32(0) + be32(0) + be32(2) + ByteArray(8))
            val mdhd = atom("mdhd", ByteArray(4) + be32(0) + be32(0) + be32(timescale) + be32(0) + ByteArray(4))
            val hdlr = atom("hdlr", ByteArray(4) + ByteArray(4) + ascii("text") + ByteArray(12))
            val stts = atom("stts", ByteArray(4) + be32(2) + be32(1) + be32(45_000) + be32(2) + be32(120_000))
            val stsz = atom("stsz", ByteArray(4) + be32(0) + be32(3) + samples.fold(ByteArray(0)) { a, s -> a + be32(s.size.toLong()) })
            val stsc = atom("stsc", ByteArray(4) + be32(1) + be32(1) + be32(3) + be32(1))
            val stco = atom("stco", ByteArray(4) + be32(1) + be32(mdatAt + 8))
            val stbl = atom("stbl", stts, stsz, stsc, stco)
            val text = atom("trak", tkhd, atom("mdia", mdhd, hdlr, atom("minf", stbl)))
            val audioTkhd = atom("tkhd", ByteArray(4) + be32(0) + be32(0) + be32(1) + ByteArray(8))
            val audio = atom("trak", audioTkhd, atom("tref", atom("chap", be32(2))))
            return ftyp + atom("moov", audio, text)
        }
        val head = build(0)
        val withOffsets = build(head.size.toLong())
        val out = ByteArrayOutputStream()
        out.write(withOffsets)
        out.write(atom("mdat", *samples.toTypedArray()))
        val got = ChapterReader.read(ArraySource(out.toByteArray()))
        assertEquals(listOf(0L, 45_000L, 165_000L), got.map { it.startMs })
        assertEquals(titles, got.map { it.title })
    }

    @Test
    fun noChaptersIsEmptyAndNeverThrows() {
        assertTrue(ChapterReader.read(ArraySource(mp3(frame24("TIT2", byteArrayOf(3) + "x".toByteArray())))).isEmpty())
        assertTrue(ChapterReader.read(ArraySource(ByteArray(0))).isEmpty())
        assertTrue(ChapterReader.read(ArraySource(ascii("ID3") + byteArrayOf(4, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F))).isEmpty())
        // One chapter alone is not a chapter list.
        assertTrue(ChapterReader.read(ArraySource(mp3(chap("a", 0, "Only")))).isEmpty())
    }

    @Test
    fun indexAtPosition() {
        val c = listOf(Chapter(0, "a"), Chapter(10_000, "b"), Chapter(20_000, "c"))
        assertEquals(0, chapterIndexAt(c, 0))
        assertEquals(0, chapterIndexAt(c, 9_999))
        assertEquals(1, chapterIndexAt(c, 10_000))
        assertEquals(2, chapterIndexAt(c, 99_000))
        assertEquals(-1, chapterIndexAt(listOf(Chapter(5_000, "x"), Chapter(6_000, "y")), 100))
    }
}
