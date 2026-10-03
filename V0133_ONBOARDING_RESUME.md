# NenoTV v0.13.3 — simplified source setup + resumable import

This branch builds on the proven v0.13.2 Light/Pro architecture.

## Source setup
- Language selection remains unchanged.
- TV source setup is reduced to three choices: Xtream Codes, M3U, or 30-day NenoTV demo.
- Xtream shows only server, username and password.
- M3U shows only the M3U URL.
- Profile name and EPG URL live under Advanced settings.
- The old separate Connection test button is replaced by Connect and continue; authentication is performed automatically before saving.
- NAS subtitle bridge URL/token is removed from first-run setup and moved to Settings.

## Demo source
No playlist URL is invented or committed to this repository.
The one-tap demo becomes functional only when the GitHub/Play build environment supplies a verified legal source in:
NENOTV_DEMO_M3U_URL

If no verified source is configured, the app explains that the demo source is not configured instead of shipping a fake playlist.

The current 30-day timer is local app state. If NenoTV later needs reinstall-proof enforcement, move the demo entitlement to the NenoTV backend.

## Import durability
- SQLite DB version 3 adds persistent category cache and import progress.
- First-sync batches are written durably as they arrive.
- Unfinished staging imports survive process death.
- Category cursor and item count are checkpointed.
- Restart shows already stored titles instead of an apparent zero state.
- Existing completed snapshots remain visible during refresh; incomplete refresh data is not promoted over a valid snapshot.
- Category lists are cached on disk, so reopening a section does not need to wait for category downloads before it can render.

## QA
Android instrumentation now includes a process-boundary resume scenario:
1. create a partial import and checkpoint it;
2. finish the instrumentation process;
3. force-stop the app;
4. start a second instrumentation process;
5. verify rows, cursor and counts survived;
6. resume and commit the final snapshot.

The existing Light gate still checks that proextras is absent from Light.
