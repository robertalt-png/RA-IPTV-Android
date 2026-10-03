# NenoTV 0.14.0 — Light + Pro release scope

## One app, two entitlement levels

NenoTV remains one Android app. Light is the base application and owns provider login, playlist loading, cache/indexing and the basic player. Pro is an on-demand dynamic feature and must never duplicate or replace the Light loading pipeline.

## NenoTV Light

- M3U and Xtream source connection.
- Existing fast progressive playlist/library loading.
- Live TV, movies and series browsing.
- Basic EPG/list view.
- Search, favorites, watch progress and resume.
- Language-aware UI/content ordering already present in the base.
- Basic Media3 player and the existing demo path.
- Local use does not require My NenoTV source sync.

## NenoTV Pro

Includes the existing Pro media/player capabilities plus:

- Multiple encrypted IPTV sources.
- Optional Smart Merge: active source stays first/fast, secondary sources load afterward, duplicates are collapsed and alternate stream URLs are preserved as playback fallbacks.
- Existing single source is migrated automatically into the source registry.
- Select active source without changing the Light provider/import architecture.
- Enable/disable and prioritize sources.
- Add/edit/remove sources.
- My NenoTV source sync using the existing NenoTV device/account identity.
- Smart EPG: up to 8 encrypted extra XMLTV/EPG URLs per source, automatically matched by tvg-id/channel name only when normal provider EPG is empty.
- Source vault is encrypted at rest on the NenoTV server.
- My NenoTV displays source name/type only; credentials are never shown back in full.
- Advanced EPG/timeline remains Pro-gated.
- Existing Pro language optimization, casting, PiP, recording/subtitle/player features remain in the dynamic module.
- Network & Privacy screen with active VPN-route detection and connection latency diagnostics.
- Provider diagnostics use only scheme/host/port and never include username/password/path/query credentials.

## Server integration

Entitlement Core 0.1.16 / DB schema 4 provides the encrypted source vault keyed to the account entitlement. v0.1.16 preserves Smart EPG URLs and existing optional bridge settings during source sync. Public app endpoints:

- POST /wp-json/nenotv/v1/sources/pull
- POST /wp-json/nenotv/v1/sources/push

Both require a valid active linked device ID + device key and an active Pro/trial entitlement. The source-sync endpoints stay blocked while the entitlement service is in prelaunch/shadow mode.

## Release safety

- Do not enable production entitlement mode merely to test this branch.
- Do not upload a QA-signed APK to Play.
- Play AAB remains the canonical package; Pro is delivered on demand.
- QA exports include a Light-only APK and a Pro universal APK, both generated from the exact same Play AAB with the disposable QA key.
- Final release requires the full existing phone/tablet/TV regression matrix plus explicit Light APK separation, multi-source dedupe/fallback, Smart EPG fallback and My NenoTV sync-payload tests.
