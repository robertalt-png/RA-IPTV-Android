# NenoTV v0.13.12 — overname en afwerking

## Uitgangspunt

De releasekandidaat is versie `0.13.12`, versiecode `92`, application ID `com.nenotv.player`. De appcode van deze kandidaat is getest op commit `e76e29715ae267dc4112a94861e268e646a7877f` in GitHub Actions-run `37119210761`.

Na die groene build zijn alleen de export-workflow en overdrachtsdocumentatie aangepast. De appcode in `android/` is daarna niet meer gewijzigd. Daardoor blijft run `37119210761` het geldige releasebewijs voor de huidige appcode op de overdrachtbranch.

## Deze reparatie

- Op een smal scherm staan de acties in het filmdetailvenster onder elkaar. De hoogte past zich aan de tekst aan, zodat zowel toevoegen als verwijderen van een favoriet leesbaar blijft bij grotere systeemletters.
- In smalle EPG-blokken staan de volledige begin- en eindtijd op afzonderlijke regels. De positie en breedte van de programma's blijven door hun echte tijdstempels bepaald.
- Extra regressiecontrole meet of de favorietenlabels horizontaal en verticaal passen, in NL/EN/DE en voor beide knoptoestanden.
- Provider-, import-, cache- en hervatcode is ongewijzigd.

## Oplevering

Eén bronboom in `android/`, één app, Light als basis en Pro als module die met de licentie wordt ontsloten. Het test-APK wordt uit hetzelfde AAB gegenereerd. De universele test-APK bevat de Pro-module; de Play-versie gebruikt on-demand delivery.

Het AAB blijft ongetekend. De APK heeft een tijdelijke testsleutel. Voor upload naar de bestaande Play-app is de juiste upload signing key nodig. Deze test-APK is geen update voor een anders ondertekende installatie.

## Definitieve validatie

Run `37119210761` is volledig geslaagd voor:

- release-build;
- AAB-validatie en test-APK rechtstreeks uit hetzelfde AAB;
- Light-basis zonder Pro-native libraries en Pro als afzonderlijke dynamic feature;
- telefoonimport en rollback;
- hervatten na force-stop;
- alle 19 demo-items;
- gedeelde EPG-data;
- telefoon-UI bij 130% systeemlettergrootte;
- tablet-UI op 1600×2560 / density 320;
- Android TV-runtime en DPAD-focus;
- UI-screenshots;
- Pro-runtime;
- APK-handtekening, package/version-identiteit en native-library alignment.

De telefoonjob, Android TV-job en buildjob eindigden alle drie met `success`.

## Grenzen van het bewijs

De suite controleert onder meer een lokale import van 5.000 items, annuleren en rollback, hervatten na force-stop, demo-afspelen, gedeelde EPG-data, afleveringsvolgorde, voortgang en Pro-runtime. Dit is geen meting van de laadtijd van de eigen providerlijst op een echte tv.

De bestaande demo heeft 19 items. Een lokale proefperiode duurt 30 dagen; deze is geen server-side accountbrede proefperiode. Casting, opnemen en externe ondertiteldiensten zijn niet opnieuw op echte apparaten of met providers bewezen. De Mijn NenoTV-koppeling en nieuwe Pro-roadmap behoren tot de volgende uitbreiding.

Er is geen Play Store-upload of uitrol gedaan.
