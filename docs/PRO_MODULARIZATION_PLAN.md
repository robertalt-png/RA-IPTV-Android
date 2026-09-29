# NenoTV Pro modularization plan

## Baseline
- Free baseline: v0.12.6.6 / versionCode 64.
- Pro donor lineage: v0.10.5 and later Pro branches.
- Do not merge Pro changes into the Free release branch until the Free automated QA gate is green.

## Architecture
1. **Core / Free**
   - source profiles
   - Live TV
   - Movies
   - Series
   - EPG
   - search
   - favorites
   - Media3 player
   - account/entitlement client shell

2. **Pro Features**
   - multiple sources
   - advanced favorites/history
   - parental controls
   - extra player/settings features
   - catch-up / recording UI where supported
   - device/account Pro state
   - future sync/multiview modules

3. **Pro Media Pack**
   - VLC/compatibility playback
   - heavy native media libraries
   - optional language/translation models or other large native payloads

## Size policy
- Keep the base module small.
- Do not ship x86/x86_64 native libraries to production devices unless a specific distribution target requires them.
- Use Android App Bundle ABI splits for Play distribution.
- Put genuinely heavy optional features in dynamic/on-demand delivery where practical.
- Keep direct-download APKs architecture-specific if heavy native media support is bundled.

## Migration method
- Recover Pro functionality from source/history, not by decompiling the v0.10.5 APK unless source is missing.
- Inventory every Pro-only feature and its dependencies.
- Port one bounded feature group at a time into this branch.
- Run Free regression QA after every Pro integration step.
- Add a dedicated Pro QA suite using synthetic Xtream/M3U/EPG/media data.
- Add entitlement-state tests for Free, Pro, expired, revoked, offline-cache and server-error states.
- Measure APK/AAB size after each feature group.

## Release gates
- Free automated QA green.
- Pro automated QA green.
- No regression in Free behavior when Pro is unavailable.
- Heavy native libraries are not in the base APK unless technically required.
- Entitlement production mode remains off until commerce/tax/payment launch gates are completed.
