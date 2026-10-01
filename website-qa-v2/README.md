# NenoTV Website QA v2

Enterprise website/commerce QA bovenop de bestaande geïsoleerde `website-qa`.

## Twee testlagen

### 1. GitHub isolated checkout
GitHub Actions bouwt tijdelijk WordPress + WooCommerce + Mailpit. Een QA-only gateway maakt een volledige synthetische bestelling af zonder internet of geld:
product -> cart -> checkout -> payment success -> Woo order -> QA entitlement -> activatiecode -> e-mail -> thank-you.

Dit bewijst de browser/WooCommerce-keten, maar NIET de echte NenoTV entitlementplugin of Mollie-webhook.

### 2. HTTPS staging + Mollie TEST
De staging-suite is destructief maar uitsluitend toegestaan op een aparte HTTPS staginghost. Zij test:
EN/NL/DE, alle vier plannen, paid/failed/cancelled/expired/pending, webhook-volgorde, duplicate/replay, refund, partial refund, chargeback, entitlement, Solo/Multi device limits, activatiecodes en upgrades.

## Harde veiligheid
- `nenotv.com` en `www.nenotv.com` zijn altijd production-readonly.
- De staging-guard weigert productie expliciet.
- Geen live Mollie-key in repository of artifacts.
- Lokale GitHub checkout gebruikt alleen `example.invalid` e-mail.
- Staging credentials horen uitsluitend in GitHub Environments/Secrets.
- Geen klantdata kopiëren naar staging.

## Lokaal
```bash
bash website-qa/setup.sh
cd website-qa-v2
npm install
npx playwright install --with-deps chromium
npm run qa:local
```

## Staging
Vereist:
- STAGING_BASE_URL
- STAGING_QA_ENABLED=true
- Mollie TEST geconfigureerd op staging
- NenoTV eigen theme/plugins op staging
- publiek bereikbare HTTPS webhookroute

```bash
STAGING_BASE_URL=https://staging.example.test STAGING_QA_ENABLED=true npm run qa:staging
```

Zie `docs/COMMERCE_MATRIX.md` en `docs/STAGING_SETUP.md`.
