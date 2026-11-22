#!/usr/bin/env bash
# Living Worlds on a real Android: installs the debug build on the emulator
# the workflow started, opens each test song with "Open with Via" (which goes
# straight to Now Playing), and keeps what the screen shows - two stills and
# a short screen recording per world - in screens/.
#
#   bash tools/world_screenshots.sh APK TRACKS_DIR
#
# Run by .github/workflows/living-worlds.yml inside android-emulator-runner.
set -u
APK="$1"
TRACKS="$2"
PKG=app.devbangs.media
ACT=com.media.app.MainActivity
OUT=screens
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }

# Taps the first element showing LABEL, if there is one. Returns 1 if not found.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 || return 1
  local xy
  xy=$(python3 tools/ui_tap.py /tmp/ui.xml "$1")
  [ -z "$xy" ] && return 1
  echo "tap '$1' at $xy"
  adb shell input tap $xy
}

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
adb shell input keyevent 82 || true
# Music volume up: the world reacts to nothing at zero volume, by design.
adb shell cmd media_session volume --stream 3 --set 12 >/dev/null 2>&1 || \
  adb shell media volume --stream 3 --set 12 >/dev/null 2>&1 || true

adb install -r -g "$APK"
adb shell mkdir -p /sdcard/Music/ViaWorlds
for f in "$TRACKS"/*.mp3; do adb push "$f" /sdcard/Music/ViaWorlds/ >/dev/null; done
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 3

# First run: the welcome, then the permission explainer (already granted by -g).
adb shell am start -W -n "$PKG/$ACT"
sleep 8
shot 00-welcome
tap "Got it" && sleep 3
tap "Understood" && sleep 3
tap "Allow" && sleep 3
sleep 4
shot 01-home

first=1
for world in night_rain fireflies ocean cosmos dream embers minimal; do
  adb shell am start -a android.intent.action.VIEW \
    -d "file:///sdcard/Music/ViaWorlds/$world.mp3" -t audio/mpeg -n "$PKG/$ACT" >/dev/null
  sleep 6
  if [ "$first" = 1 ]; then
    # The first time Now Playing opens it offers Reactive artwork: turn it on,
    # so the cover moves with the beat as well as the world around it.
    shot 02-reactive-hint
    tap "Turn it on" && sleep 2
    first=0
  fi
  sleep 6
  shot "world-$world-a"
  sleep 0.7
  shot "world-$world-b"
  adb shell screenrecord --time-limit 6 --bit-rate 6000000 "/sdcard/$world.mp4" || true
  adb pull "/sdcard/$world.mp4" "$OUT/world-$world.mp4" >/dev/null 2>&1 || true
done

# Recordings to GIFs, which open anywhere.
for v in "$OUT"/*.mp4; do
  [ -s "$v" ] || continue
  ffmpeg -y -v error -i "$v" -vf "fps=15,scale=360:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=192[p];[b][p]paletteuse=dither=sierra2_4a" "${v%.mp4}.gif" || true
done
ls -la "$OUT"
