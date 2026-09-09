# TrueLocal

> 🇳🇱 Nederlandse versie: [README.nl.md](README.nl.md)

Pair Matter and Zigbee devices with Home Assistant **without the Companion app or Google Play
Services**. Your phone is only the Bluetooth radio; all Matter logic and the Thread credentials
stay on your HA host. Nothing is shared with Google, Apple, or any cloud.

## Use cases

- **Add a Matter device without Google Play Services.** The standard route hands your Thread
  network credentials to the Google (or Apple) credential store on the phone. Here the Matter
  Server keeps the dataset and only borrows the phone's radio.
- **Add a Matter device that is out of range of your server.** Instead of carrying the device
  to the HA host to get it within Bluetooth range, you carry the phone to the device.
- **See which Matter devices are in pairing mode around you** — address, discriminator,
  vendor/product ID and signal strength, refreshed live. Useful to confirm a device really is
  advertising, and to check the signal before you try to commission.
- **See which Matter nodes the server already has**, including which ones are offline — handy
  after a Thread key rotation or when cleaning up stale nodes.
- **Open the Zigbee network for joining from where the device is.** Press permit join on your
  phone while standing at the sensor or thermostat, instead of walking back to a browser.
  Works with both Zigbee2MQTT and ZHA.
- **Find out why pairing fails before you start.** The environment check per tab verifies the
  whole chain: phone Bluetooth, add-ons installed and started, ingress enabled, integrations
  loaded, preferred Thread network, BLE proxy active, Thread dataset present, and whether the
  `/ble` endpoint actually accepts a proxy client.

## How it works

```
[Light] ←BLE→ [This app] ←websocket via HA Ingress→ [Matter Server add-on on the HA host]
```

The Matter Server add-on (9.x with the BLE proxy enabled) exposes two websocket endpoints:

- `/ws` — the command API. The app sends `commission_with_code` here.
- `/ble` — the BLE proxy protocol. The server sends BLE operations (scan, connect, GATT
  read/write/subscribe); the app executes them on the phone's Bluetooth radio and streams the
  results back. The same protocol Home Assistant itself and ESPHome proxies speak.

The server hands the Thread dataset to the light itself. The dataset therefore never lives in
this app, never on the phone, and never in the Google Play Services credential store.

## What is "Ingress" anyway?

Ingress is a **reverse proxy built into Home Assistant**, running on your own HA host. When you
click "Open Web UI" on an add-on, that traffic does not go to the add-on directly but through
`http://<ha-host>:8123/api/hassio_ingress/...` — HA verifies your login and then forwards the
request internally.

Why that matters here:

1. **No open ports.** The Matter Server's `/ble` endpoint has no authentication of its own.
   Through ingress, port 5580 never needs to be reachable; everything runs over HA's already
   secured port 8123.
2. **Authentication.** Only someone with a valid HA token gets in.
3. **Nothing leaves the LAN.** Ingress is software on your HA host. No Google, no Nabu Casa,
   no cloud relay involved. Phone → HA host over your own Wi-Fi, done. Want proof: pull the
   WAN cable from your router and it still works.

## Tested environment

Verified end-to-end with the signed release build. Other versions likely work, but this is what
the flow was actually confirmed on:

| Component | Version |
|---|---|
| Home Assistant OS | 2026.8.3 (Raspberry Pi 5) |
| Matter Server add-on | 9.2.0 (matter.js based), BLE proxy enabled |
| BLE proxy protocol | v1 |
| OpenThread Border Router add-on | with a flashed Thread RCP dongle |
| Zigbee | Zigbee2MQTT + Mosquitto (second dongle) |
| Test phone | Motorola Moto G75 5G, Android 16 |
| App | minSdk 31 (Android 12), target/compileSdk 34 |
| Build toolchain | JDK 21, Gradle 8.7, AGP 8.5.2, Kotlin 2.0.20 |

Devices confirmed: a Matter light commissioned over Thread through the BLE proxy, and a ROBB
ROB_200-040-0 (Sunricher Zg9092) Zigbee thermostat paired through the app's permit join.

Note on Home Assistant versions: with a long-lived access token the REST proxy at
`/api/hassio/*` returns 401, so the app performs every Supervisor call (add-on info, ingress
session) through the `supervisor/api` websocket command instead. On older HA releases that
still allow the REST route this makes no difference.

## One-time setup

### 1. Matter Server add-on

