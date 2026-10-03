# NenoTV Admin Android

Simple read-only Android admin companion for NenoTV.

## v0.1.0
- Pair once with the existing 10-character NenoTV Admin pairing code.
- Securely stores the bearer token with Android Keystore (AES-GCM).
- Dashboard period dropdown: Today / This week / This month.
- Simple overview of revenue, orders, visitors, Pro access and service health.
- Customer summary, order summary and advanced details behind a separate view.
- Uses the existing read-only NenoTV endpoint at `https://nenotv.com/wp-json/nenotv-dashboard/v1/admin-app`.
- No WordPress write operations are available from the app.

Package: `com.nenotv.admin`
