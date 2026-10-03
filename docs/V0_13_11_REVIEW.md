# v0.13.11 review

One source tree (`android/`), one application ID, one version (91 / 0.13.11), Light base and entitlement-gated Pro module. The universal test APK is derived from the Play AAB. The original v0.13.10 source came from tested run 37109459128.

Implemented, awaiting new Android validation:
- Artwork fallback during loading, on failed HTTP/decode, and on recycled views; successful images restore the correct crop mode.
- Missing/zero/invalid ratings omitted; valid source scores retained, including cached favorites/recent items.
- One normalized, timestamped EPG cache and query size across grid/list/cards/details; no fabricated timestamps; exact timeline widths and readable current-programme contrast.
- Numeric episode sorting, an explicit completed flag, resume of the most recently opened incomplete episode, next episode after completion, and a season selector. The playback queue spans all seasons.
- Durable progress on player exit; Home refreshes after playback, shows remaining time, favorites, progress and live now information.
- One owner of browsing insets and bottom padding; real immersive Light and Pro players; clearly labelled +/-10 seconds and capped seeking.
- Compact import banner; counts in Settings; title/card readability at larger system fonts.
- Film details with poster/backdrop, valid score, metadata, Play/Continue, favorite action, collapsible cast and preserved demo attribution links.
- Consistent Light/Pro naming; internal legacy FREE entitlement values are retained for compatibility.
- Fused Pro payload detection for universal APKs, with the same entitlement gate as Play; Pro demo expiry at start and during playback; shared demo retry policy.
- Android TV launcher declaration and banner using the existing brand, plus remote focus indicators.

Existing provider authentication, streaming import, category batching, cache paging and cancellation logic is preserved. New tests cover regression cases on the actual release-derived APK. The 30-day trial is still local to installation; external streams can interrupt/rate-limit. Casting, recording and subtitle-provider integrations require separate real-device/provider validation and are not claimed as newly verified.

No Play Store publication or signing-key change is performed.
