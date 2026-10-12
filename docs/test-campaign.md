# Limited test campaign

The campaign is opt-in through the existing customer account/device pairing
and explicit trial consent. Installing or logging in does not grant access.
An administrator opens the campaign once. The first 25 distinct eligible
accounts refreshing an active account-linked trial are enrolled; an existing
active trial may join. A second device never consumes another account slot.
Revoked participants keep their slot, so the lifetime cap remains 25.

Only these active trial rows lose their fixed expiry while the test runs.
Paid, revoked, expired, email-only and administrator entitlements are not
converted. Nonparticipants keep the configured ordinary 14-day trial.
The server supplies the boolean test_access; the Android app shows Test only
with actual active trial access. A client request cannot nominate itself.

Google production access must be verified by a human administrator. The
authenticated management page (Admin-app koppelen > Testperiode) provides
a required confirmation before closing enrollment and granting 365 days
of promotional Pro for one device. The year starts at that confirmation,
not at enrollment. This is not an automatically renewed subscription.
There is no Google approval polling or automatic public release.

Campaign enrollment/approval share a database lock. Options are refreshed
after acquiring it. Conditional row updates preserve concurrent purchases
and revocations. Approval is idempotent; retries use the original timestamps
and cannot extend the year. Failed writes retry during account refresh or
existing daily maintenance. The original trial source reference remains
to prevent an account starting a new ordinary trial after its reward.

The paired admin app stays read-only. GET sunnyiptv-admin/v1/test-campaign
returns aggregate counts; POST requires a website administrator rather
than the paired admin app bearer token. No customer identifiers are exposed
in those aggregate responses.
