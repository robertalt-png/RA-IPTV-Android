# NenoTV Android — one app

The canonical source is `android/`. NenoTV Light is the base; Pro is an entitlement-gated, on-demand feature module within the same application (`com.nenotv.player`). There is no separate Play edition: Google Play distributes this app.

The `build-nenotv.yml` workflow produces the Play AAB and derives the universal test APK from that exact bundle. Both have version 0.13.11 / code 91. The universal test APK includes the Pro payload for sideload testing; paid features still require entitlement. Its QA signature cannot replace a Play-signed installation. The release AAB remains unsigned until the authorized upload-signing step.

The packaged 30-day demo contains 17 unique open films and two EU live channels. Trial state is local to the installation. Stream availability remains dependent on external providers.

Historical patch scripts and workflow sources are retained for traceability. They are not the canonical build path. The invalid default `build-apk.yml` was archived under `docs/legacy-workflows/`.

QA covers the release-derived APK: large imports/cancellation/restart; all demo streams; localized UI, seasons, progress/completion, shared EPG and artwork failures; phone with enlarged fonts, tablet-sized layout, Android TV remote focus, and actual Light/Pro players. Test results are required before this version is considered ready. No Store publication is performed by this workflow.
