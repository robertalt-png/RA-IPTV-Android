# NenoTV website testomgeving

Deze aparte testomgeving draait WordPress 7.1.2/PHP 8.3, WooCommerce 11.1.2,
Mollie 8.1.10, een nieuwe database en Mailpit voor lokaal opgevangen e-mail.
De GitHub-runner maakt de omgeving tijdelijk aan en verwijdert deze na de test.
Dit is geen permanent gehoste testwebsite.

## Starten

Met Docker Compose, Bash, OpenSSL en Node 22:

```sh
bash website-qa/setup.sh
export QA_CHECKOUT_ID=$(cd website-qa && docker compose run --rm -T cli option get woocommerce_checkout_page_id)
node website-qa/smoke.mjs
```

Website: http://localhost:8090. Testmail: http://localhost:8025.
Voor lokale beheerlogin: stel met `docker compose run --rm cli user update qa_admin --user_pass=...`
een eigen tijdelijk wachtwoord in vanuit `website-qa/`. Het installatiepassword wordt niet opgeslagen.
Opruimen: `docker compose down -v` vanuit deze map, uitsluitend voor deze testdatabase.

## Afzondering

- Alleen nieuwe fictieve producten en gegevens; geen productie-import.
- Website, database en mailbox draaien op een intern Docker-netwerk.
- Browserpoorten luisteren uitsluitend op 127.0.0.1.
- Alleen de installatiecontainer kan WordPress-downloads ophalen.
- De QA-guard dwingt Mollie TEST af, wist de live sleutel bij bootstrap,
  blokkeert externe WordPress HTTP-aanvragen en stuurt mail naar Mailpit.
- Geen deploy naar nenotv.com, geen live sleutels, geen externe mailbox.
- Eigen NenoTV-plugin/theme-code, credentials en databases horen niet in deze openbare repository.

## Wat de controle bewijst

Vier fictieve producten zijn koopbaar, elk kan afzonderlijk in een winkelwagen,
de prijs klopt, de checkoutpagina opent en een proefmail komt lokaal binnen.
Dit is een basiscontrole, geen volledige besteltest en geen browser/UI-test.
De `qa-` SKUs en apparaatmetadata zijn fixtures; de Multi-limiet van vijf is
een voorlopig testgegeven en moet tegen de echte productconfiguratie worden gecontroleerd.

## Nog nodig voor de volledige besteltest

1. De eigen NenoTV theme/plugins via een private bron toevoegen en hun configuratie controleren.
2. De echte SKU/planmapping, apparaatlimieten en productvoorwaarden overnemen zonder klantdata.
3. Een afgeschermd HTTPS-testadres met publieke Mollie-webhookroute en alleen een TEST-sleutel.
   De huidige internetisolatie moet daarvoor gericht worden aangepast; geen tunnel naar productie.
4. Browserflows voor paid/failed/cancelled, orderstatus, entitlement, apparaatlimiet,
   My NenoTV, ordermail, refund/revoke en herhaalde betaalmeldingen uitvoeren.

De basiscontrole mag niet als launch-goedkeuring worden gebruikt.

Technische bronnen: [WordPress Docker](https://hub.docker.com/_/wordpress),
[Mailpit Docker](https://mailpit.axllent.org/docs/install/docker/).
