# OsnPlay 1.5.2

Local OsnPlay product build for the 2023 Lynk & Co 03 / OSN 2.0 (Android 9).
Application ID: `com.sinyee.babybus.story`, reused from the supplied CarMax APK at
the user's request (CarMax is not installed on the target). It installs separately
from OsnPlay 1.0.0, official OsnPlay and OsnPlay OSN Test. The receiver is built from this source tree with a persistent local
release key, without debug-only HUD components. Version 1.5.2 (versionCode 13)
fixes numeric-dialog readability and keeps manual-only GitHub Release checks. No legacy preference,
class-name or storage migration is included.

## 1.5.2 dialog themes and release automation

Resolution, ambient-light threshold and night-mode delay dialogs now use the
explicit OsnPlay day/night dialog theme before inflation. Their custom show
listeners also apply the palette, including input text/hints and all action
buttons. Open dialogs retain their drafts and update colors when the native
theme changes; text size is not scaled repeatedly. Numeric validation, reset
actions and natural dialog height remain covered by regression tests.

The version defaults live in `mobile/osnplay-release.properties`. The
**Publish OsnPlay release** Actions workflow supports matching `osnplay-v*` tags,
version-configuration changes on main and manual dispatch. It signs with
provisioned repository secrets, verifies the new APK, generates update.json,
uploads both assets to a draft and then publishes it. It does not download or
compare legacy repository Releases. See [Actions setup](ACTIONS_RELEASE.md).

## 1.5.1 manual-only GitHub Release updates

The OSN product has **Settings → About and updates**, using only the public
`xxxcrel/OsnPlay` repository. Only tapping **Check for updates** starts a request.
Startup, resume and opening the update page do not contact GitHub. The automatic
check switch, schedule/backoff preferences and unsolicited update notices have
been removed, including for installations that enabled the former switch.
Requested checks run outside the UI thread. Downloads and system installation
are explicit actions. Android 9 requires the source's installation permission and
system installation confirmation; there is no silent installation or system UID.

Each stable Release must contain its named APK and `update.json`. The release
script automatically generates these assets next to `mobile-release.apk` using
the actual signed APK's package, versionCode, versionName, minSdk, byte size and
SHA-256. See [GitHub release publishing](GITHUB_UPDATES.md). Drafts/prereleases,
an empty repository and releases without update metadata have distinct handling.

The updater compares versionCode, bounds JSON/download sizes, supports download
progress/cancellation/retry and verifies the APK checksum, actual package/version,
Android requirement and signing certificates. It rechecks the cached APK before
installation, grants a read-only FileProvider URI and stops the current CarPlay
session only when the user starts installation. Checks, downloads and validation
use a process-owned worker; screen subscriptions detach on destruction without
retaining activities or losing an in-progress download. Rate limits/network errors
are shown for manual retry and never trigger another request. No GitHub token is embedded.

Install an updater-enabled version manually once; future releases can then be
downloaded and handed to the system installer from the app. Instrument projection
remains removed.

## 1.4.1 instrument projection removal

The user reported that the 1.4.0 instrument projection experiment was not usable
on the vehicle and requested its removal. Its settings, calibration grid,
secondary-display controller, DIM Binder layer management, extra permission,
map-capability negotiation and experiment-specific decoder callbacks are removed.
The OSN product does not advertise or open an instrument map, including when
preferences from 1.4.0 remain saved. Main-screen CarPlay and the integrated
upstream night mode, Same LAN support and diagnostic export fallback remain.

The APK verifier checks that the withdrawn classes, resources and permission
are absent, and that `config_cluster_map_available` remains false.

## 1.3.0 cleanup, settings shortcuts and audio evidence

- Application classes, resource identifiers, log files/tags, preference namespaces,
  project branding, sample packages and scripts are named OsnPlay. The active source
  project is `frontend/android/OsnPlay`; the prior working tree remains a source backup.
  Original external source URLs and third-party license texts remain factual references.
- The OsnPlay product disables BYD configuration and runtime vehicle outputs. Battery,
  wheel speed, parked-video, HUD/cluster, BYD ADB and automatic factory-hotspot controls
  are excluded even if stale settings exist. Generic phone GPS and automatic app launch
  remain available. The BYD diagnostic/probe section is omitted from OsnPlay reports.
