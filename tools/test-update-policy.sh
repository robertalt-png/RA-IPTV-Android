#!/usr/bin/env bash
set -euo pipefail
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/UpdatePromptPolicy.java tools/tests/UpdatePromptPolicyTest.java
java -cp "$classes" com.nenotv.player.UpdatePromptPolicyTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/SiteEndpoints.java tools/tests/SiteEndpointsTest.java
java -cp "$classes" com.nenotv.player.SiteEndpointsTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/ExtraPrivacyPolicy.java tools/tests/ExtraPrivacyPolicyTest.java
java -cp "$classes" com.nenotv.player.ExtraPrivacyPolicyTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/ExtraPrivacySession.java tools/tests/ExtraPrivacySessionTest.java
java -cp "$classes" com.nenotv.player.ExtraPrivacySessionTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/core/GuideMatcher.java tools/tests/GuideMatcherTest.java
java -cp "$classes" com.nenotv.player.core.GuideMatcherTest
