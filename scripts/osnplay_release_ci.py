"""Release CI helpers; credential values are never printed or passed on command lines."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import quote

def restore(root: Path, assets: Path, environment):
    names = ('OSNPLAY_KEYSTORE_B64', 'OSNPLAY_KEYSTORE_PASSWORD', 'OSNPLAY_KEY_ALIAS',
             'OSNPLAY_AUTH_IDENTITY_B64', 'OSNPLAY_AUTH_CERTIFICATE_B64')
    missing = [name for name in names if not environment.get(name)]
    if missing:
        raise ValueError('Missing repository secrets: ' + ', '.join(missing))
    decoded = {}
    for name in (names[0], names[3], names[4]):
        try:
            decoded[name] = base64.b64decode(environment[name], validate=True)
        except (ValueError, TypeError):
            raise ValueError('Invalid base64 secret: ' + name) from None
        if not decoded[name]:
            raise ValueError('Empty decoded secret: ' + name)
    signing = root / '.private/osnplay-signing'
    runtime = assets / 'offline-mfi'
    for directory in (signing, runtime):
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        directory.chmod(0o700)
    files = {
        signing / 'osnplay-release.p12': decoded[names[0]],
        signing / 'signing.json': json.dumps({'password': environment[names[1]], 'alias': environment[names[2]]}).encode(),
        runtime / 'identity.pk8': decoded[names[3]],
        runtime / 'certificate.p7b': decoded[names[4]],
    }
    for path, content in files.items():
        path.write_bytes(content)
        path.chmod(0o600)

def gh(arguments, missing_allowed=False):
    result = subprocess.run(['gh', *arguments], capture_output=True, text=True)
    if result.returncode:
        if missing_allowed and 'HTTP 404' in result.stderr:
            return None
        raise RuntimeError('GitHub CLI request failed; check token permissions and repository access')
    return result.stdout

def release_for_tag(repo, tag):
    value = gh(['api', f'repos/{repo}/releases/tags/{quote(tag, safe="")}'], missing_allowed=True)
    return json.loads(value) if value is not None else None

def require_tag_source(repo, tag, source_sha):
    value = gh(['api', f'repos/{repo}/git/ref/tags/{quote(tag, safe="")}'], missing_allowed=True)
    if value is None:
        return
    target = json.loads(value)['object']
    for _ in range(8):
        if target['type'] == 'commit':
            if target['sha'] != source_sha:
                raise ValueError('The release tag points to a different commit; run the workflow on that tag/ref')
            return
        if target['type'] != 'tag':
            break
        target = json.loads(gh(['api', f'repos/{repo}/git/tags/{target["sha"]}']))['object']
    raise ValueError('Release tag does not resolve to the checked-out commit')

def publish(repo, tag, source_sha, directory: Path, prerelease):
    metadata = json.loads((directory / 'update.json').read_text())
    asset = metadata['apkAsset']
    if not re.fullmatch(r'OsnPlay-[A-Za-z0-9][A-Za-z0-9._-]{0,70}\.apk', asset):
        raise ValueError('Invalid release APK filename')
    if metadata['applicationId'] != 'com.sinyee.babybus.story' or tag != 'osnplay-v' + metadata['versionName']:
        raise ValueError('Release tag/package does not match the verified APK metadata')
    existing = release_for_tag(repo, tag)
    if existing is not None and not existing['draft']:
        raise ValueError('This tag already has a published release; published APKs are not overwritten')
    require_tag_source(repo, tag, source_sha)
    if existing is None:
        command = ['release', 'create', tag, '--repo', repo, '--target', source_sha,
                   '--draft', '--title', 'OsnPlay ' + metadata['versionName'], '--generate-notes']
        if prerelease:
            command += ['--prerelease']
        gh(command)
    else:
        gh(['release', 'edit', tag, '--repo', repo, '--target', source_sha])
    # Keep the release hidden from the updater until BOTH assets have been uploaded successfully.
    gh(['release', 'upload', tag, str(directory / asset), str(directory / 'update.json'), '--repo', repo, '--clobber'])
    gh(['release', 'edit', tag, '--repo', repo, '--draft=false',
        '--prerelease=' + str(prerelease).lower(), '--latest=' + str(not prerelease).lower()])
