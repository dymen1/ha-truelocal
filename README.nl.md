# TrueLocal

> 🇬🇧 English version: [README.md](README.md)

Matter- en Zigbee-apparaten koppelen aan Home Assistant **zonder de Companion-app of Google Play
Services**. Je telefoon is alleen de Bluetooth-radio; alle Matter-logica en de Thread-credentials
blijven op de HA-host. Er gaat niets naar Google, Apple of enige cloud.

## Use cases

- **Een Matter-apparaat toevoegen zonder Google Play Services.** De standaardroute geeft je
  Thread-netwerkcredentials aan de credential-store van Google (of Apple) op de telefoon. Hier
  houdt de Matter Server de dataset en leent hij alleen de radio van je telefoon.
- **Een Matter-apparaat toevoegen dat buiten bereik van je server is.** In plaats van het
  apparaat naar de HA-host te dragen voor Bluetooth-bereik, draag je de telefoon naar het
  apparaat.
- **Zien welke Matter-apparaten in de buurt in pairing-modus staan** — adres, discriminator,
  vendor/product-ID en signaalsterkte, live ververst. Handig om te bevestigen dat een apparaat
  echt adverteert, en om het signaal te checken vóór je gaat commissionen.
- **Zien welke Matter-nodes de server al heeft**, inclusief welke offline zijn — nuttig na een
  Thread-sleutelrotatie of bij het opruimen van dode nodes.
- **Het Zigbee-netwerk openzetten vanaf de plek waar het apparaat hangt.** Druk op permit join
  op je telefoon terwijl je bij de sensor of thermostaat staat, in plaats van terug te lopen
  naar een browser. Werkt met zowel Zigbee2MQTT als ZHA.
- **Vooraf uitvinden waarom koppelen faalt.** De omgevingscheck per tab controleert de hele
  keten: Bluetooth op de telefoon, add-ons geïnstalleerd en gestart, ingress aan, integraties
  geladen, voorkeurs-Thread-netwerk, BLE proxy actief, Thread-dataset aanwezig, en of de
  `/ble`-endpoint daadwerkelijk een proxyclient accepteert.

## Hoe het werkt

```
[Lamp] ←BLE→ [Deze app] ←websocket via HA Ingress→ [Matter Server add-on op de HA-host]
```

De Matter Server add-on (9.x met `Enable BLE proxy` aan) heeft twee websocket-endpoints:

- `/ws` — de commando-API. De app stuurt hier `commission_with_code` naartoe.
- `/ble` — het BLE-proxyprotocol. De server stuurt BLE-opdrachten (scan, connect, GATT
  read/write/subscribe); de app voert ze uit op de Bluetooth-radio van de telefoon en stuurt de
  resultaten terug. Hetzelfde protocol dat Home Assistant zelf en ESPHome-proxies spreken.

De server geeft de lamp zelf de Thread-dataset. De dataset staat dus nooit in deze app, nooit op
de telefoon, en nooit in de credential-store van Google Play Services.

## Wat is die "Ingress" eigenlijk?

Ingress is een **reverse proxy die in Home Assistant zelf zit**, op je eigen HA-host. Als je in HA op
"Open Web UI" bij een add-on klikt, gaat dat verkeer niet rechtstreeks naar de add-on, maar via
`http://<ha-host>:8123/api/hassio_ingress/...` — HA controleert je login en stuurt het dan
intern door naar de add-on.

Waarom dat hier handig is:

1. **Geen open poorten.** De `/ble`-endpoint van de Matter Server heeft zelf geen authenticatie.
   Via ingress hoeft poort 5580 niet bereikbaar te zijn; alles loopt over de al beveiligde
   poort 8123 van HA.
2. **Authenticatie.** Alleen wie een geldig HA-token heeft komt erbij.
3. **Niets verlaat het LAN.** Ingress is software op de HA-host. Er is geen Google, geen Nabu Casa,
   geen cloud-relay bij betrokken. Telefoon → HA-host over je eigen Wi-Fi, klaar. Wil je het
   controleren: trek de WAN-kabel uit de router en het werkt nog steeds.

## Geteste omgeving

End-to-end geverifieerd met de gesigneerde release-build. Andere versies werken waarschijnlijk
ook, maar hierop is de flow daadwerkelijk bevestigd:

| Onderdeel | Versie |
|---|---|
| Home Assistant OS | 2026.8.3 (Raspberry Pi 5) |
| Matter Server add-on | 9.2.0 (matter.js-based), BLE proxy aan |
| BLE-proxyprotocol | v1 |
| OpenThread Border Router add-on | met geflashte Thread RCP-dongle |
| Zigbee | Zigbee2MQTT + Mosquitto (tweede dongle) |
| Testtoestel | Motorola Moto G75 5G, Android 16 |
| App | minSdk 31 (Android 12), target/compileSdk 34 |
| Build-toolchain | JDK 21, Gradle 8.7, AGP 8.5.2, Kotlin 2.0.20 |

Bevestigde apparaten: een Matter-lamp via Thread gecommissioned door de BLE-proxy, en een ROBB
ROB_200-040-0 (Sunricher Zg9092) Zigbee-thermostaat gekoppeld via de permit join van de app.

Let op bij HA-versies: met een long-lived access token geeft de REST-proxy op `/api/hassio/*`
een 401, dus de app doet elke Supervisor-call (add-on-info, ingress-sessie) via het
`supervisor/api`-websocketcommando. Op oudere HA-releases die de REST-route nog toestaan maakt
dat geen verschil.

## Eenmalige setup

