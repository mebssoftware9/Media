#!/usr/bin/env bash
# Living Worlds on a real Android: installs the debug build on the emulator
# the workflow started, opens each test song with "Open with Via" (which goes
# straight to Now Playing), and keeps what the screen shows - two stills, a
# burst of frames made into a GIF, and a screen recording per world - in
# screens/. UI dumps and the log go to screens/debug/.
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
DBG=$OUT/debug
mkdir -p "$OUT" "$DBG"

# --compressed skips uiautomator's "not accessibility friendly" check, which
# crashes on Compose screens (NullPointerException in nafCheck) and left the
# Reactive artwork card untapped over every world. Tried twice.
dump() {
  local i
  rm -f "$DBG/ui.xml"
  for i in 1 2; do
    adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1
    adb shell uiautomator dump --compressed /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$DBG/ui.xml" >/dev/null 2>&1 && [ -s "$DBG/ui.xml" ] && return 0
    sleep 1
  done
  return 1
}

# Taps the first element showing LABEL, if there is one. Returns 1 if not found.
tap() {
  dump || return 1
  local xy
  xy=$(python3 tools/ui_tap.py "$DBG/ui.xml" "$1")
  [ -z "$xy" ] && return 1
  echo "tap '$1' at $xy"
  adb shell input tap $xy
}

# A slow emulator's launcher or System UI can raise "isn't responding" over
# everything; the first run had one over Via for every shot. Answer "Wait"
# (the app behind keeps running) until none is left.
clear_dialogs() {
  local i
  for i in 1 2 3; do
    dump
    if grep -qiE "isn.t responding|keeps stopping|has stopped" "$DBG/ui.xml" 2>/dev/null; then
      echo "system dialog up, dismissing"
      tap "Wait" || tap "Close app" || tap "OK" || adb shell input keyevent 4
      sleep 2
    else
      return 0
    fi
  done
}

shot() {
  clear_dialogs
  adb exec-out screencap -p > "$OUT/$1.png"
  cp "$DBG/ui.xml" "$DBG/$1.xml" 2>/dev/null || true
  echo "shot $1"
}

# A burst of stills, made into a GIF: works however few frames the
# emulator's screenrecord produces.
burst() {
  local name=$1 dir="$DBG/burst-$1" i
  mkdir -p "$dir"
  for i in $(seq -w 1 16); do
    adb exec-out screencap -p > "$dir/$i.png"
  done
  ffmpeg -y -v error -framerate 6 -i "$dir/%02d.png" \
    -vf "scale=360:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=192[p];[b][p]paletteuse=dither=sierra2_4a" \
    -loop 0 "$OUT/world-$name.gif" || echo "gif $name failed"
}

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
# No ANR or crash dialogs over the app being photographed.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
# Let the launcher finish its first start before anything else asks for CPU.
sleep 25
adb shell input keyevent 82 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
clear_dialogs
# Music volume up: the world reacts to nothing at zero volume, by design.
adb shell cmd media_session volume --stream 3 --set 12 >/dev/null 2>&1 || \
  adb shell media volume --stream 3 --set 12 >/dev/null 2>&1 || true

adb install -r -g "$APK"
adb shell mkdir -p /sdcard/Music/ViaWorlds
for f in "$TRACKS"/*.mp3; do adb push "$f" /sdcard/Music/ViaWorlds/ >/dev/null; done
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
sleep 3
adb logcat -c || true

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
    first=0
  fi
  # Every time, in case the first tap missed: nothing matches once it is gone.
  tap "Turn it on" && sleep 2
  sleep 5
  shot "world-$world-a"
  sleep 0.7
  shot "world-$world-b"
  burst "$world"
  adb shell screenrecord --time-limit 6 --bit-rate 6000000 "/sdcard/$world.mp4" || true
  adb pull "/sdcard/$world.mp4" "$OUT/world-$world.mp4" >/dev/null 2>&1 || true
done

adb logcat -d > "$DBG/logcat.txt" 2>/dev/null || true
grep -E "FATAL|AndroidRuntime|ANR in" "$DBG/logcat.txt" | head -40 || true
ls -la "$OUT"
