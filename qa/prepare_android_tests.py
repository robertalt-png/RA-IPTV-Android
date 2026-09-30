#!/usr/bin/env python3
from pathlib import Path
import shutil

root = Path.cwd()
app = root / "app"
gradle = app / "build.gradle"
s = gradle.read_text()

if 'testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"' not in s:
    s = s.replace("defaultConfig {", 'defaultConfig {\n        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"', 1)

if "testOptions" not in s:
    s = s.replace("    compileOptions {", "    testOptions { animationsDisabled = true }\n\n    compileOptions {", 1)

deps = [
    "    testImplementation 'junit:junit:4.13.2'",
    "    androidTestImplementation 'androidx.test:runner:1.6.2'",
    "    androidTestImplementation 'androidx.test:rules:1.6.1'",
    "    androidTestImplementation 'androidx.test.ext:junit:1.2.1'",
    "    androidTestImplementation 'androidx.test.uiautomator:uiautomator:2.3.0'",
]
for dep in deps:
    if dep not in s:
        s = s.replace("dependencies {", "dependencies {\n" + dep, 1)

gradle.write_text(s)

dst = app / "src/androidTest/java/com/robertalt/raiptv"
dst.mkdir(parents=True, exist_ok=True)
src = Path(__file__).parent / "androidTest/java/com/robertalt/raiptv/NenoTvAutomatedQaTest.java"
shutil.copy2(src, dst / src.name)

print("NenoTV QA instrumentation prepared")
