# OSN / Android 9 hotspot compatibility test

The two supplied Lynk & Co 03 / OSN 2.0 reports identify Android 9 (API 28),
QUALCOMM CS11 / msmnile. Bluetooth iAP2 authentication succeeds, but AirPlay TCP
and Bonjour discovery never start. The old heuristic repeatedly selects
`eth0.3` and its IPv6 link-local address, with zero receive-counter deltas.
The reports do not reveal the actual hotspot interface or unredacted addresses.

## Changes

- Prefer system-reported tethered interfaces when available. Use non-station
  Wi-Fi interfaces as a fallback; do not guess an unverified Ethernet/VLAN link
  from its private IP. Do not exclude an interface merely because it is the
  default network. System-confirmed vendor VLAN/bridge hotspots remain usable.
- Android 9 built-in-hotspot Auto mode prefers IPv4 when available. Android 10+
  retains the existing IPv6 preference. Wi-Fi Direct behavior is unchanged.
- Connection setup → Built-in car hotspot exposes address mode (Auto / IPv4 /
  IPv6) and an explicit interface selector. The selector shows local IPv4
  addresses so they can be compared with the router in iPhone Wi-Fi details.
  An explicit choice never silently falls back to another interface or family.
- Saved reports include mode, override and candidate-interface/address-family
  counts. No hotspot passwords or raw addresses are added to exported logs.

## Build

Use the existing external runtime-asset provisioning:

```sh
OSNPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets \
  ./gradlew -PosnplayOsnTest :mobile:assembleStandaloneDebug
```

This produces `OsnPlay OSN Test`, package `com.shihab.osnplay.osntest`, version
`0.2.11-osn-test1`. It uses a local debug signature and installs alongside the
official app; it cannot update the official app. Only one receiver should run
at a time. Save hotspot details and choose the iPhone again in the test app.

## On-car verification

1. Stop the official OsnPlay receiver and open OsnPlay OSN Test.
2. Turn on the built-in car hotspot, save its real credentials, and choose the iPhone.
3. Try Auto interface / Auto address first (IPv4 preferred on Android 9).
4. If no interface is detected or no picture appears, manually join the car
   hotspot on the iPhone, note its Wi-Fi router address, and choose the listed
   interface with that IPv4 address. Select IPv4 compatibility and reconnect.
5. Export a fresh report. Expected progression: a relevant interface with receive
   activity → `airplay TCP accepted` → screen stream → `Video: first frame rendered`.
6. If the iPhone router is not an address of any listed interface, obtain `adb shell
   ip addr show` and `adb shell ip route show`. The hotspot may be in a separate
   network subsystem that requires system-side routing; guessing a VLAN is not a fix.

This is a targeted test build. Compilation and unit tests cannot establish
physical CarPlay operation on OSN; a new on-car report is required.
