# NenoTV Trial → Pro migration

Status: development branch only. Do not publish this build yet.

## Product state machine

```
TRIAL_REQUIRED
  -> PRO_TRIAL (30 days, server-authoritative)
  -> TRIAL_EXPIRED
  -> PRO
```

A successful paid Pro activation may move a device directly from an active or expired trial entitlement to Pro.

## Invariants

- No permanent Free playback after the trial model launches.
- NenoTV remains player software only. No IPTV channels, subscriptions, playlists, movies or series are included.
- No automatic charge and no automatic conversion from trial to paid Pro.
- Trial timing comes from nenotv.com. Device wall-clock changes must not extend the trial.
- Reinstalling the app must not create a new entitlement for the same trial account.
- Trial expiry must not intentionally delete source settings, favourites, history or Continue Watching data.
- Settings/account screens remain reachable after expiry.
- Playback is the first hard-gated operation after trial expiry.
- Paid Pro activation uses the same app and can move the current trial-linked device onto the paid entitlement.

## Backend contract staged on nenotv.com

App Bridge route:
- `POST /wp-json/nenotv/v1/entitlement/trial`

Backend route:
- `POST /wp-json/nenotv-backend/v1/entitlement/trial`

Trial payload:
- `level: pro_trial`
- `status: trial_active | trial_expired`
- `trial_state: active | expired`
- `expires_at_ms`
- `server_time_ms`
- `max_devices`
- `email`

Current server safety state:
- entitlement mode = shadow
- trial enabled = false
- trial days = 30
- public sales = disabled
- Mollie = test mode

The trial endpoint must therefore reject production activation until the launch gates are deliberately changed.

## Android dev1

Version: `0.13.0-trial-dev1` / versionCode 66.

Implemented on this branch:
- server-authoritative trial states in `EntitlementStore`
- trial start method and language propagation in `EntitlementClient`
- account screen for starting the 30-day trial and entering a Pro activation code
- playback gate that routes trial-required / expired users to the account screen
- paid Pro remains an allowed state

Still required before public use:
- merge the modular Pro feature architecture so an active trial actually exposes the complete Pro feature set
- add the 7-day countdown bar and final-day messaging
- add periodic entitlement refresh around playback/foreground transitions
- extend mock server + automated QA for trial start, expiry, reinstall, clock tamper and Pro conversion
- test paid activation from both active and expired trial states
- translate the trial-specific Android copy beyond EN/NL/DE or formally scope app release languages
- validate offline/reboot behavior and forced server revalidation
- align all website/public copy before enabling trial
