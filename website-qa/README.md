# NenoTV website testomgeving

Deze aparte testomgeving draait WordPress 7.1.2/PHP 8.3, WooCommerce 11.1.2,
Mollie 8.1.10, een nieuwe database en Mailpit voor lokaal opgevangen e-mail.
De GitHub-runner maakt de omgeving tijdelijk aan en verwijdert deze na de test.
Dit is geen permanent gehoste testwebsite.

## Starten

Met Docker Compose, Bash, OpenSSL en Node 22:

```sh
bash website-qa/setup.sh
QA_CHECKOUT_ID=$(cd website-qa && docker compose run --rm -T cli option get woocommerce_checkout_page_id) || exit 1
export QA_CHECKOUT_ID
node --test website-qa/checks.test.mjs
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
- Een aparte downloader haalt uitsluitend de plugin-ZIPs op. Deze container heeft geen
  WordPressbestanden of databaseverbinding. WordPress en WP-CLI hebben geen internetroute.
- De nginx-toegangspoort heeft alleen vaste lokale upstreams en luistert op loopback.
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

## Auditcorrecties

De beheercontainer is nu ook van internet afgesloten; directe cURL wordt in beide
PHP-containers getest. Een ontbrekende checkout-ID stopt de workflow en de test zelf.
Checkoutcontrole vereist de juiste pagina-ID plus een echt checkoutblok of formulier.
De fixture gebruikt NL 21% btw met prijzen inclusief btw, zoals de twee bevestigde
productieopties. Andere landen, vrijstellingen en de volledige productietarieven zijn niet nagebouwd.
Pluginversies worden na installatie gecontroleerd. Mollie-betaalmethodes blijven
bewust ongeconfigureerd in deze basisomgeving; betaaltests zijn nog niet uitgevoerd.
Docker-tags en action-versietags zijn nog niet op inhoudshashes vastgepind.
Een sessie die WordPress.org blokkeert kan de setup niet uitvoeren; vraag daar
netwerktoegang voor de officiele downloads aan. Omzeil het sessiebeleid niet.

Technische bronnen: [WordPress Docker](https://hub.docker.com/_/wordpress),
[Mailpit Docker](https://mailpit.axllent.org/docs/install/docker/).
