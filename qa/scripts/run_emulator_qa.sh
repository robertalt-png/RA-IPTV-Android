#!/usr/bin/env bash
set -euo pipefail

EDITION="${1:?edition required}"
API="${2:-35}"
SUITE="${3:-core}"
APP="$GITHUB_WORKSPACE/src/RA_IPTV_Android_v0.1"
EVIDENCE="$GITHUB_WORKSPACE/qa-evidence"
mkdir -p "$EVIDENCE"

adb logcat -c

if [ "$EDITION" = "pro" ]; then
  AAB="$APP/app/build/outputs/bundle/debug/app-debug.aab"
  APKS="$EVIDENCE/pro-debug.apks"
  KS="$HOME/.android/debug.keystore"
  test -f "$AAB"
  test -f "$GITHUB_WORKSPACE/bundletool.jar"

  if [ ! -f "$KS" ]; then
    keytool -genkeypair -v       -keystore "$KS"       -storepass android       -alias androiddebugkey       -keypass android       -keyalg RSA       -keysize 2048       -validity 10000       -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi

  java -jar "$GITHUB_WORKSPACE/bundletool.jar" build-apks     --bundle="$AAB"     --output="$APKS"     --overwrite     --local-testing     --ks="$KS"     --ks-key-alias=androiddebugkey     --ks-pass=pass:android     --key-pass=pass:android

  java -jar "$GITHUB_WORKSPACE/bundletool.jar" install-apks --apks="$APKS"

  adb shell pm path com.robertalt.raiptv | tee "$EVIDENCE/pro-installed-paths.txt"
  if grep -qi proextras "$EVIDENCE/pro-installed-paths.txt"; then
    echo "On-demand proextras was unexpectedly installed during base install"
    exit 2
  fi

  adb shell monkey -p com.robertalt.raiptv -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 5
  adb logcat -d > "$EVIDENCE/pro-base-startup-logcat.txt"
  if grep -q "FATAL EXCEPTION" "$EVIDENCE/pro-base-startup-logcat.txt"; then
    cat "$EVIDENCE/pro-base-startup-logcat.txt"
    exit 2
  fi
  PID="$(adb shell pidof com.robertalt.raiptv | tr -d '\r')"
  test -n "$PID"

  TEST_APK="$(find "$APP/app/build/outputs/apk/androidTest" -name '*.apk' -print -quit)"
  test -n "$TEST_APK"
  adb install -r "$TEST_APK"

  OUT="$EVIDENCE/instrumentation-pro.txt"
  if ! adb shell am instrument -w -r com.robertalt.raiptv.test/androidx.test.runner.AndroidJUnitRunner > "$OUT" 2>&1; then
    cat "$OUT"
    exit 2
  fi
  cat "$OUT"
  if grep -Eq "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" "$OUT"; then
    exit 2
  fi
else
  gradle -p "$APP" --no-daemon :app:connectedDebugAndroidTest
fi

adb shell screencap -p /sdcard/qa-final.png || true
adb pull /sdcard/qa-final.png "$EVIDENCE/final-${EDITION}-api${API}-${SUITE}.png" || true
adb logcat -d > "$EVIDENCE/logcat.txt"
