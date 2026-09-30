# NenoTV QA v2 master specification

## Architectuur
Static, Local/Unit, Instrumented Functional, Edition/Entitlement, Compatibility, Resilience/Fault, Performance, Security/Privacy en Release Evidence gates.

## Cadence
Smoke bij relevante QA-commits; Full handmatig/release; Release als harde promotiegate; Nightly voor soak/fault/security/performance.

## Edition-regels
Lite blijft zelfstandig werken zonder Pro-entitlement en mag geen actieve Pro-functionaliteit lekken. Pro ontsluit afgesproken functies alleen bij geldige entitlement. Verlopen, revoked, malformed, offline-cache en device-limit situaties worden expliciet getest.

## Localization
Locales dynamisch uit Android values-* resources. Checks: ontbrekende keys, placeholders, taalpersistentie, kernschermen, pseudo-locale, RTL, font scale en niet-ASCII/diacritics.

## Compatibility
Doel: API 26/30/34/35 en nightly 36; compact phone, phone, tablet en Android TV; portrait/landscape; font scale 1.0/1.3/1.5.

## Media/faults
Synthetische Xtream, M3U, XMLTV, HLS en MP4. Edge cases: ontbrekende artwork, rating null/0/invalid, series ordering, malformed JSON/XML, 401/403/404/429/500/503, timeout en connection drop.

## Permanente regressies
Posters/fallbacks; rating 0/null; EPG grid/list; episode sorting; volgende aflevering; bottom safe-area; fullscreen; 10 sec seek; Continue/Resume; categorienamen; Nederlandse metadata; seizoenskeuze.

## Security/privacy
Geen echte abonnementen of secrets. Geen tokens/licenties in logcat/resources. Entitlement niet alleen op wijzigbare lokale flags. Serverfout is nooit unlock. MASVS als security-checklist.

## Releasegates
Geen release bij build/install failure, crash/ANR, credential leak, ongeautoriseerde Pro unlock, core playback failure of upgrade-dataverlies.

## Waarheid
De projectbron bevat 288 gespecificeerde tests. GitHub rapporteert expliciet welke tests automated, specified, manual_release, quarantined of retired zijn.
