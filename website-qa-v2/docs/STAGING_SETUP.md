# Staging setup

Een echte end-to-end Mollie-test vereist een permanente HTTPS-staginghost.

## Verplicht
1. Hostnaam bevat staging/test/qa/acceptance en is NOOIT nenotv.com.
2. Aparte database zonder productieklantdata.
3. Dezelfde NenoTV theme/plugins als kandidaat-release.
4. WooCommerce producten/SKU's gelijk aan productieconfiguratie.
5. Mollie TEST API actief; live key niet aanwezig.
6. Publiek bereikbare Mollie webhook.
7. Testmailbox of sink; geen echte klantenmail.
8. WordPress noindex + HTTP auth/IP-beperking waar praktisch.
9. Aparte testaccounts, testorders, testdevices.
10. GitHub Environment `nenotv-staging` voor secrets/approval.

## GitHub variables/secrets
Variables:
- STAGING_BASE_URL
- STAGING_QA_ENABLED=true

Secrets indien later API-verificatie/refunds via Mollie API wordt toegevoegd:
- MOLLIE_TEST_API_KEY
- STAGING_QA_USER
- STAGING_QA_PASSWORD

Nooit opslaan:
- live Mollie key
- productie database dump
- klantgegevens

## Promotie
Staging is pas release-groen als paid/failed/cancelled/expired/pending, webhook replay, refund/chargeback, entitlement, Solo/Multi device limits, activatiecodes en upgradecases zijn bewezen.