Recent versions of the add-on ship with the **BLE proxy enabled by default** — nothing to
configure. On an older version, or if in doubt:

Settings → Add-ons → Matter Server → Configuration:

- Enable *Show unused optional configuration options*
- **Enable BLE proxy** on
- **Bluetooth adapter ID** empty (deprecated; mutually exclusive with the BLE proxy)
- Save → restart. The log should report the BLE proxy endpoint.

The most reliable check is the app itself: open the **Environment check** section on the Matter
tab — the "BLE proxy active on the server" line must say OK.

The Thread dataset must be set as default on the server (HA pushes the preferred network
automatically; you can verify in the add-on's Web UI under Settings — the environment check
shows this too as "Thread dataset present on the server").

### 2. Long-lived access token

Ingress access goes through the Supervisor API and requires a token from an
**administrator** user:

1. Open HA → click your profile name bottom-left → **Security** tab
2. At the bottom under *Long-lived access tokens* → **Create token**, name it e.g. `truelocal`
3. Copy the token immediately (it is shown only once) and paste it into the app

The token grants full HA access: treat it like a password. Revoking it = deleting it on the
same profile page.

### 3. Building the app

Open this folder in Android Studio and let it sync (Studio generates the Gradle wrapper), or
with a local Gradle installation:

```
gradle wrapper && gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Install via `adb install` or by copying the
file to the phone. Requires Android 12 or newer. Prebuilt signed APKs are attached to the
[releases](../../releases).

For a signed release build, create a `keystore.properties` in the project root (see
`app/build.gradle.kts` for the expected keys); without it the release build is unsigned.

## Usage

1. Enter the **HA URL** and paste the **token**. Use **exactly** the URL you open HA on in
   the browser. On a standard install that includes the port (`http://192.168.1.10:8123`);
   behind a reverse proxy or on your own domain you must **leave the port out** — `:8123` is
   usually not reachable there and the connection will fail. If a hostname does not resolve
   (Android often cannot resolve `.local`/mDNS names), use the IP address instead
2. **Connect** — the log must show: ingress session opened, `BLE proxy handshake complete`,
   `Matter server connected ... thread_credentials=true`
3. Put the light in pairing mode (toggle power 5× until it blinks), phone next to it
4. **Scan QR** (or type the 11-digit pairing code) → **Add device**
5. Wait (~45 s for Thread). On success the light appears in HA automatically.

Leave the token field empty to connect directly without ingress via
`http://<ha-host>:5580` (the add-on runs on the host network). Works, but that port has no
authentication — the ingress route is the proper one.

## Known pitfall: Google notices the light too

A light in pairing mode advertises over BLE, and **Google Play Services listens for that by
default** (the same mechanism as Fast Pair). Android then shows its own "device found"
notification with a QR scanner. Completing that flow shares your Thread credentials with
Google after all — dismissing it is enough, nothing leaks as long as you do not finish the
setup.

To disable it permanently: Settings → **Google** → Devices & sharing → Devices →
**"Nearby device notifications"** off. This app's own scanner is recognizable by its portrait
mode with the prompt "Scan the Matter QR code on the light".

## Known limitations

- Everything runs in the Activity: the screen stays on during commissioning, do not swipe the
  app away
- Ingress sessions expire after inactivity; press **Connect** again
- One commissioning at a time
- Error handling is a log line, not a retry

## Protocol references

- BLE proxy protocol: `matter-js/matterjs-server` → `docs/ble-proxy-protocol.md`
- Websocket API (`commission_with_code`): same repo → `docs/websockets_api.md`

## Provenance

This app was designed and built by an engineer pair-programming with an LLM. The protocol
implementation follows the published BLE proxy and websocket API specifications rather than
being derived from an existing client.

It is not a paper exercise: the complete flow — ingress authentication, BLE proxy handshake,
Matter commissioning over Thread and Zigbee permit join — was verified end-to-end on real
hardware with the signed release build, on the setup listed under
[Tested environment](#tested-environment).

## License

Copyright 2026 Dylan Hesse (dymen1). Licensed under the
[GNU General Public License v3.0](LICENSE) — the same license Zigbee2MQTT uses.

You may use, modify, redistribute and sell this, including commercially. The condition is
reciprocity: if you distribute a modified version, you must release its source under the same
license, so improvements stay available to everyone.

This project is not affiliated with or endorsed by Home Assistant, Nabu Casa, or the Open Home
Foundation.
