# NenoTV Pro donor inventory

Source/history basis: NenoTV v0.10.5 lineage, current v0.12.6.6 Free lineage, and the v0.9.8 Free/Pro entitlement patch set.

## APK size audit: v0.10.5 donor

The uploaded/built v0.10.5 universal APK is about **272.75 MiB** (285,995,469 bytes).

Compressed payload by ABI:

| ABI | Payload |
|---|---:|
| x86_64 | 78.54 MiB |
| arm64-v8a | 69.41 MiB |
| x86 | 66.40 MiB |
| armeabi-v7a | 50.32 MiB |
| DEX | 5.74 MiB |
| resources.arsc | 1.09 MiB |
| other resources/assets | < 1 MiB |

Largest native files:

| Native component | Approx. size per ABI |
|---|---:|
| VLC libvlc.so | 38–52 MiB |
| ML Kit translate JNI | 11–17 MiB |
| C++ shared runtime | 0.5–8.9 MiB |
| ML Kit language-id JNI | 0.6–1.3 MiB |

Conclusion: the Pro feature code itself is not the cause of the huge APK. The universal APK contains four copies of the heavy native media/translation stack.

## Confirmed feature classification

### Core / Free baseline
These belong in the base app and must remain usable without Pro:
- Xtream source
- M3U/M3U8 source
- Live TV
- Movies
- Series
- basic EPG
- search
- favorites
- recent / Continue Watching
- Media3 playback
- audio/subtitle track selection supplied by the stream
- speed/aspect controls
- source connection test
- encrypted local source credential storage
- NenoTV account/entitlement shell

### Lightweight Pro features
These have little size cost and should remain in the base app behind entitlement gates:
- advanced EPG timeline/grid
- parental PIN / adult-content hiding
- Picture-in-Picture
- recording UI and recording workflow
- Cast / TV playback controls
- external subtitle bridge
- advanced player settings
- Pro account/trial/device state UI

### Heavy Pro Media Pack
These should be delivered on demand:
- VLC compatibility playback
- ML Kit local metadata translation
- ML Kit language identification
- associated native C++ runtimes

### Not yet proven as a completed feature
Do not advertise as finished until implemented and QA-tested:
- true multiple-source profile management/switching
- production entitlement purchase/trial flow
- production device-limit enforcement
- production recording behavior across Android/device/storage variants
- provider-independent catch-up behavior
- multiview

The existing Pro benefit copy mentions multiple TV sources, but the recovered profile storage is still single-profile. Treat multiple sources as planned work, not as completed donor functionality.

## Architecture decision

NenoTV should remain **one Android application ID: com.robertalt.raiptv**.

- Base APK/AAB: Free + lightweight Pro code.
- Entitlement decides whether lightweight Pro controls unlock.
- Dynamic feature `proextras`: only heavy VLC/ML Kit payload.
- Pro users download the media pack only when required.
- Free users never download VLC/ML Kit.
- Google Play AAB delivery further prevents shipping all CPU architectures to one device.

## Migration order

1. Make the modular build compile.
2. Measure base module vs Pro Media Pack size.
3. Route Pro playback into the feature module.
4. Restore ML Kit translation via the feature bridge.
5. Add automated Pro entitlement tests.
6. Add automated Pro feature tests.
7. Port/verify advanced EPG, parental, PiP, recording, cast and external subtitles one bounded feature group at a time.
8. Implement multiple-source management as new work instead of treating it as recovered functionality.


## First modular build result — dev1

GitHub Actions run **36580961513** completed successfully.

Measured Android App Bundle:

- Total AAB: **123.43 MiB**. This is the store upload artifact and still contains all supported ABIs.
- Base module: **6.18 MiB compressed**.
- Pro Media Pack: **116.95 MiB compressed** in the AAB because it contains four ABI variants.
- Heavy VLC/ML Kit libraries in base module: **0**. The size gate passed.

Approximate delivered Pro Media Pack per CPU architecture, based on compressed module entries:

| ABI | Pro Media Pack |
|---|---:|
| arm64-v8a | **30.89 MiB** |
| armeabi-v7a | **26.52 MiB** |
| x86 | **29.30 MiB** |
| x86_64 | **32.95 MiB** |

For a normal modern arm64 device the expected delivered combination is therefore roughly **6.18 MiB base + 30.89 MiB optional Pro Media Pack**, before Play delivery overhead/optimization.

This validates the architecture: the previous ~273 MiB universal APK does not need to be shipped to every user.
