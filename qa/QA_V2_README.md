# NenoTV QA v2 — Testomgeving

Deze branch is de GitHub-implementatie van de projectbron `NenoTV_QA_Testomgeving_v1.0`.

## Veiligheidsregel
- Geen productiecode op `main` wijzigen vanuit QA.
- Geen echte IPTV-accounts, klantdata, wachtwoorden, tokens of licenties in tests.
- De bestaande deterministische mock IPTV-server en instrumented tests blijven de basis.
- Lite en Pro worden als aparte editions getest.
- Talen worden uit Android resources ontdekt.
- Release is nooit groen op alleen een geslaagde build.

## Testlagen
1. Smoke — iedere relevante QA-commit; snelle static + API35 kernflow.
2. Full — handmatig; Lite/Pro, meerdere API-levels, locales en form factors.
3. Release — harde promotiegate met blocker/critical beleid en evidence.
4. Nightly — soak, fault injection, security, performance en brede compatibiliteit.

## Huidige brondefaults
- Lite branch: `nenotv-v0.12.6.7-free-qa-executorfix`
- Lite workflow: `build-v096.yml`
- Lite artifact: `NenoTV-Free-*-source`
- Pro branch: `nenotv-pro-modularization-v0.2-qa-splitfix`
- Pro workflow: `build-pro-modular.yml`
- Pro artifact: `NenoTV-Pro-Modular-*-source`

Deze defaults zijn inputs/configuratie, geen permanente productversie-lock.

## Canonieke projectbron
De volledige project-ZIP bevat 288 testspecificaties. SHA-256 van de canonieke catalogus:
`21a6ee5cdd473e0677997303791a1b9e595b16e386acbf9fba74bebf1a2a39ba`.

Implementatie wordt gefaseerd: eerst bewezen kernflows automatiseren, daarna edition/entitlement, talen, compatibiliteit, regressies, resilience, security/performance/accessibility en uiteindelijk een harde release gate.
