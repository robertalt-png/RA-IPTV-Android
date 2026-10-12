# Google Play Testing

The closed Google Play track is the only test programme. There is no separate
SunnyIPTV application form, task portal, tester deep link or task telemetry.
Existing website records are preserved, but no longer recruit participants or
send test-task reminders when `sunnyiptv_google_play_only` is enabled.

The website's Testbeloningen screen requires the administrator to confirm
successful Google Play participation and supply the SunnyIPTV account email.
It then activates 365 days of Solo Pro and sends the thank-you email and admin
confirmation. Google Play participation is never inferred from a client email.
Only explicitly approved rewards are retried. The normal app account pairing
is still needed for secure Pro access, but no tester-specific pairing is needed.

The app's Account screen displays Google Play test information when the server
offer enables `google_play_test`. Trial refresh checks the entitlement endpoint
before using the catalogue shortcut, so an approved tester reward replaces the
trial automatically. Existing paid entitlements keep the catalogue path.

Version 0.14.47 / code 141 requires normal upload signing and publication to the
closed Play track. CI creates an unsigned AAB and a debug APK; it does not upload
or publish to Google Play. Set the real closed-test participation URL in the
website configuration before inviting participants.

