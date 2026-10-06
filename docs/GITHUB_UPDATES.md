# OsnPlay GitHub Release updates

Update repository: https://github.com/xxxcrel/OsnPlay

The application reads the latest **stable** Release from
`https://api.github.com/repos/xxxcrel/OsnPlay/releases/latest`. It requires a public
repository and does not ship a GitHub access token. Drafts and GitHub prereleases
are not offered through this channel.

## Build and publish

1. Increase both `versionCode` and `versionName` in `mobile/osnplay-release.properties`.
   Current version: 1.5.2 / 13. Use a higher versionCode for every future update.
2. Build using the existing persistent local signing key and explicitly provided
   authentication assets:

   ```sh
   python3 scripts/build-osnplay-release.py --auth-assets-dir /path/to/runtime-assets
   ```

   Set `JAVA_HOME` and `ANDROID_HOME` as described in `BUILD.md`. Dependency
   downloads can use `--proxy-url`; `--build-tools` overrides the default SDK
   `build-tools/37.0.0` directory used for generating metadata.
3. After signing, the script generates these Release assets in
   `mobile/build/outputs/apk/release/`:

   ```text
   OsnPlay-1.5.2.apk
   update.json
   ```

4. On the fork's GitHub Releases page, create a release/tag, add release notes,
   and upload **both files to the same Release**. Publish it without selecting
   “Set as a pre-release”. Tags can be named `osnplay-v1.5.2`; version comparison
   uses metadata versionCode rather than parsing the tag.

Do not rename an APK after generating metadata. Generate again if its bytes,
name or version change. The publishing files are APK and update.json; retain
the local signing configuration for future builds.

For tag/configuration-triggered Actions builds and automatic publishing, see
[Automatic Actions releases](ACTIONS_RELEASE.md). Local builds can also override
the configuration with `--version-name` and `--version-code`.

You can also generate publishing files for an already signed APK:

```sh
python3 scripts/generate-osnplay-update.py \
  --apk /path/to/OsnPlay-1.5.2.apk \
  --build-tools /path/to/Android/sdk/build-tools/37.0.0 \
  --output-dir /path/to/an/existing/output-directory
```

## Metadata format

```json
{
  "formatVersion": 1,
  "applicationId": "com.sinyee.babybus.story",
  "versionCode": 13,
  "versionName": "1.5.2",
  "minSdk": 28,
  "apkAsset": "OsnPlay-1.5.2.apk",
  "sizeBytes": 38200000,
  "sha256": "<actual APK SHA-256>"
}
```

The size/hash above are illustrative; the generator writes actual values. The
release notes come from GitHub's Release body and are displayed as plain text.
The APK must use the same application ID and signing certificates as the installed
app. Missing/malformed metadata, wrong APKs and unsupported Android versions are
shown as update states instead of attempting installation.

## On the head unit

Install an updater-enabled version manually once. Open **Settings → About and
updates → Check for updates**. This is the only trigger for contacting GitHub.
There are no automatic checks, timers or automatic-check switches. Startup,
resume and opening the page only display existing state. Network errors and
GitHub rate limits are shown for manual retry. Checking does not stop CarPlay.

Choose Download update, then Install update after verification. If Android asks
for permission to install unknown apps, allow OsnPlay. Installation is confirmed
in the system installer. Starting installation disconnects the current CarPlay
session. Cancelling a download removes partial data. A download survives activity
recreation; after process termination, check/download again to reuse a verified
completed APK from cache when available. No silent installs or forced updates
are performed.

An app that already has the currently published version will report no newer
version. Test an upgrade by installing an older updater-enabled build and
publishing an APK with a higher versionCode and matching signing certificates.
