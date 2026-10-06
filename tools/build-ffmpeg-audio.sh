#!/usr/bin/env bash
# Builds the Media3 FFmpeg audio decoder (LGPL, audio only) as an AAR.
# Usage: NDK_PATH=<ndk> tools/build-ffmpeg-audio.sh <output-dir>
# Output: <output-dir>/media3-decoder-ffmpeg-audio.aar plus licence.txt, decoders.txt, ffmpeg-source.txt
# FFmpeg is configured WITHOUT --enable-gpl / --enable-version3 / --enable-nonfree; the script fails otherwise.
set -euo pipefail
# On failure, surface the failing command and the last log lines as GitHub annotations (readable without log access).
report_failure() {
  local code=$? line=$1 cmd=$2
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error title=build-ffmpeg-audio.sh line ${line}::exit ${code}: ${cmd}"
    if [ -n "${LOG:-}" ] && [ -f "${LOG}" ]; then
      echo "::error title=FFmpeg build log (last 40 lines)::$(tail -n 40 "$LOG" | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')"
    fi
  fi
}
trap 'report_failure $LINENO "$BASH_COMMAND"' ERR

OUT="$(mkdir -p "$1" && cd "$1" && pwd)"
MEDIA3_VERSION="${MEDIA3_VERSION:-1.11.1}"   # must match androidx.media3 in android/app/build.gradle
FFMPEG_TAG="${FFMPEG_TAG:-n6.0.1}"            # pinned so the in-app source link points at the exact code
ANDROID_ABI="${ANDROID_ABI:-26}"              # = app minSdk
DECODERS=(aac mp3 mp2 ac3 eac3 dca truehd mlp flac alac opus vorbis)
: "${NDK_PATH:?Set NDK_PATH to an Android NDK (r26b tested by Media3)}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone -q --depth 1 --branch "$MEDIA3_VERSION" https://github.com/androidx/media.git "$WORK/media"
MODULE="$WORK/media/libraries/decoder_ffmpeg/src/main"
git clone -q --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git "$MODULE/jni/ffmpeg"

LOG="$OUT/ffmpeg-build.log"
(cd "$MODULE/jni" && ./build_ffmpeg.sh "$MODULE" "$NDK_PATH" linux-x86_64 "$ANDROID_ABI" "${DECODERS[@]}") > "$LOG" 2>&1 \
  || { tail -n 80 "$LOG"; exit 1; }

CFG="$MODULE/jni/ffmpeg/config.h"
for flag in GPL VERSION3 NONFREE; do
  grep -q "#define CONFIG_${flag} 0" "$CFG" || { echo "FFmpeg licence check failed: CONFIG_${flag} is not 0"; exit 1; }
done
{ grep -E "^License:" "$LOG" | sort -u; grep -E "#define CONFIG_(GPL|VERSION3|NONFREE) " "$CFG"; } > "$OUT/licence.txt"
# FFmpeg 6 keeps component switches in config_components.h; require every requested decoder to be enabled.
COMP="$MODULE/jni/ffmpeg/config_components.h"
grep -E "#define CONFIG_[A-Z0-9_]+_DECODER 1" "$COMP" > "$OUT/decoders.txt"
for d in "${DECODERS[@]}"; do
  grep -q "#define CONFIG_$(echo "$d" | tr '[:lower:]' '[:upper:]')_DECODER 1" "$COMP" || { echo "Decoder $d is not enabled"; exit 1; }
done
printf 'FFmpeg %s (%s)\nhttps://github.com/FFmpeg/FFmpeg/tree/%s\nMedia3 %s https://github.com/androidx/media/tree/%s/libraries/decoder_ffmpeg\n' \
  "$FFMPEG_TAG" "$(git -C "$MODULE/jni/ffmpeg" rev-parse HEAD)" "$FFMPEG_TAG" "$MEDIA3_VERSION" "$MEDIA3_VERSION" > "$OUT/ffmpeg-source.txt"

(cd "$WORK/media" && ./gradlew --no-daemon -q :lib-decoder-ffmpeg:assembleRelease)
AAR="$(find "$WORK/media" -path '*decoder_ffmpeg*' -name '*release*.aar' | head -n 1)"
test -n "$AAR" || { echo "FFmpeg AAR not found"; exit 1; }
cp "$AAR" "$OUT/media3-decoder-ffmpeg-audio.aar"
unzip -l "$OUT/media3-decoder-ffmpeg-audio.aar" | awk '/\.so$/{printf "%8.2f MB  %s\n",$1/1048576,$4}' | tee "$OUT/sizes.txt"
cat "$OUT/licence.txt"
