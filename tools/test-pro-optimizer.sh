#!/usr/bin/env bash
set -euo pipefail
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -encoding UTF-8 -d "$out" \
  android/app/src/main/java/com/robertalt/raiptv/model/MediaEntry.java \
  android/app/src/main/java/com/robertalt/raiptv/ContentLanguage.java \
  android/proextras/src/main/java/com/robertalt/raiptv/proextras/ProLibraryOptimizer.java \
  tools/tests/ProLibraryOptimizerTest.java
java -cp "$out" ProLibraryOptimizerTest
