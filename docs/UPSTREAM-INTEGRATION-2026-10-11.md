# OSN upstream integration — 2026-10-11

## Source

- OSN starting point: `56750123` (1.5.2).
- Previous upstream base: `a41542a1` (0.2.11).
- Imported upstream main: `24ae1fa52edbe27392a668d4c9e71a32d5691986` (0.2.18),
  713 additional commits.
- Repository release configuration remains 1.5.2 / versionCode 13.

## Integration decisions

The OSN launcher remains `com.osnplay.app.MainActivity`, inheriting the existing
`OsnPlayActivity`. Its native UI, settings categories, interface-size preferences,
day/night dialogs, background device sampling and settings shortcuts are retained.
The imported settings screen is named `OsnGenericActivity` and is removed from
the OSN manifest. Application naming stays OsnPlay; original external URLs and
third-party source/license notices retain their provenance.

USB, wireless-startup recovery, video decoding, buffered audio, software Opus and
echo-cancellation changes are integrated into the shared implementation. The OSN
standard-microphone option still chooses MIC without switching to communication
mode. Diagnostics use the same platform-or-software Opus capability probe as
negotiation.

The hand-selected real hotspot interface is passed through the imported readiness
sampler. A missing choice cannot silently fall back to another interface; unstable
samples and a known-off hotspot remain rejected. Explicit IPv4/IPv6 selection also
constrains published addresses and automatic address-family recovery. USB remains
independent of hotspot configuration.

The imported automatic-update scheduler and worker are gated out of the OSN
product, including a saved background-check opt-in. The existing manual updater
continues to use `xxxcrel/OsnPlay`. BYD/FCE call, navigation, phone-audio, Bluetooth
pause and ambient-light controls obey the OSN product exclusion. Instrument-map
capability remains disabled. The app retains its ordinary UID and existing
private release-signing configuration.

## Local verification

The complete AGENTS.md CI command passed:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

- Shared: 1,370 tests, 0 failures, 1 skipped.
- Common: 1,215 tests, 0 failures.
- Home: 4 tests, 0 failures.
- Release helpers: 13 tests passed.
- Actionlint passed for `android.yml` and `osnplay-release.yml`.
- OSN Release Lint and signed standalone Release build passed.
- APK verification passed: OSN-only launcher, non-debug build, API 28 minimum and
  target, ordinary UID, effective product defaults, themed resources, bitmap icons,
  ARM native libraries, explicit runtime inputs and archive/signature validity.
- Public-tree credential checks passed. Imported Concentus and SpeexDSP sources
  and third-party notices match the upstream commit; their existing whitespace is
  retained. Project-owned changes pass `git diff --check`.

The unpublished verification build uses command-line version overrides
`1.5.3-upstream-check` / 14. It is located at:

```text
mobile/build/outputs/apk/release/OsnPlay-1.5.3-upstream-check.apk
```

Size: 40.47 MiB. SHA-256:

```text
d623cbd440eabc9cbbe7f4aa974004655e84b3868b881adc0e0fe259cea319ce
```

No physical vehicle acceptance was performed during this integration. These checks
establish source/build regressions, not Bluetooth-independent music, Siri or
steering-wheel behavior on the user's OSN head unit.
