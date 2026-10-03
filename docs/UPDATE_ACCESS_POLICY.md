# Update access policy

Google Play's account/device eligibility decides which update is offered, including
internal testers. Dismissing the reminder no longer suppresses the next main-screen
resume. The dialog shows the installation deadline, remaining days, temporary Basic
access, and automatic restoration of still-valid subscription or tester access.

The 60-day deadline uses Play's client staleness when supplied. Otherwise it starts
when this installation first confirms the eligible update. The first confirmed
deadline is retained if a newer update supersedes it. Unknown/offline results cannot
start a deadline; a cached confirmed deadline remains in effect offline. An
authoritative UPDATE_NOT_AVAILABLE clears a withdrawn/ineligible requirement.

After 60 days, EntitlementStore exposes FREE (the existing Light/Basic tier) without
changing the underlying account entitlement, device identity, or preferences.
The Pro module is retained during this temporary restriction. Installing the required
version or a newer version removes the restriction on the next start. Valid Pro or
trial access returns automatically; revoked or expired rights never become Pro.
Downloaded-but-not-installed updates do not restore access. No Play release is
uploaded or published by this change.

This uses the existing Play in-app update integration. Devices/installations not
supported by that API cannot start a deadline without an eligible Play response.
The first app version containing this policy must be installed before it can enforce
future update deadlines; existing app binaries cannot be changed retroactively.

Verification: tools/test-update-policy.sh covers reminder and deadline boundaries.
The update instrumentation phase exercises real Android preferences, Basic access,
preserved entitlements, restoration, revoked/expired rights, clock rollback, and
withdrawn releases. verify-update-access.yml compiles the app and runs these checks
on an Android emulator. Existing phone/TV QA artifact paths match version 0.14.1.
