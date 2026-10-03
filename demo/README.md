# NenoTV demo playlist

This release includes a reviewed, two-entry playlist: Big Buck Bunny with adaptive HLS and a 480p HLS version. These are short-film playback demonstrations, not a live television package. No invented EPG data is supplied.

Big Buck Bunny (2008), copyright Blender Foundation / www.bigbuckbunny.org, is licensed under Creative Commons Attribution 3.0: https://creativecommons.org/licenses/by/3.0/ . Attribution is included in the visible entry names and playlist comments. License source: https://peach.blender.org/about/ . The unmodified film is delivered by the public test host https://test-streams.mux.dev/ . No Blender trademark endorsement is implied.

The playlist is compiled into `DemoSource` and read through the reserved `nenotv://demo/v1` source. Normal M3U and Xtream behavior remains available. The demo workflow explicitly selects this source for both builds; an unset GitHub secret cannot disable it. Internet is still necessary for media playback.

## Release validation

The Android instrumentation `demo` phase opens the real onboarding activity, taps its demo choice, verifies the persisted profile and unchanged expiration on repeated activation, parses the actual packaged playlist, then uses Media3 on an Android surface for every entry. Each must render a video frame, expose an audio format and advance playback by at least 1.5 seconds without a player error. It also checks expiration after profile renaming, disables the expired demo in Dutch, English and German, and allows a user's own source after demo expiration. A second instrumentation process verifies that the saved demo profile, playlist and original expiry survive a force-stop without restarting the trial. The build fails unless `NENOTV_DEMO_TESTS=passed` appears for both Light and modular base.

Existing import and process-restart tests remain in the workflow. Playback checks use live upstream streams and therefore fail when an upstream endpoint is unavailable; such a failure must be investigated rather than bypassed.

The 30-day timer remains local to the installation. Reinstall-proof enforcement and protection against device-clock manipulation require a server-backed trial entitlement and are not claimed by this release. Playback source uptime and all physical device combinations cannot be guaranteed by an emulator test.

Versions: Light 0.13.9-light-test / code 87; modular Play 0.13.9-play1 / code 88. The AAB is unsigned and is not automatically published to Google Play.