- The default settings shortcut is a **one-finger 1-second hold** in the middle of
  either side edge. Only a narrow edge strip is reserved. A short tap is replayed to
  the phone and a drag cancels the hold. Pending holds cancel on pause/shutdown.
  A small in-activity Settings button is enabled by default as a reliable fallback,
  requiring no system overlay permission. Button-only mode cannot hide its only entry.
- Appearance settings offer edge hold, button-only and optional multi-finger compatibility
  mode. The primary connection button stays visible with enlarged UI sizes. Fresh installs
  on the reported 1920-pixel/160-dpi screen default to 140%; other screens default to 125%.
- The supplied 1.2.1 report showed a maximum native build of 43 ms and status callback
  delay of 92 ms while background queries took about 1 second. It contained no CarPlay
  audio SETUP or music RTP evidence. This supports the reported factory-Bluetooth
  dependency, but does not identify every phone/factory routing decision.
- AAC music format type 102/media previously had only a type 102/default latency entry.
  A matching media latency is now declared. Audio offer counts, accepted media setups,
  aggregate received media packet/byte counts and sanitized audio-resource entities are
  exported. Audio → Audio transport status exposes the same evidence and cached Android
  output device types. This is a concrete negotiation correction, not a claim that
  Bluetooth-independent audio is already verified on the head unit.
- Premature Android media-focus takeover is now optional and defaults off in OsnPlay.
  A connection without incoming CarPlay music no longer claims the factory player's
  focus just for displaying video. Focus is still requested when real media starts.
  Audio settings expose the compatibility switch for on-car comparison.

After reinstalling, pair/select the iPhone, save hotspot credentials and manually
select a detected interface again. Test the edge hold and button, then compare music
with the factory Bluetooth source idle versus active. The new `mediaSetups` and
`mediaPackets` fields distinguish phone playback metadata from actual CarPlay audio.

## 1.2.1 performance and 12-inch UI size

The 1.2.0 on-car report (`OsnPlay-20261005-144841-240.txt`) showed active hardware
projection, including a 56 fps sample with no video sequence gaps, while the user
reported slow native tab switching and configuration saves. It did not contain
native UI timings. Code review found synchronous Bluetooth, hotspot, interface and
permission queries on the one-second main-thread status path, including settings
pages, plus repeated theme/context creation and unchanged text updates.

- Read-only device queries now run on a background executor, publish immutable
  snapshots and coalesce while a query is outstanding. Normal sampling is limited
  to once per 10 seconds; returning from system settings requests a fresh sample.
  Paused/destroyed windows do not receive UI updates, and late results are dropped.
- Render and status callbacks read cached state; background results update labels
  rather than rebuilding the page. Unknown state is shown as being read. The real
  interface picker still lists detected interfaces only and requires a deliberate
  choice; a first-use request may briefly wait for its background catalog.
- Palette/styles are applied only when the effective day/night theme changes.
  Version lookup is cached and unchanged status/clock strings are not reassigned.
- The native UI now defaults to **125%** for the user's 12-inch head unit. Text,
  cards, tabs and button targets grow together. Appearance → Interface size offers
  100%, 115%, 125% and 140%. Changes preserve the CarPlay connection; this preference
  does not change the phone's video resolution or CarPlay layout size. Important
  connection actions remain above secondary controls at the default enlarged size.
- Reports now include UI build maximum, status callback delay, background-query
  duration/count and screen density/dimensions. These are local measurements, not
  an assertion of on-car improvement before retesting.

89 targeted regression tests passed. Tests hold a device query blocked while
switching tabs and saving hotspot details, verify request coalescing and closed-window
callback disposal, and exercise adjustable size and the cached real-interface picker.

## 1.2.0 native UI and themes

The approved UI V1 concept is implemented using native Android views. The OsnPlay
product enables the redesigned shell; other product builds retain their existing UI.

- A persistent landscape navigation rail provides Home, Connection, Settings and
  Car home. Smaller windows use a compact navigation row. The home page combines
  a connection/status card, the saved device and three shortcut cards. Readiness
  uses actual preferences, required permissions and known hotspot/Bluetooth state.
- The connection page has wireless/USB selection, setup steps, concise hotspot
  preference rows and a separate device/permissions card. Editors and interface
  pickers retain existing validation and hand-selected real network interfaces.
  USB does not require a hotspot interface. Passwords remain masked in summaries.
