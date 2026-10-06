# Automatic OsnPlay builds and GitHub Releases

Workflow: `.github/workflows/osnplay-release.yml` (**Publish OsnPlay release**).
Repository: `xxxcrel/OsnPlay`.

## One-time setup

Enable GitHub Actions on the fork. Provision these repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `OSNPLAY_KEYSTORE_B64` | Base64 of the existing `.private/osnplay-signing/osnplay-release.p12` |
| `OSNPLAY_KEYSTORE_PASSWORD` | `password` from the existing local `signing.json` |
| `OSNPLAY_KEY_ALIAS` | `alias` from that same file, normally `osnplay` |
| `OSNPLAY_AUTH_IDENTITY_B64` | Base64 of the provisioned `offline-mfi/identity.pk8` |
| `OSNPLAY_AUTH_CERTIFICATE_B64` | Base64 of the matching `offline-mfi/certificate.p7b` |

Use the same signing key as the APK already installed on the car. CI refuses to
create a replacement key. CI validates the new APK against its resolved version
and product configuration; it does not download or interpret older repository
Releases. Maintain increasing versionCode values in the release configuration.

You can configure all five secrets without printing their values:

```sh
gh auth login
python3 scripts/configure-osnplay-release-secrets.py \
  --repo xxxcrel/OsnPlay \
  --auth-assets-dir /absolute/path/to/runtime-assets
```

The helper reads the local existing signing configuration and sends each value to
`gh secret set` through stdin. It changes repository secrets only when you run
this command. No personal access token is embedded in the application. Release
publishing uses GitHub's job token with `contents: write`.

## Option A: release configuration

Edit `mobile/osnplay-release.properties`, the single default version source for
local builds and Actions:

```properties
versionName=1.5.2
versionCode=13
```

Commit and push that configuration change to `main`. The workflow automatically
builds and creates the `osnplay-v1.5.2` Release/tag at that source commit.

Raise versionCode for every published APK. The app compares versionCode, not the
tag text. A repeated code will not be offered as an update to installed apps.

## Option B: push a tag

Set both versionName and the higher versionCode in the configuration and commit
the source, then push a matching tag:

```sh
git tag osnplay-v1.5.2
git push origin osnplay-v1.5.2
```

The tag supplies versionName and must match the configuration at that commit;
versionCode comes from the same configuration. On a release branch, pushing just
the tag triggers publication without a configuration push to main. For example,
`osnplay-v1.6.0-beta.1` automatically publishes a
GitHub prerelease, excluded from the app's stable update channel.

Configuration and tag triggers can refer to the same version. Once that tag has
a published Release, the duplicate trigger skips it rather than replacing assets.

## Option C: run manually

Open **Actions → Publish OsnPlay release → Run workflow**:

- Leave `tag` and `version_code` empty to use the selected ref's configuration.
- Supply a tag such as `osnplay-v1.5.2` and a higher code such as `13` to override
  versions for that build.
- Select `prerelease` for a test-only publication.
- If a tag already exists, choose that tag/ref as the workflow source; a tag
  pointing to a different commit is refused.

## What the workflow does

1. Resolves the version, validates inputs and fixes the actual source commit.
2. Restores the existing signing/runtime inputs from Actions secrets.
3. Runs release helper tests, targeted app regressions and Release Lint.
4. Builds a signed standalone APK using JDK 25, SDK 37.0, build-tools 37.0.0 and
   NDK 28.2.13676358.
5. Generates `OsnPlay-<version>.apk` and `update.json` from that signed APK.
6. Verifies the new APK's signature, package, product resources and resolved version.
7. Creates a draft Release, uploads both assets, then publishes it. Failed uploads
   remain drafts, so the app never sees an incomplete stable update.
8. Retains the APK/metadata as Actions artifacts and removes temporary credentials
   from the runner.

All release build/publish jobs share a concurrency group. Published releases are
not overwritten. Re-run a failed draft build to finish its asset upload. To
change a published APK, create a new version/code and tag.

Installable APKs contain the explicitly provisioned runtime authentication files
as in local builds. The Android release signing keystore is never an APK or
Release asset. Source-only Android checks remain identity-free.

This workflow does not change app update behavior: OsnPlay checks GitHub only
when the user taps **Check for updates**.
