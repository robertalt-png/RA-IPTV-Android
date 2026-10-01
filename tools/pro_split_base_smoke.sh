#!/usr/bin/env bash
set -euo pipefail

AAB="$GITHUB_WORKSPACE/base/RA_IPTV_Android_v0.1/app/build/outputs/bundle/release/app-release.aab"
APKS="$GITHUB_WORKSPACE/pro-base-startup.apks"
KS="$HOME/.android/nenotv-qa-debug.keystore"
PKG="com.nenotv.player"

test -f "$AAB"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -v \
    -keystore "$KS" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=NenoTV QA,O=NenoTV,C=NL" >/dev/null 2>&1
fi

java -jar "$GITHUB_WORKSPACE/bundletool.jar" build-apks \
  --bundle="$AAB" \
  --output="$APKS" \
  --overwrite \
  --local-testing \
  --ks="$KS" \
  --ks-key-alias=androiddebugkey \
  --ks-pass=pass:android \
  --key-pass=pass:android

java -jar "$GITHUB_WORKSPACE/bundletool.jar" install-apks --apks="$APKS"

adb shell pm path "$PKG" | tee "$GITHUB_WORKSPACE/pro-installed-paths.txt"
if grep -qi proextras "$GITHUB_WORKSPACE/pro-installed-paths.txt"; then
  echo "On-demand proextras was unexpectedly installed during base install"
  exit 2
fi

adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8
adb logcat -d > "$GITHUB_WORKSPACE/pro-base-startup-logcat.txt"

if grep -q "FATAL EXCEPTION" "$GITHUB_WORKSPACE/pro-base-startup-logcat.txt"; then
  cat "$GITHUB_WORKSPACE/pro-base-startup-logcat.txt"
  exit 2
fi

PID="$(adb shell pidof "$PKG" | tr -d '\r')"
test -n "$PID"
echo "Base-only startup PASS; proextras remains on-demand. pid=$PID"
