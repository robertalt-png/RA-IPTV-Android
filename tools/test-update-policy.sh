#!/usr/bin/env bash
set -euo pipefail
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/UpdatePromptPolicy.java tools/tests/UpdatePromptPolicyTest.java
java -cp "$classes" com.nenotv.player.UpdatePromptPolicyTest
