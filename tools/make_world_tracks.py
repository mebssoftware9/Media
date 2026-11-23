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
        print("made", mp3)
    os.remove(wav)


if __name__ == "__main__":
    main()
