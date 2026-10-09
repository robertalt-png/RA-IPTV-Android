#!/usr/bin/env bash
# Device matrix: real-life start and restart scenarios on one emulator.
# Usage: tools/qa-device-matrix.sh <phone|tablet|tv> <api>
# Setup steps (demo source, Pro checks) are best effort: a failure there is reported as a warning,
# because on some Android versions the emulator itself is the problem. A crash of the app,
# or the app not running after a launch, always fails the job.
set -uo pipefail
kind="$1"; api="$2"
pkg=com.nenotv.player
out=qa-results; mkdir -p "$out"
# Installed the way Play does it: base + config splits, with or without the Pro module as its own split.
play_install() { # play_install <light|pro>
  local mods=""; [ "$1" = pro ] && mods="--modules=_ALL_"
  java -jar qa-tools/bundletool.jar install-apks --apks=qa-tools/all.apks $mods --allow-downgrade >> "$out/install.txt" 2>&1
}
fail=0
summary="$out/summary.txt"; : > "$summary"

note() { echo "$1" | tee -a "$summary"; }
annot() { echo "::$1 title=$kind API $api: $2::$(head -c 3000 "${3:-/dev/null}" 2>/dev/null | tr -d '\r' | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')"; }

# Crashes of our package in the crash buffer since the last clear.
check_crash() {
  local step="$1" f="$out/crash-$1.txt"
  adb logcat -b crash -d > "$f" 2>/dev/null || true
  if rg -q "$pkg" "$f"; then
    note "FAIL $step: crash"; annot error "crash during $step" "$f"; fail=1
  fi
  adb logcat -b crash -c >/dev/null 2>&1 || true
}

launch() {
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep "${1:-8}"
}

alive() { [ -n "$(adb shell pidof "$pkg" | tr -d '\r')" ]; }
start_ok() { launch "$1"; alive; }

step() { # step <name> <command...>
  local name="$1"; shift
  if "$@"; then note "ok   $name"; else note "FAIL $name"; annot error "$name failed" "$out/crash-$name.txt"; fail=1; fi
  check_crash "$name"
}

setup() { # best-effort instrumentation phase
  local phase="$1" key="$2"
  timeout 600 adb shell am instrument -w -e device "$kind" -e phase "$phase" $pkg.test/$pkg.UiInstrumentation > "$out/setup-$phase.txt" 2>&1 || true
  if rg -q "$key=passed" "$out/setup-$phase.txt"; then note "ok   setup $phase"
  else note "warn setup $phase (see setup-$phase.txt)"; annot warning "setup $phase did not pass" "$out/setup-$phase.txt"; fi
  check_crash "setup-$phase"
}

adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell getprop ro.build.version.release > "$out/android-version.txt"
adb shell wm size > "$out/screen.txt"
adb logcat -b crash -c >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

# 0. Light first (as from the Play Store), opened, then the Pro module added and the app reopened:
#    the exact sequence that crashed every start in 0.14.40-0.14.43 (ML Kit start-up with Pro installed).
play_install light || { note "FAIL install light"; annot error "Play-style install failed" "$out/install.txt"; exit 2; }
adb shell pm path $pkg > "$out/paths-light.txt"
step light-first-start start_ok 10
adb shell am force-stop $pkg
play_install pro || { note "FAIL install pro"; annot error "Play-style Pro install failed" "$out/install.txt"; exit 2; }
adb shell pm path $pkg > "$out/paths-pro.txt"
if rg -qi proextras "$out/paths-pro.txt"; then note "ok   pro split installed"; else note "FAIL pro split missing"; fail=1; fi
for i in 1 2 3; do
  adb shell am force-stop $pkg
  step "pro-split-start-$i" start_ok 10
done
adb install -r qa-tools/tests.apk >> "$out/install.txt" 2>&1 || { note "FAIL install tests"; annot error "test APK install failed" "$out/install.txt"; exit 2; }
# Notifications allowed, as a viewer would after the first prompt (Android 13+).
[ "$api" -ge 33 ] && adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

# 1. ML Kit on demand with the Pro split installed: text scanning and Pro language detection.
timeout 300 adb shell am instrument -w -e phase mlkit $pkg.test/$pkg.UiInstrumentation > "$out/mlkit.txt" 2>&1 || true
if rg -q 'NENOTV_MLKIT_SCAN=passed' "$out/mlkit.txt"; then note "ok   mlkit scan"; else note "FAIL mlkit scan"; annot error "text scanning cannot start ML Kit" "$out/mlkit.txt"; fail=1; fi
if rg -q 'NENOTV_MLKIT_PRO=passed' "$out/mlkit.txt"; then note "ok   mlkit pro"; else note "warn mlkit pro"; annot warning "Pro language detection cannot start ML Kit" "$out/mlkit.txt"; fi
check_crash mlkit

# 2. Set up like a viewer: demo source and the Pro checks (playback, recording, EPG).
setup family SUNNYIPTV_FAMILY_TESTS
setup pro NENOTV_RECORDING

# 3. Close and reopen five times (the 0.14.42 crash happened on the second start).
for i in 1 2 3 4 5; do
  adb shell am force-stop $pkg
  step "restart-$i" start_ok 8
done

# 4. Home and back three times, then the system kills the app in the background and it is reopened.
for i in 1 2 3; do
  adb shell input keyevent KEYCODE_HOME; sleep 2
  step "back-to-front-$i" start_ok 4
done
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill $pkg; sleep 2
step process-death-reopen start_ok 8

# 5. Memory pressure while open.
for level in RUNNING_LOW RUNNING_CRITICAL COMPLETE; do adb shell am send-trim-memory $pkg $level >/dev/null 2>&1 || true; sleep 1; done
step memory-pressure alive

# 6. Rotation on phones and tablets.
if [ "$kind" != tv ]; then
  adb shell settings put system accelerometer_rotation 0
  for r in 1 0 1 0; do adb shell settings put system user_rotation $r; sleep 2; done
  step rotation alive
fi

# 7. Recording left running, app killed mid-recording, reopened twice.
adb shell am force-stop $pkg
setup record_leave NENOTV_RECORD_LEFT
adb shell am force-stop $pkg
for i in 1 2; do
  step "reopen-after-recording-$i" start_ok 10
  adb shell am force-stop $pkg
done

# 8. "Gegevens wissen" (clear data), then start twice.
adb shell pm clear $pkg > /dev/null
for i in 1 2; do
  step "after-clear-data-$i" start_ok 10
  adb shell am force-stop $pkg
done

# 9. Update over itself (Play update path), then start.
play_install pro
step after-reinstall start_ok 10

adb logcat -d > "$out/logcat.txt" 2>/dev/null || true
adb exec-out screencap -p > "$out/last-screen.png" 2>/dev/null || true
cat "$summary"
{ echo "### $kind · API $api · Android $(tr -d '\r' < "$out/android-version.txt")"; echo '```'; cat "$summary"; echo '```'; } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit $fail
