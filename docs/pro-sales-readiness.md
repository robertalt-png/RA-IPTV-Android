# Pro sales readiness

No release or public checkout is enabled by this change.

## Purchase confirmation

Verified delivery persists an encrypted Play purchase token before success. Unacknowledged purchases get a durable pending option and a server job containing only the entitlement id. Failed confirmation retries with backoff from 60 seconds up to one hour. Confirmation also rechecks whether the purchase is still valid; an inactive purchase is not acknowledged. A maintenance dispatcher recovers lost jobs and pages fairly through a backlog. Action Scheduler is preferred, with WP-Cron as fallback. The customer does not have to reopen the app.

Confirmation may be pending while verified Pro access is usable; the response explicitly carries `confirmation_pending`. Failure to store the encrypted purchase token or the durable pending marker is returned as an error. Existing customer, source and entitlement records are retained.

## Refunds and renewals

Subscriptions and lifetime purchases are checked on authenticated refresh and in scheduled background jobs. Successful checks are throttled for 15 minutes; network errors retry after one minute and do not revoke access. Cancellation keeps access through the paid period, payment hold and expiry stop access, and refunded lifetime access is revoked. Explicit administrator revocations and replaced entitlements are not reactivated by background workers. Catalog/source access invokes the same check so the catalog shortcut cannot skip purchase synchronization.

The server dispatcher runs every five minutes and schedules 50 entitlement checks per pass. Large backlogs take longer than 15 minutes; this is polling, not immediate RTDN. A reliable hosting cron/Action Scheduler runner must be verified before launch, including a retry with the customer app closed. Production Google Play callbacks can later shorten refund detection time.

## Checks before publication

- Both Android apps build successfully; verify the customer app on phone and TV.
- Run `tools/tests/play-billing-backend.php` with OpenSSL enabled, both with default WP-Cron fixtures and argument `action-scheduler`.
- Run the existing pairing/account tests and new feedback tests.
- On a Google Play test installation: purchase, close app before confirmation, prove background retry, restore purchase, cancel, refund/revoke and check Pro access.
- Verify the actual released artifact, signing, product/base-plan availability and Play production eligibility. Do not use a development version number as proof of distribution.
- Website checkout remains separately gated. Verify product terms, invoices and payment delivery before opening public web sales; saved tax confirmations are configuration, not a new verification of the business's tax position.
- Deploy the central feedback route and verify the paired admin phone inbox; AI analysis remains unconfigured.
- Obtain Robert's separate release/public-sales approval.

## Local PHP runtime

The bundled Windows PHP initially did not load OpenSSL. Enabling its existing OpenSSL extension makes the original pairing/account authorization suite pass. No change to the pairing logic was needed to resolve that test failure.

Verified locally: 148 purchase lifecycle checks with WP-Cron fixtures and 148 with Action Scheduler fixtures; 85 pairing/account checks; 72 feedback checks. A concurrent administrator revocation is protected against a stale in-flight Google response by comparing the stored state before writing. The live Google Play testing offer flag is preserved.

On 2026-10-12 the server repair and feedback API were deployed through SunnyIPTV Beheer with automatic backups and exact core patches. The live core includes additional quick-pairing/account work absent from this checkout: do not replace that full core with the repository copy. Apply only the reviewed include, trait, hooks and authenticated source-access check when deploying this change to an existing installation. Quick-pairing code and all unrelated live changes were retained.

The authenticated feedback inbox returns HTTP 200 and anonymous access returns HTTP 401. Existing site/admin status routes remain HTTP 200, sales switches are unchanged, and public web checkout remains disabled. No live purchase was made. The existing Action Scheduler has completed jobs and no overdue pending jobs at inspection time; the new purchase-retry job still needs an end-to-end Google Play sandbox purchase to prove recovery while the app is closed. The release-notification monitor follows the internal Google Play track; that monitor does not establish public production eligibility.
