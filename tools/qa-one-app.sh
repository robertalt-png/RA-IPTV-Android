#!/usr/bin/env bash
set -euo pipefail
device="$1"
pkg=com.nenotv.player
mkdir -p qa-results
capture_phase_evidence() {
  local phase="$1"
  timeout 15s adb logcat -d > "qa-results/$phase-logcat.txt" || true
  timeout 10s adb shell dumpsys window > "qa-results/$phase-window.txt" || true
  timeout 10s adb shell dumpsys activity activities > "qa-results/$phase-activities.txt" || true
  timeout 5s adb shell ps -A > "qa-results/$phase-processes.txt" || true
  timeout 10s adb pull /sdcard/Android/data/$pkg/files/qa "qa-results/$phase-screenshots" || true
}
run_instrument() {
  local seconds="$1" phase="$2" expected="$3" rc msg out
  shift 3
  out="qa-results/$phase.txt"
  timeout 10s adb logcat -c >/dev/null 2>&1 || true
  echo "QA phase $phase: timeout ${seconds}s, expecting $expected=passed"
  if timeout --kill-after=10s "${seconds}s" adb shell am instrument -w "$@" "$pkg.test/$pkg.UiInstrumentation" | tee "$out"; then
    if rg -q "$expected=passed" "$out"; then
      return 0
    fi
    capture_phase_evidence "$phase"
    msg=$(tail -n 40 "$out" | tr -d '\r' | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')
    echo "::error title=QA phase $phase missing $expected::$msg"
    return 2
  else
    rc=$?
  fi
  capture_phase_evidence "$phase"
  timeout 10s adb shell am force-stop "$pkg" >/dev/null 2>&1 || true
  if [ "$rc" -eq 124 ]; then
    msg="Instrumentation phase $phase exceeded ${seconds}s. See qa-results/$phase-logcat.txt, $phase-window.txt and $phase-activities.txt."
  else
    msg=$(tail -n 40 "$out" 2>/dev/null | tr -d '\r' | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')
  fi
  echo "::error title=QA phase $phase failed::$msg"
  return "$rc"
}
report_failure() {
  rc=$?
  timeout 15s adb logcat -d > qa-results/logcat.txt || true
  timeout 15s adb pull /sdcard/Android/data/$pkg/files/qa qa-results/final-screenshots || true
  [ "$rc" -eq 0 ] && return 0
  # Surface the cause as annotations: the last instrumentation output and any crash, so failures are readable without the log archive.
  last=$(ls -t qa-results/*.txt 2>/dev/null | grep -v logcat | head -1 || true)
  if [ -n "$last" ]; then
    msg=$(tail -n 25 "$last" | tr -d '\r' | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')
    echo "::error title=QA failed in $(basename "$last")::$msg"
  fi
  crash=$(rg -A 12 'FATAL EXCEPTION' qa-results/logcat.txt 2>/dev/null | head -30 | tr -d '\r' | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}' || true)
  [ -n "$crash" ] && echo "::error title=App crash::$crash"
  return 0
}
trap report_failure EXIT
apk="$PWD/distribution/SunnyIPTV-Pro-v0.14.54-vc149-TEST-SIGNED.apk"
light_apk="$PWD/distribution/SunnyIPTV-Light-v0.14.54-vc149-TEST-SIGNED.apk"
adb install "$light_apk"
adb install qa-tools/tests.apk
run_instrument 240 family-light SUNNYIPTV_FAMILY_TESTS -e phase family
run_instrument 240 account-free-light SUNNYIPTV_ACCOUNT_FREE_SETUP -e phase account_free
adb shell am force-stop "$pkg"
adb shell pm path "$pkg" > qa-results/light-apk-paths.txt
if rg -qi proextras qa-results/light-apk-paths.txt; then exit 2; fi
adb logcat -c
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 5
adb logcat -d > qa-results/light-apk-logcat.txt
if rg -q 'FATAL EXCEPTION' qa-results/light-apk-logcat.txt; then exit 2; fi
test -n "$(adb shell pidof "$pkg" | tr -d '\r')"
adb uninstall "$pkg"
if [ "$device" = phone ]; then
  java -jar qa-tools/bundletool.jar install-apks --apks=qa-tools/phone.apks
  adb shell pm path "$pkg" > qa-results/play-light-delivery-paths.txt
  if rg -qi proextras qa-results/play-light-delivery-paths.txt; then exit 2; fi
  adb uninstall "$pkg"
fi
adb install "$apk"
adb install qa-tools/tests.apk
run_instrument 240 family-pro SUNNYIPTV_FAMILY_TESTS -e phase family
run_instrument 240 account-free-pro SUNNYIPTV_ACCOUNT_FREE_SETUP -e phase account_free
adb shell am force-stop "$pkg"
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c
if [ "$device" = phone ]; then
  for phase in default prepare_resume verify_resume demo demo_resume; do
    case "$phase" in
      default) key=NENOTV_IMPORT_TESTS;;
      prepare_resume) key=NENOTV_RESUME_PREPARE;;
      verify_resume) key=NENOTV_RESUME_VERIFY;;
      demo) key=NENOTV_DEMO_TESTS;;
      demo_resume) key=NENOTV_DEMO_RESUME;;
    esac
    run_instrument 360 "$phase" "$key" -e phase "$phase"
    adb shell am force-stop "$pkg"
  done
  adb shell settings put system font_scale 1.3
fi
run_instrument 240 catalog NENOTV_CATALOG_PACKAGE -e phase catalog
adb shell am force-stop "$pkg"
run_instrument 600 ui NENOTV_UI_SCREENSHOTS -e device "$device" -e phase ui
adb shell am force-stop "$pkg"
run_instrument 300 ui-resume NENOTV_UI_RESUME -e phase resume
run_instrument 900 pro-runtime NENOTV_PRO_RUNTIME -e phase pro
rg -q 'NENOTV_PRO_LANGUAGE=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_SOURCES=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_SMART_SOURCES=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_SMART_EPG=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_RUNTIME=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_RECORDING=passed' qa-results/pro-runtime.txt
adb pull /sdcard/Android/data/com.nenotv.player/files/qa qa-results/screenshots
if [ "$device" = phone ]; then
  mv qa-results/screenshots qa-results/phone-screenshots
  adb shell wm size 1600x2560
  adb shell wm density 320
  adb shell settings put system font_scale 1.0
  adb shell am force-stop "$pkg"
  run_instrument 600 tablet-ui NENOTV_UI_SCREENSHOTS -e device tablet -e phase ui
  adb pull /sdcard/Android/data/com.nenotv.player/files/qa qa-results/tablet-screenshots
fi
# Real-life restart: a set-up app closed by the viewer and opened again from the launcher, twice.
adb logcat -b crash -c || true
for attempt in 1 2; do
  adb shell am force-stop "$pkg"
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 10
  test -n "$(adb shell pidof "$pkg" | tr -d '\r')" || { adb logcat -b crash -d > qa-results/restart-crash.txt; echo "::error title=Crash after restart $attempt::$(head -c 3000 qa-results/restart-crash.txt | tr -d '\r' | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')"; exit 2; }
done
adb logcat -b crash -d > qa-results/restart-crash.txt || true
if rg -q 'FATAL EXCEPTION' qa-results/restart-crash.txt; then exit 2; fi
adb logcat -d > qa-results/logcat.txt
if rg -q 'FATAL EXCEPTION' qa-results/logcat.txt; then exit 2; fi
