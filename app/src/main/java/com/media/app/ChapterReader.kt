package com.media.app

import java.nio.charset.Charset

// ============================================================================
//  CHAPTERS, read from the file
//
//  Audiobooks and many podcasts carry their own chapter list. Read straight
//  from the bytes, like TagReader, in the three forms they come in:
//
//      ID3v2 CHAP frames (MP3)       start time in ms, title in an
//                                    embedded TIT2 frame
//      Nero chapters, moov/udta/chpl  start in 100 ns units, title
//      (M4B / M4A)
//      QuickTime chapter track        a text track (the one tref/chap points
//      (M4B / M4A, Apple's own)       at, or else any 'text' track): one
//                                    sample per chapter, its time in stts
//
//  Pure Kotlin on a ByteSource, so the JVM tests read built files. Nothing
//  is ever written back.
// ============================================================================

/** One chapter: where it starts, and its title (may be blank). */
data class Chapter(val startMs: Long, val title: String)

object ChapterReader {
    private const val MAX_FRAME = 1 shl 20
    private const val MAX_CHAPTERS = 2000

    fun read(src: ByteSource): List<Chapter> = try {
        val head = src.read(0, 12)
        val found = when {
            head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> id3(src)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1).let {
                it == "ftyp" || it == "moov" || it == "free" || it == "wide" || it == "mdat"
            } -> mp4(src)
            else -> emptyList()
        }
        tidy(found)
    } catch (_: Exception) {
        emptyList()
    }

    /** In order, one per start time, and only if there are at least two. */
    internal fun tidy(list: List<Chapter>): List<Chapter> {
        val sorted = list.filter { it.startMs >= 0 }.sortedBy { it.startMs }
            .distinctBy { it.startMs }.take(MAX_CHAPTERS)
            .map { it.copy(title = it.title.trim().replace('\u0000', ' ').trim()) }
        return if (sorted.size >= 2) sorted else emptyList()
    }

    // ------------------------------------------------------------- ID3 --

    private fun synchsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun be64(b: ByteArray, at: Int): Long =
        (be32(b, at).toLong() shl 32) or (be32(b, at + 4).toLong() and 0xFFFFFFFFL)

    private fun be16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun id3(src: ByteSource): List<Chapter> {
        val h = src.read(0, 10)
        if (h.size < 10) return emptyList()
        val major = h[3].toInt() and 0xFF
        val flags = h[5].toInt() and 0xFF
        // CHAP exists from v2.3. A whole-tag unsynchronised v2.3 tag would
        // need the tag read whole; chapters in one are rare enough to skip.
        if (major !in 3..4 || (major == 3 && flags and 0x80 != 0)) return emptyList()
        val size = synchsafe(h, 6)
        val limit = 10L + size
        var pos = 10L
        if (flags and 0x40 != 0) {
            val eh = src.read(pos, 4)
            if (eh.size < 4) return emptyList()
            pos += if (major == 4) synchsafe(eh, 0).toLong() else be32(eh, 0).toLong() + 4
        }
        val out = ArrayList<Chapter>()
        while (pos + 10 <= limit) {
            val fh = src.read(pos, 10)
            if (fh.size < 10 || fh[0].toInt() == 0) break
            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            val len = if (major == 4) synchsafe(fh, 4) else be32(fh, 4)
            if (len <= 0 || pos + 10 + len > limit) break
            if (id == "CHAP" && len <= MAX_FRAME) {
                chap(src.read(pos + 10, len), major)?.let(out::add)
            }
            pos += 10 + len
        }
        return out
    }

    /** A CHAP frame's body: element id, four times, then frames (TIT2). */
    internal fun chap(d: ByteArray, major: Int): Chapter? {
        var i = 0
        while (i < d.size && d[i].toInt() != 0) i++
        i++ // the id's terminator
        if (i + 16 > d.size) return null
        val start = be32(d, i).toLong() and 0xFFFFFFFFL
        i += 16
        var title = ""
        while (i + 10 <= d.size) {
            val id = String(d, i, 4, Charsets.ISO_8859_1)
            val len = if (major == 4) synchsafe(d, i + 4) else be32(d, i + 4)
            if (len <= 0 || i + 10 + len > d.size) break
            if (id == "TIT2" && len > 1) title = id3Text(d, i + 10, len)
            i += 10 + len
        }
        return Chapter(start, title)
    }

    private fun id3Text(d: ByteArray, at: Int, len: Int): String {
        val enc = d[at].toInt() and 0xFF
        val body = d.copyOfRange(at + 1, at + len)
        val cs: Charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(body, cs).substringBefore('\u0000')
    }

    // ------------------------------------------------------------- MP4 --

    private class Atom(val type: String, val start: Long, val dataAt: Long, val end: Long)

    private fun atoms(src: ByteSource, from: Long, to: Long): List<Atom> {
        val out = ArrayList<Atom>()
        var pos = from
        while (pos + 8 <= to && out.size < 512) {
            val h = src.read(pos, 16)
            if (h.size < 8) break
            var size = be32(h, 0).toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                if (h.size < 16) break
                size = be64(h, 8); header = 16L
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < header || pos + size > to) break
            out += Atom(type, pos, pos + header, pos + size)
            pos += size
        }
        return out
    }

    private fun child(src: ByteSource, parent: Atom, type: String, skip: Long = 0): Atom? =
        atoms(src, parent.dataAt + skip, parent.end).firstOrNull { it.type == type }

    private fun mp4(src: ByteSource): List<Chapter> {
        val moov = atoms(src, 0, src.size).firstOrNull { it.type == "moov" } ?: return emptyList()
        // Nero chapters first: one small atom, read whole.
        val chpl = child(src, moov, "udta")?.let { child(src, it, "chpl") }
        if (chpl != null) {
            val got = chpl(src.read(chpl.dataAt, (chpl.end - chpl.dataAt).toInt().coerceAtMost(MAX_FRAME)))
            if (got.size >= 2) return got
        }
        return quickTime(src, moov)
    }

    /** The chpl body: version, flags, (v1: 4 more), count, then (time, title). */
    internal fun chpl(b: ByteArray): List<Chapter> {
        if (b.size < 5) return emptyList()
        val version = b[0].toInt() and 0xFF
        var i = 4 + if (version > 0) 4 else 0
        if (i >= b.size) return emptyList()
        val n = b[i].toInt() and 0xFF
        i++
        val out = ArrayList<Chapter>(n)
        repeat(n) {
            if (i + 9 > b.size) return out
            val start100ns = be64(b, i)
            val len = b[i + 8].toInt() and 0xFF
            i += 9
            if (i + len > b.size) return out
            out += Chapter(start100ns / 10_000L, String(b, i, len, Charsets.UTF_8))
            i += len
        }
        return out
    }

    /** Apple's chapter track: a text track's samples are the titles. */
    private fun quickTime(src: ByteSource, moov: Atom): List<Chapter> {
        val traks = atoms(src, moov.dataAt, moov.end).filter { it.type == "trak" }
        // The track ids that some track's tref/chap points at.
        val chapIds = HashSet<Int>()
        for (t in traks) {
            val chap = child(src, t, "tref")?.let { child(src, it, "chap") } ?: continue
            val b = src.read(chap.dataAt, (chap.end - chap.dataAt).toInt().coerceAtMost(256))
            var i = 0
            while (i + 4 <= b.size) { chapIds += be32(b, i); i += 4 }
        }
        val texts = traks.filter { handler(src, it) == "text" }
        val pick = texts.firstOrNull { trackId(src, it) in chapIds } ?: texts.firstOrNull() ?: return emptyList()
        return textTrack(src, pick)
    }

    private fun trackId(src: ByteSource, trak: Atom): Int {
        val tkhd = child(src, trak, "tkhd") ?: return -1
        val b = src.read(tkhd.dataAt, 24)
        if (b.isEmpty()) return -1
        val v = b[0].toInt() and 0xFF
        val at = if (v == 1) 20 else 12
        return if (b.size >= at + 4) be32(b, at) else -1
    }

    private fun handler(src: ByteSource, trak: Atom): String? {
        val mdia = child(src, trak, "mdia") ?: return null
        val hdlr = child(src, mdia, "hdlr") ?: return null
        val b = src.read(hdlr.dataAt, 12)
        return if (b.size >= 12) String(b, 8, 4, Charsets.ISO_8859_1) else null
    }

    private fun textTrack(src: ByteSource, trak: Atom): List<Chapter> {
        val mdia = child(src, trak, "mdia") ?: return emptyList()
        val mdhd = child(src, mdia, "mdhd") ?: return emptyList()
        val mh = src.read(mdhd.dataAt, 32)
        if (mh.isEmpty()) return emptyList()
        val timescale = (if ((mh[0].toInt() and 0xFF) == 1) be32(mh, 20) else be32(mh, 12)).toLong()
        if (timescale <= 0) return emptyList()
        val stbl = child(src, mdia, "minf")?.let { child(src, it, "stbl") } ?: return emptyList()
        fun body(type: String): ByteArray? = child(src, stbl, type)?.let {
            src.read(it.dataAt, (it.end - it.dataAt).toInt().coerceAtMost(MAX_FRAME))
        }

        // Sample start times, from stts (count, delta) runs.
        val stts = body("stts") ?: return emptyList()
        val starts = ArrayList<Long>()
        var t = 0L
        run {
            val n = be32(stts, 4)
            var i = 8
            for (k in 0 until n) {
                if (i + 8 > stts.size) break
                val count = be32(stts, i); val delta = be32(stts, i + 4).toLong() and 0xFFFFFFFFL
                for (c in 0 until count) {
                    if (starts.size >= MAX_CHAPTERS) break
                    starts += t; t += delta
                }
                i += 8
            }
        }
        // Sample sizes.
        val stsz = body("stsz") ?: return emptyList()
        val fixed = be32(stsz, 4)
        val count = be32(stsz, 8).coerceAtMost(starts.size)
        val sizes = IntArray(count) { k -> if (fixed != 0) fixed else if (12 + 4 * k + 4 <= stsz.size) be32(stsz, 12 + 4 * k) else 0 }
        // Chunk offsets (stco or co64), and samples per chunk (stsc).
        val offsets: LongArray = body("stco")?.let { b ->
            LongArray(be32(b, 4).coerceAtLeast(0).coerceAtMost((b.size - 8) / 4)) { k -> be32(b, 8 + 4 * k).toLong() and 0xFFFFFFFFL }
        } ?: body("co64")?.let { b ->
            LongArray(be32(b, 4).coerceAtLeast(0).coerceAtMost((b.size - 8) / 8)) { k -> be64(b, 8 + 8 * k) }
        } ?: return emptyList()
        val stsc = body("stsc") ?: return emptyList()
        val runs = be32(stsc, 4)
        // Offset of every sample, chunk by chunk.
        val sampleAt = LongArray(count)
        var sample = 0
        for (r in 0 until runs) {
            val at = 8 + 12 * r
            if (at + 12 > stsc.size) break
            val firstChunk = be32(stsc, at) - 1
            val perChunk = be32(stsc, at + 4)
            val nextFirst = if (r + 1 < runs && at + 24 <= stsc.size) be32(stsc, at + 12) - 1 else offsets.size
            for (chunk in firstChunk until nextFirst.coerceAtMost(offsets.size)) {
                var off = offsets[chunk]
                for (s in 0 until perChunk) {
                    if (sample >= count) break
                    sampleAt[sample] = off
                    off += sizes[sample]
                    sample++
                }
            }
        }
        val out = ArrayList<Chapter>(count)
        for (k in 0 until sample) {
            val b = src.read(sampleAt[k], sizes[k].coerceAtMost(4096))
            val title = if (b.size >= 2) {
                val len = be16(b, 0).coerceAtMost(b.size - 2)
                String(b, 2, len, if (len >= 2 && b[2] == 0xFE.toByte() && b[3] == 0xFF.toByte()) Charsets.UTF_16 else Charsets.UTF_8)
            } else ""
            out += Chapter(starts[k] * 1000L / timescale, title)
        }
        return out
    }
}

/** The chapter playing at [positionMs], or -1 before the first. */
fun chapterIndexAt(chapters: List<Chapter>, positionMs: Long): Int {
    var lo = 0
    var hi = chapters.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (chapters[mid].startMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}
