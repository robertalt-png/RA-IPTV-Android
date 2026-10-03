# NenoTV v0.13.12 — overname en afwerking

## Uitgangspunt

De basis is commit `83a44a6bccfde791384c1ae2c325ceef2372264a`, getest in GitHub-run `37117342185`. De latere v0.13.11-commits wijzigen uitsluitend de overdracht. De vorige export verwees naar run `37116659717`, waarvan de volledige testmatrix faalde. De overnamebranch verwijst voor de bewaarde v0.13.11-basis naar de succesvolle run.

## Deze reparatie

- Versie `0.13.12`, versiecode `92`, application ID `com.nenotv.player`.
- Op een smal scherm staan de acties in het filmdetailvenster onder elkaar. De hoogte past zich aan de tekst aan, zodat zowel toevoegen als verwijderen van een favoriet leesbaar blijft bij grotere systeemletters.
- In smalle EPG-blokken staan de volledige begin- en eindtijd op afzonderlijke regels. De positie en breedte van de programma's blijven door hun echte tijdstempels bepaald. Zeer kleine blokken tonen een titel; de volledige tijden staan in de toegankelijkheidstekst en het detailvenster.
- Extra regressiecontrole meet of de favorietenlabels horizontaal en verticaal passen, in NL/EN/DE en voor beide knoptoestanden.
- Provider-, import-, cache- en hervatcode is ongewijzigd.

## Oplevering

Eén bronboom in `android/`, één app, Light als basis en Pro als module die met de licentie wordt ontsloten. Het test-APK wordt uit hetzelfde AAB gegenereerd. De universele test-APK bevat de Pro-module; de Play-versie gebruikt on-demand delivery.

Het AAB blijft ongetekend. De APK heeft een tijdelijke testsleutel. Voor upload naar de bestaande Play-app is de juiste upload signing key nodig. Deze test-APK is geen update voor een anders ondertekende installatie.

## Grenzen van het bewijs

De bestaande suite controleert onder meer een lokale import van 5.000 items, annuleren en rollback, hervatten na force-stop, demo-afspelen, gedeelde EPG-data, afleveringsvolgorde, voortgang en Pro-runtime. Een telefoonemulator wordt tevens op tabletformaat getest. Dit is geen meting van de laadtijd van de eigen providerlijst op een echte tv.

De bestaande demo heeft 19 items. Een lokale proefperiode duurt 30 dagen; deze is geen server-side accountbrede proefperiode. Casting, opnemen en externe ondertiteldiensten zijn niet opnieuw op echte apparaten of met providers bewezen. De Mijn NenoTV-koppeling en nieuwe Pro-roadmap behoren tot de volgende uitbreiding.

Er is geen Play Store-upload of uitrol gedaan. Definitieve testuitslagen en checksums staan in het afzonderlijke releaseverslag.
