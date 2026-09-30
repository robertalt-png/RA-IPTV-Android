# NenoTV Automated QA

This directory is the deterministic QA harness for the NenoTV Android Free app. It must never contain a real IPTV subscription, customer playlist, password or token.

## Automated scope

The QA pipeline is designed to cover:

- clean install and first-run language selection;
- Xtream profile entry, authentication and invalid-login handling;
- M3U/M3U8 profile loading;
- Live TV categories, channel selection and playback;
- XMLTV and Xtream EPG current/next data;
- Movies and Series navigation and episode playback;
- player auto-hide, tap-to-show, pause/play, seek, favorite, audio/subtitle menus and aspect controls;
- search and provider ordering;
- rotation and background/foreground resume;
- network failures and malformed responses;
- Android lint, compile/build and manifest/permission checks;
- read-only reachability of the NenoTV WordPress REST route and fallback route;
- logcat crash/ANR scanning;
- screenshots and test reports as GitHub Actions artifacts;
- compatibility runs across multiple Android API levels.

The test IPTV service is synthetic. It generates its own short video/audio clips and exposes deterministic M3U, Xtream, XMLTV, HLS and MP4 endpoints from the GitHub runner. The emulator reaches that server through 10.0.2.2.

## What still needs real hardware

An emulator cannot fully prove vendor-specific hardware decoding, TV firmware, remote-control behavior, HDMI/audio-device behavior, DRM, all mobile-network conditions or every real IPTV-provider quirk. Those remain a short final real-device smoke test instead of the full manual regression suite.


## v0.12.6.7 retest
Triggered after the corrected v0.12.6.7 source build succeeded. The next green candidate will be promoted as v0.12.6.8 after any QA fixes are applied.
