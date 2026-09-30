#!/usr/bin/env bash
set -euo pipefail
APK="${1:?APK path required}"
AAPT="${AAPT:-$(find "$ANDROID_HOME/build-tools" -name aapt -type f | sort -V | tail -1)}"
"$AAPT" dump badging "$APK" | tee qa-badging.txt
"$AAPT" dump permissions "$APK" | tee qa-permissions.txt
grep -q "package: name='com.robertalt.raiptv'" qa-badging.txt
grep -q "application-label:'NenoTV'" qa-badging.txt
for p in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.WAKE_LOCK; do
  grep -q "$p" qa-permissions.txt || { echo "Missing required permission $p"; exit 2; }
done
