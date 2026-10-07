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
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/core/GuideWindow.java tools/tests/GuideWindowTest.java
java -cp "$classes" com.nenotv.player.core.GuideWindowTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/core/XtreamUrls.java android/app/src/main/java/com/robertalt/raiptv/core/CatchupUrls.java tools/tests/CatchupUrlsTest.java
java -cp "$classes" com.nenotv.player.core.CatchupUrlsTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/core/GuideSources.java tools/tests/GuideSourcesTest.java
java -cp "$classes" com.nenotv.player.core.GuideSourcesTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/core/CatchupTemplate.java tools/tests/CatchupTemplateTest.java
java -cp "$classes" com.nenotv.player.core.CatchupTemplateTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/model/MediaEntry.java android/app/src/main/java/com/robertalt/raiptv/core/M3uParser.java tools/tests/M3uCatchupTest.java
java -cp "$classes" com.nenotv.player.core.M3uCatchupTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/core/NameCleaner.java tools/tests/NameCleanerTest.java
java -cp "$classes" com.nenotv.player.core.NameCleanerTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/model/MediaEntry.java android/app/src/main/java/com/robertalt/raiptv/ContentLanguage.java android/app/src/main/java/com/robertalt/raiptv/core/LanguageGroups.java tools/tests/LanguageGroupsTest.java
java -cp "$classes" com.nenotv.player.core.LanguageGroupsTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/core/NumberZap.java android/app/src/main/java/com/robertalt/raiptv/core/Reconnect.java tools/tests/ZappingReconnectTest.java
java -cp "$classes" ZappingReconnectTest
javac -d "$classes" android/app/src/main/java/com/robertalt/raiptv/core/Reconnect.java android/app/src/main/java/com/robertalt/raiptv/core/RecordingPlan.java android/app/src/main/java/com/robertalt/raiptv/core/RecordingEngine.java tools/tests/RecordingTest.java
java -cp "$classes" RecordingTest
# UiText must stay under the JVM 64 KB method limit (one initialiser method per language).
mkdir -p "$classes/uitext"
python3 - "$classes/uitext/UiText.java" <<'PY'
import re, sys
s = open('android/app/src/main/java/com/robertalt/raiptv/UiText.java', encoding='utf-8').read()
s = re.sub(r'^import (android|com\.nenotv).*\n', '', s, flags=re.M)
i = s.index('    public static String t(Context c')
open(sys.argv[1], 'w', encoding='utf-8').write(s[:i] + '}')
PY
javac -d "$classes/uitext" "$classes/uitext/UiText.java"
echo "UiText: compiles within the method size limit"
