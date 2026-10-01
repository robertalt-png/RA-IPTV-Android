# Commerce testmatrix

## Plannen
- Pro Solo Yearly — 8.99 EUR — 1 device
- Pro Solo Lifetime — 17.99 EUR — 1 device
- Pro Multi Yearly — 19.99 EUR — max 5 devices
- Pro Multi Lifetime — 39.99 EUR regulier / 29.99 EUR launch — max 5 devices

## Talen
EN / NL / DE.

## Payment states
paid, pending, open, failed, cancelled, expired.

## Invariants
- failed/cancelled/expired => nooit Pro.
- paid => exact één entitlement.
- webhook replay => geen dubbel entitlement.
- full refund => revoke.
- partial refund => geen automatische revoke; incident/review.
- chargeback => revoke en upgrade terugdraaien waar relevant.
- Solo => device 2 blokkeren.
- Multi => device 6 blokkeren.
- device verwijderen => vrij slot.
- activatiecode one-time/rotatie/replay/race.
- gebruiker A kan nooit order/device/entitlement van B beheren.
- checkoutprijs = order = factuur = btw-som.
- EN/NL/DE ordertaal blijft behouden in checkout, e-mail, account en factuur.

## Volgorde/race
- browser return voor webhook.
- browser return na webhook.
- duplicate webhook x2/x10.
- dubbelklik Place order.
- refresh/back.
- refund tijdens grant.
- revoke tijdens activatie.
- twee devices gebruiken code tegelijk.
