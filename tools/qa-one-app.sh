#!/usr/bin/env bash
set -euo pipefail
device="$1"
mkdir -p qa-results
trap 'timeout 15s adb logcat -d > qa-results/logcat.txt || true; timeout 15s adb pull /sdcard/Android/data/com.nenotv.player/files/qa qa-results/final-screenshots || true' EXIT
apk="$PWD/distribution/SunnyIPTV-Pro-v0.14.21-vc115-TEST-SIGNED.apk"
light_apk="$PWD/distribution/SunnyIPTV-Light-v0.14.21-vc115-TEST-SIGNED.apk"
adb install "$light_apk"
adb install qa-tools/tests.apk
adb shell am instrument -w -e phase family com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/family-light.txt
rg -q 'SUNNYIPTV_FAMILY_TESTS=passed' qa-results/family-light.txt
adb shell am instrument -w -e phase account_free com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/account-free-light.txt
rg -q 'SUNNYIPTV_ACCOUNT_FREE_SETUP=passed' qa-results/account-free-light.txt
adb shell am force-stop com.nenotv.player
adb shell pm path com.nenotv.player > qa-results/light-apk-paths.txt
if rg -qi proextras qa-results/light-apk-paths.txt; then exit 2; fi
adb logcat -c
adb shell monkey -p com.nenotv.player -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 5
adb logcat -d > qa-results/light-apk-logcat.txt
if rg -q 'FATAL EXCEPTION' qa-results/light-apk-logcat.txt; then exit 2; fi
test -n "$(adb shell pidof com.nenotv.player | tr -d '\r')"
adb uninstall com.nenotv.player
if [ "$device" = phone ]; then
  java -jar qa-tools/bundletool.jar install-apks --apks=qa-tools/phone.apks
  adb shell pm path com.nenotv.player > qa-results/play-light-delivery-paths.txt
  if rg -qi proextras qa-results/play-light-delivery-paths.txt; then exit 2; fi
  adb uninstall com.nenotv.player
fi
adb install "$apk"
adb install qa-tools/tests.apk
adb shell am instrument -w -e phase family com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/family-pro.txt
rg -q 'SUNNYIPTV_FAMILY_TESTS=passed' qa-results/family-pro.txt
adb shell am instrument -w -e phase account_free com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/account-free-pro.txt
rg -q 'SUNNYIPTV_ACCOUNT_FREE_SETUP=passed' qa-results/account-free-pro.txt
adb shell am force-stop com.nenotv.player
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
    adb shell am instrument -w -e phase "$phase" com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee "qa-results/$phase.txt"
    rg -q "$key=passed" "qa-results/$phase.txt"
    adb shell am force-stop com.nenotv.player
  done
  adb shell settings put system font_scale 1.3
fi
adb shell am instrument -w -e phase catalog com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/catalog.txt
rg -q 'NENOTV_CATALOG_PACKAGE=passed' qa-results/catalog.txt
adb shell am force-stop com.nenotv.player
adb shell am instrument -w -e device "$device" -e phase ui com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/ui.txt
rg -q 'NENOTV_UI_SCREENSHOTS=passed' qa-results/ui.txt
adb shell am force-stop com.nenotv.player
adb shell am instrument -w -e phase resume com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/ui-resume.txt
rg -q 'NENOTV_UI_RESUME=passed' qa-results/ui-resume.txt
adb shell am instrument -w -e phase pro com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_LANGUAGE=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_SOURCES=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_SMART_SOURCES=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_SMART_EPG=passed' qa-results/pro-runtime.txt
rg -q 'NENOTV_PRO_RUNTIME=passed' qa-results/pro-runtime.txt
adb pull /sdcard/Android/data/com.nenotv.player/files/qa qa-results/screenshots
if [ "$device" = phone ]; then
  mv qa-results/screenshots qa-results/phone-screenshots
  adb shell wm size 1600x2560
  adb shell wm density 320
  adb shell settings put system font_scale 1.0
  adb shell am force-stop com.nenotv.player
  adb shell am instrument -w -e device tablet -e phase ui com.nenotv.player.test/com.nenotv.player.UiInstrumentation | tee qa-results/tablet-ui.txt
  rg -q 'NENOTV_UI_SCREENSHOTS=passed' qa-results/tablet-ui.txt
  adb pull /sdcard/Android/data/com.nenotv.player/files/qa qa-results/tablet-screenshots
fi
adb logcat -d > qa-results/logcat.txt
if rg -q 'FATAL EXCEPTION' qa-results/logcat.txt; then exit 2; fi