### 1. Matter Server add-on

In recente versies van de add-on staat de **BLE proxy standaard aan** — dan is hier niets te
configureren. Draai je een oudere versie, of twijfel je:

Instellingen → Add-ons → Matter Server → Configuratie:

- *Show unused optional configuration options* aan
- **Enable BLE proxy** aan
- **Bluetooth adapter ID** leeg (deprecated; sluit de BLE proxy uit)
- Opslaan → herstarten. Log moet de BLE-proxy-endpoint melden.

De zekerste controle is de app zelf: klap op de Matter-tab de sectie **Omgevingscheck** open —
de regel "BLE proxy actief op de server" moet OK zijn.

De Thread-dataset moet op de server als default staan (HA pusht het voorkeursnetwerk
automatisch; controleren kan in de Web UI van de add-on onder Settings — ook dit zie je terug
in de Omgevingscheck als "Thread-dataset aanwezig op de server").

### 2. Long-lived access token

Ingress-toegang loopt via de Supervisor-API en vereist een token van een **administrator**-gebruiker:

1. HA openen → klik linksonder op je profielnaam → tab **Beveiliging**
2. Onderaan bij *Tokens voor langdurige toegang* → **Token aanmaken**, naam bijv. `truelocal`
3. Kopieer het token direct (het wordt maar één keer getoond) en plak het in de app

Het token geeft volledige HA-toegang: behandel het als een wachtwoord. Token intrekken =
verwijderen op dezelfde profielpagina.

### 3. App bouwen

Open deze map in Android Studio en laat het syncen (Studio genereert zelf de Gradle-wrapper),
of met een lokale Gradle-installatie:

```
gradle wrapper && gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Installeren via `adb install` of het bestand
naar de telefoon kopiëren. Minimaal Android 12. Kant-en-klare gesigneerde APK's hangen onder de
[releases](../../releases).

## Gebruik

1. **HA URL** invullen en het **token** plakken. Gebruik **exact** de URL waarop je HA in de
   browser opent. Bij een standaardinstallatie hoort de poort erbij
   (`http://192.168.1.10:8123`); achter een reverse proxy of op een eigen domein moet de poort
   juist **weggelaten** worden — `:8123` is daar meestal niet bereikbaar en dan faalt de
   verbinding. Lost een hostname niet op (Android kan `.local`/mDNS-namen vaak niet vinden),
   gebruik dan het IP-adres
2. **Verbind** — log moet tonen: ingress-sessie geopend, `BLE proxy handshake complete`,
   `Matter server verbonden ... thread_credentials=true`
3. Lamp in pairing-modus (5× aan/uit tot hij knippert), telefoon ernaast
4. **Scan QR** (of typ de 11-cijferige koppelcode) → **Voeg toe**
5. Wachten (~45 s voor Thread). Bij succes verschijnt de lamp vanzelf in HA.

Laat het token-veld leeg om zonder ingress rechtstreeks te verbinden met
`http://<ha-host>:5580` (de add-on draait op host-netwerk). Werkt, maar die poort heeft geen
authenticatie — de ingress-route is de nette.

## Bekende valkuil: Google ziet de lamp ook

Een lamp in pairing-modus adverteert over BLE, en **Google Play Services luistert daar standaard
ook naar** (hetzelfde mechanisme als Fast Pair). Android toont dan een eigen
"apparaat gevonden"-melding met QR-scanner. Die flow afmaken deelt je Thread-credentials alsnog
met Google — wegdrukken is genoeg, er lekt niets zolang je de setup niet voltooit.

Structureel uitzetten: Instellingen → **Google** → Apparaten en delen → Apparaten →
**"Meldingen voor apparaten in de buurt"** uit. De scanner van deze app herken je aan de
portretmodus met de prompt "Scan de Matter QR-code op de lamp".

## Bekende beperkingen

- Alles draait in de Activity: scherm blijft aan tijdens commissioning, app niet wegswipen
- Ingress-sessies verlopen na een tijd inactiviteit; opnieuw op **Verbind** drukken
- Eén commissioning tegelijk
- Foutafhandeling is een logregel, geen retry

## Protocolreferenties

- BLE-proxyprotocol: `matter-js/matterjs-server` → `docs/ble-proxy-protocol.md`
- Websocket-API (`commission_with_code`): zelfde repo → `docs/websockets_api.md`

## Herkomst

Deze app is ontworpen en gebouwd door een engineer die samenwerkte met een LLM. De
protocolimplementatie volgt de gepubliceerde specificaties van het BLE-proxyprotocol en de
websocket-API, en is niet afgeleid van een bestaande client.

Het is geen papieren exercitie: de volledige flow — ingress-authenticatie, BLE-proxy-handshake,
Matter-commissioning over Thread en Zigbee permit join — is end-to-end geverifieerd op echte
hardware met de gesigneerde release-build, op de setup onder
[Geteste omgeving](#geteste-omgeving).

## Licentie

Copyright 2026 Dylan Hesse (dymen1). Gelicentieerd onder de
[GNU General Public License v3.0](LICENSE) — dezelfde licentie als Zigbee2MQTT.

Je mag dit gebruiken, aanpassen, herdistribueren en verkopen, ook commercieel. De voorwaarde is
wederkerigheid: distribueer je een aangepaste versie, dan moet je de broncode daarvan onder
dezelfde licentie vrijgeven, zodat verbeteringen beschikbaar blijven voor iedereen.

Dit project is niet gelieerd aan of goedgekeurd door Home Assistant, Nabu Casa of de Open Home
Foundation.
