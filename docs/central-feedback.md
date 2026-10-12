# Central customer feedback

## Components

- Customer app: Settings > Feedback; complaint, comment or tip; explicit send action.
- Central API: `POST /wp-json/nenotv/v1/feedback`.
- Admin app: bell opens feedback, filters, pagination and a local read watermark.
- Protected admin API: `GET /wp-json/sunnyiptv-admin/v1/feedback?after=ID&before=ID`.
- Background notifications: WorkManager checks approximately every 15 minutes with network access. Android may defer checks; these are not immediate push notifications. Foreground badge refreshes every minute.

## Deployment

Upload `server/sunnyiptv-feedback.php` alongside the Entitlement Core files and update `nenotv-entitlement-core.php` from this change. First WordPress init creates a separate `sunnyiptv_feedback` table. No existing entitlement, license, source or customer table is changed. Apply the backend before distributing either app; an older backend shows a retryable error and the customer message remains on screen. Nothing is deployed by this source change.

The customer must have a linked Free or paid device. Failed delivery keeps the message; retrying the same message uses the same request id to avoid duplicates. An edited message receives a new id. Rate limits apply before insertion. Admin access uses the existing paired token or WordPress administrator authorization. Read status belongs to the admin phone; reading on one phone does not dismiss alerts on another.

## Stored data

Message, kind, app version, Android version, device manufacturer/model and UTC timestamp. Device keys authenticate the request but are not stored as feedback. An HMAC of device id plus request id provides deduplication. No playlist, license token, email or payment data is included automatically. Customers are asked not to put secrets in free text. Existing feedback is not automatically deleted.

## AI analysis

This change provides the central inbox. AI analysis is not connected yet: no AI service, credentials or data-processing policy were configured. It must keep original messages intact, receive only necessary text/metadata, and show generated grouping and summaries as suggestions. Add customer disclosure before activating external AI processing. Do not send feedback to an external AI service simply by installing this version.

## Acceptance checks

1. Unlinked customer gets an account-link message; no feedback is inserted.
2. Linked customer submits each kind and receives confirmation with no entitlement changes.
3. Retry after a lost response returns the same id; changing message or kind produces a new submission.
4. Unpaired admin cannot list feedback; a paired phone lists newest messages and can load older pages.
5. New complaints increment the badge and background notification; comments/tips do not trigger complaint alerts.
6. Marking read clears complaints through the fetched latest id. Complaints arriving afterward remain unread.
7. Logout cancels the worker and visible notification; denied notification permission still allows the inbox/badge.

Android build and on-device validation are still required before a release.

Local validation: `tools/tests/feedback-backend.php` passes 72 behavioral checks with WordPress/database fixtures. PHP syntax checks pass for the new feedback module and the modified Entitlement Core. Enabling the bundled PHP OpenSSL extension resolves the original source-vault test failure: all 85 pairing/account authorization checks pass without a pairing-code change.
