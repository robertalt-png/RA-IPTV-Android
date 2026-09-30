# NenoTV QA v2 implementatieroadmap

## A — bestaande QA stabiliseren
- API35-suite betrouwbaar groen maken.
- Flaky waits verwijderen.
- Tests per domein opsplitsen.
- Mockserver-contract vastleggen.

## B — edition awareness
- Lite en Pro bronartifact-selectie.
- Pro entitlement fixtures.
- single/5-device/expired/revoked/offline/device-limit.
- Lite feature-leak tests.

## C — talen
- automatische locale discovery;
- translation completeness en placeholders;
- locale kernflows;
- pseudo-locales, RTL en font scale.

## D — compatibiliteit
- API 26/30/34/35/36;
- telefoon/tablet/TV;
- orientation/config changes.

## E — regressies
Permanente asserts voor bekende NenoTV-problemen: posters/fallbacks, 0/null ratings, EPG grid/list, episode sorting, next episode, bottom safe-area, fullscreen, 10-sec seek, Continue/Resume, categorie-opmaak, metadata en seizoenskeuze.

## F — resilience
Netwerkprofielen, malformed data, process-kill/recovery, upgrades en migrations.

## G — security/performance/accessibility
MASVS-gebaseerde checks, secret/log scanning, dependency inventory, Macrobenchmark/baselines en accessibility.

## H — release gate
Eén evidence report en promotie alleen bij groene verplichte gates.