- Settings are categorized as appearance, display/performance, audio/microphone,
  connection/startup and diagnostics/help. Existing settings persist and existing
  reconnect requirements remain in effect. Setting changes are saved as applied;
  there is no misleading extra Save button for already-persisted controls.
- Interface theme defaults to Follow system, with Light and Dark overrides under
  Settings → Appearance and interface → Interface theme. Semantic day/night colors
  cover surfaces, labels, controls, system bars, native dialogs and inputs. The
  product's startup window also has day/night styling.
- The system theme is read from application resources, so an explicit app language
  cannot freeze the creation-time night qualifier. Configuration changes, resume
  and a lightweight status-loop check update the UI without rebuilding CarPlay.
  The current page/category/scroll and an open hotspot editor's draft are retained.
- The OsnPlay UI theme is independent of the phone's CarPlay appearance. No eCarX
  controls or instrument projection have been reintroduced.

80 targeted regression tests passed, including 13 new theme/native UI checks and
existing language, permission, audio, hotspot, USB and reconnect tests. Native
Robolectric drawing was used to review the three screens in both palettes; it is
not a substitute for checking the head unit's font scale and actual night reporting.
An attempted unfiltered common test run exceeded the command timeout; the targeted
UI/settings/connection suite completed successfully.

## 1.1.1 changes

At the user's request, the experimental eCarX integration added in 1.1.0 has been
removed: factory media-center registration, vendor key interception/learning,
native instrument guidance, second-screen map projection and OEM navigation-service
control. Its settings, diagnostic probes, manifest authorization metadata and
Dexmaker dependency have also been removed. OsnPlay's product configuration disables
instrument maps even if an earlier map preference was saved, so no second display
is advertised or opened. Navigation within the main CarPlay display remains available.

The 16:9 app-list icons and banner now extend Apple's source green gradient across
the entire rectangle. The complete white CarPlay glyph retains its original scale;
dark padding, transparent corners and the JPEG's rounded-square outer border are
excluded. All density variants and the banner are verified as fully opaque with
green left/right extensions. The CarPlay return-to-car icon remains the Lynk & Co mark.

The on-car 1.1.0 reports showed unavailable media-center classes, no accepted Input
interception and short playing-to-paused transitions. Factory Bluetooth steering-wheel
operation was observed after disabling the vendor options. This update removes the
experimental adaptation and corrects the icon; audio troubleshooting remains based
on actual phone output routing and fresh diagnostics.

## OSN settings and audio

### 1.0.2 audio compatibility corrections

The delivered 1.0.1 APK was inspected: early focus and standard media-key settings
were still enabled. The short automatic pause was also observed in the later 1.1.0
reports; its exact trigger remains unconfirmed.

- System media-control sync defaults off. Early focus remains, but no Android
  transport/metadata session is published and focus is not reclaimed when the
  iPhone starts playing. This removes the factory-controller pause forwarding
  path and focus ping-pong. CarPlay touch controls are independent of this option.
  The setting is available under Audio routing; reconnect after changing it.
- Only PCM bidirectional voice formats are advertised on Android 9, where an Opus
  microphone encoder is not guaranteed. Music's AAC output remains unchanged.
- Negotiated microphone capture starts after the authenticated voice SETUP response
  is flushed, without waiting for a downlink packet. This also supports uplink-only
  recordings. Teardown, stream replacement and session close release the recorder.
- The microphone UDP sender binds to the local AirPlay link address. Compatibility
  mode uses standard MIC input and preserves factory-managed call mode/routing.
- Microphone permission is required before OsnPlay connects. Diagnostics include
  capture source, route type, byte/send counts and aggregate signal levels, never PCM.
- Unit tests cover absent downlink, authentication/permission gating, stale setup,
  PCM-only negotiation, mode preservation and encrypted UDP microphone delivery.
  Physical call/WeChat microphone operation still needs an on-car retest.

- Fresh installs have no selected hotspot interface. Turn on the car hotspot,
  choose an actual interface in Connection setup, and save it. The choice is
  remembered across activity/process restarts and future in-place updates.
  There is no preset interface or automatic-interface option in the OsnPlay picker;
  unavailable saved interfaces are not inserted into the list as ghost choices.
  A missing/unavailable choice returns to setup before a wireless manual-hotspot
  connection can start. USB does not require a hotspot interface. Address mode
  still defaults to IPv4 and can be changed independently.
