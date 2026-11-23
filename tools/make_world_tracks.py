#!/usr/bin/env python3
"""Makes one test song per Living World, for the screenshot workflow.

    python3 tools/make_world_tracks.py OUT_DIR

Each is 150 seconds at 120 BPM (long enough that none ends before its shots) - a kick on every beat, a snare on 2 and 4,
hi-hats on the eighths, a low pad - so Via's analysis finds a real groove
to move the world with. Each carries an embedded cover in the colours that
make Living Worlds choose that world (the same covers the unit tests draw,
WorldStage.cover), and a title naming it. Needs ffmpeg.
"""
import math
import os
import struct
import subprocess
import sys
import zlib

COVERS = {
    "night_rain": (0x0B1426, 0x1E3350, "Night Rain"),
    "fireflies": (0x1B5E20, 0x8BC34A, "Fireflies"),
    "ocean": (0x1565C0, 0x4FC3F7, "Ocean"),
    "cosmos": (0x22093F, 0x6A1B9A, "Cosmos"),
    "dream": (0xF8BBD0, 0xD1B3F0, "Dream"),
    "embers": (0xB3260A, 0xFF8F00, "Embers"),
    "minimal": (0x202024, 0xCFCFD4, "Minimal"),
}


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(((a >> s) & 255) * (1 - t) + ((b >> s) & 255) * t) for s in (16, 8, 0))


def pack(rgb):
    return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]


def cover_png(a, b, path, n=512):
    rows = []
    light = pack(mix(b, 0xFFFFFF, 0.45))
    for y in range(n):
        row = bytearray([0])
        for x in range(n):
            base = pack(mix(a, b, (x + y) / (2.0 * n)))
            dx, dy = x - n * 0.64, y - n * 0.36
            sun = max(0.0, min(1.0, 1 - math.sqrt(dx * dx + dy * dy) / (n * 0.22)))
            row += bytes(mix(base, light, sun * 0.8))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


GROOVE = (
    # Kick: a pitch drop on every beat.
    "aevalsrc='0.9*sin(2*PI*t*(48+90*exp(-mod(t,0.5)*28)))*exp(-mod(t,0.5)*9)':s=44100:d=150[k];"
    # Snare: noise on 2 and 4.
    "aevalsrc='0.45*(2*random(0)-1)*exp(-mod(t-0.5,1)*22)*gte(mod(t-0.5,1),0)':s=44100:d=150,"
    "bandpass=f=1800:w=1600[s];"
    # Hats: bright noise on the eighths.
    "aevalsrc='0.35*(2*random(1)-1)*exp(-mod(t,0.25)*70)':s=44100:d=150,highpass=f=7000[h];"
    # A low pad under it.
    "aevalsrc='0.10*sin(2*PI*110*t)+0.08*sin(2*PI*164.8*t)+0.06*sin(2*PI*220*t)':s=44100:d=150[p];"
    "[k][s][h][p]amix=inputs=4:normalize=0,alimiter=limit=0.9[out]"
)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "tracks"
    os.makedirs(out, exist_ok=True)
    wav = os.path.join(out, "groove.wav")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-filter_complex", GROOVE, "-map", "[out]", "-ac", "2", wav], check=True)
    for key, (a, b, title) in COVERS.items():
        png = os.path.join(out, key + ".png")
        cover_png(a, b, png)
        mp3 = os.path.join(out, key + ".mp3")
        subprocess.run([
            "ffmpeg", "-y", "-v", "error", "-i", wav, "-i", png,
            "-map", "0:a", "-map", "1:v", "-c:a", "libmp3lame", "-b:a", "192k",
            "-c:v", "png", "-disposition:v", "attached_pic", "-id3v2_version", "3",
            "-metadata", "title=" + title, "-metadata", "artist=Via Living Worlds",
            "-metadata", "album=" + title + " (test)", "-metadata", "genre=Electronic",
            "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)",
            mp3,
        ], check=True)
        os.remove(png)
        add_lyrics(mp3, title)
        print("made", mp3)
    os.remove(wav)
    make_audiobook(os.path.join(out, "book"))


LYRIC_LINES = [
    "Lights come up over the {w}",
    "Every beat a step closer",
    "Hold on to the sound",
    "We move when the bass moves",
    "Colours running through the night",
    "Turn it up and let it in",
    "This is where the music lives",
    "Stay a little longer",
]


def add_lyrics(mp3, world):
    """Synced lyrics as LRC text in a real ID3 USLT frame (ffmpeg only
    writes TXXX), a line every four seconds, so Now Playing has a sung line
    and the full lyrics something to follow."""
    lines = []
    for k in range(0, 37):
        t = 1.0 + 4.0 * k
        text = LYRIC_LINES[k % len(LYRIC_LINES)].format(w=world.lower())
        lines.append("[%02d:%05.2f]%s" % (int(t // 60), t % 60, text))
    lrc = "\n".join(lines).encode("latin-1")
    body = b"\x00" + b"eng" + b"\x00" + lrc
    frame = b"USLT" + struct.pack(">I", len(body)) + b"\x00\x00" + body
    data = open(mp3, "rb").read()
    assert data[:3] == b"ID3" and data[3] == 3
    size = (data[6] << 21) | (data[7] << 14) | (data[8] << 7) | data[9]
    tag = data[10:10 + size]
    end = 0  # the frames end where the zero padding starts
    while end + 10 <= len(tag) and tag[end] != 0:
        end += 10 + struct.unpack(">I", tag[end + 4:end + 8])[0]
    tag = tag[:end]
    new = tag + frame
    n = len(new)
    head = b"ID3\x03\x00\x00" + bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])
    open(mp3, "wb").write(head + new + data[10 + size:])


BOOK_CHAPTERS = ["Opening", "The Long Road", "A Quiet Town", "Rain at Night", "Home Again"]


def make_audiobook(out):
    """A four-minute 'audiobook' with five chapters (ID3 CHAP frames), for
    the skip buttons, the chapter line and the Chapters and bookmarks sheet.
    Pushed under Audiobooks/, which is how the library knows it is one."""
    os.makedirs(out, exist_ok=True)
    meta = os.path.join(out, "chapters.txt")
    step = 48_000
    with open(meta, "w") as f:
        f.write(";FFMETADATA1\ntitle=The Living Worlds\nartist=Via Test Narrator\nalbum=The Living Worlds\n")
        for i, t in enumerate(BOOK_CHAPTERS):
            f.write("[CHAPTER]\nTIMEBASE=1/1000\nSTART=%d\nEND=%d\ntitle=%s\n" % (i * step, (i + 1) * step, t))
    png = os.path.join(out, "cover.png")
    cover_png(0x3A1C71, 0xD76D77, png)
    mp3 = os.path.join(out, "living_worlds_audiobook.mp3")
    # A voice-like murmur: a low tone wobbling in pitch and level.
    voice = "aevalsrc='0.25*sin(2*PI*t*(140+30*sin(2*PI*t*3)))*(0.6+0.4*sin(2*PI*t*0.7))':s=22050:d=%d" % (len(BOOK_CHAPTERS) * step // 1000)
    subprocess.run([
        "ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", voice, "-i", meta, "-i", png,
        "-map", "0:a", "-map", "2:v", "-map_metadata", "1", "-map_chapters", "1",
        "-c:a", "libmp3lame", "-b:a", "48k", "-ac", "1",
        "-c:v", "png", "-disposition:v", "attached_pic", "-id3v2_version", "3",
        mp3,
    ], check=True)
    os.remove(meta)
    os.remove(png)
    print("made", mp3)


if __name__ == "__main__":
    main()