- Claim the media-session audio focus before starting the phone connection. The
  previous late request, on the first audio packet, can cause an OEM Bluetooth
  player to send AVRCP pause as it loses focus. This change moves that source switch
  before CarPlay playback starts. Audio output routing is preserved because the user
  confirmed that sound was audible before the automatic pause.
- Hardware PLAY and PAUSE remain explicit commands in this product, rather than
  using BYD's PLAY/PAUSE-to-toggle workaround. The real PLAY_PAUSE key still toggles.
- Focus changes, iPhone playback transitions and forwarded media commands are
  recorded in the redacted diagnostic report. The user reported normal operation
  with version 1.0.0 and another pause after switching to the allowlisted package.

## App-list and CarPlay icons

The reference `应用商店Pro-车机版-5.3.1.apk` is labelled `CarMax商店` and uses an adaptive icon, an exported
MAIN/LAUNCHER activity, singleTask landscape launch, and target SDK 28. It does not
declare a special OSN app-list icon metadata field. Its application ID is
`com.sinyee.babybus.story`; OsnPlay 1.0.1 reuses this package to test the suspected
OSN package allowlist, while retaining the OsnPlay name and its own release signature.

OsnPlay declares a real `com.osnplay.app.MainActivity`, with explicit
label and bitmap icon, and uses target SDK 28 for Android 9 compatibility. Five
  launcher density sizes and a round-icon resource are bundled. In 1.1.1, both icon
  resources are fully green 16:9 cards with the original Apple CarPlay glyph, and application/
  activity banner metadata points at the matching wide PNG. The CarPlay OEM return label
is `领克` and its image uses the official Lynk & Co wordmark (source recorded in
`asset/osnplay/README.md`). The icon preview now shows the actual packaged return
icon. Native OSN app-list visibility must still be checked on the car; any additional
firmware package allowlist cannot be inferred from this reference manifest alone.

## Build and verification

```sh
JAVA_HOME=/path/to/jdk25 ANDROID_HOME=/path/to/android-sdk \
  python3 scripts/build-osnplay-release.py --auth-assets-dir /path/to/runtime-assets
```

The helper creates/reuses `.private/osnplay-signing/osnplay-release.p12` and its
local signing configuration. Keep that directory for future in-place updates.
It is excluded from Git and is not included in the APK. An optional `--proxy-url`
can be supplied for dependency downloads.

```sh
python3 scripts/verify-osnplay-apk.py \
  --apk mobile/build/outputs/apk/release/mobile-release.apk \
  --auth-assets-dir /path/to/runtime-assets \
   --build-tools /path/to/android-sdk/build-tools/37.0.0 \
   --output /path/to/OsnPlay-1.3.0.apk
```

The verifier requires Pillow and checks effective compiled OSN resource defaults, release mode,
the real launcher class definition in DEX, PNG icons, the exact Lynk & Co return
image, ARM libraries, archive integrity, signing validity, an ordinary UID and both runtime assets.
The regression suite covers explicit interface selection, persistence, stale choices,
missing-interface startup, USB, audio focus, microphone negotiation/uplink and saved
instrument preferences after withdrawal, plus release lint. The APK verifier also
checks opaque green padding, disabled instrument maps and absence of the removed
vehicle integration and callback dependency in DEX.

## On-car check

Stop the older receivers, install OsnPlay, save the car hotspot credentials and
choose the iPhone again, and select/save a real hotspot interface. The new package
has its own preferences and pairing identity on a fresh install; in-place updates
retain the existing ones. Check launcher/app-list visibility,
the Lynk & Co return icon, sustained music playback and pause/resume. If music still pauses, export a
fresh `OsnPlay-*.txt` after the failure; the new media-control records distinguish
incoming pause commands from a phone-side pause without a command from this app.

For 1.2.1, check native tab switching and configuration saves during active CarPlay,
the default 125% size and manual size choices, and the background status indicators.
Also check the redesigned home/connection/settings pages, system light/dark
switching, manual theme overrides, reopening the app and theme changes during an
active connection. Check that hotspot edits and saved interface selection survive,
then test center-screen CarPlay, music and microphone. Exported diagnostics include
the selected UI appearance and reported system uiMode.
