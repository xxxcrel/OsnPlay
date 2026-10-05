#!/usr/bin/env python3
"""Build the standalone OsnPlay release using a persistent, local-only signing key."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
from urllib.parse import urlparse

parser = argparse.ArgumentParser()
parser.add_argument('--auth-assets-dir', type=Path, required=True)
parser.add_argument('--proxy-url', help='Optional HTTP proxy for Gradle dependency downloads')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
directory = root / '.private/osnplay-signing'
directory.mkdir(parents=True, exist_ok=True, mode=0o700)
directory.chmod(0o700)
settings = directory / 'signing.json'
keystore = directory / 'osnplay-release.p12'
if settings.exists():
    values = json.loads(settings.read_text())
else:
    if keystore.exists():
        raise RuntimeError('Signing key exists without its local configuration; refusing to replace it')
    values = {'password': secrets.token_urlsafe(36), 'alias': 'osnplay'}
    settings.write_text(json.dumps(values))
    settings.chmod(0o600)
env = os.environ.copy()
env.update(ANDROID_KEYSTORE_PATH=str(keystore), ANDROID_KEYSTORE_PASSWORD=values['password'],
           ANDROID_KEY_ALIAS=values['alias'], ANDROID_KEY_PASSWORD=values['password'],
           OSNPLAY_AUTH_ASSETS_DIR=str(args.auth_assets_dir.resolve()), OSNPLAY_STORE_PASSWORD=values['password'])
if not keystore.exists():
    keytool = Path(env['JAVA_HOME']) / 'bin/keytool'
    subprocess.run([str(keytool), '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12',
                    '-storepass:env', 'OSNPLAY_STORE_PASSWORD', '-keypass:env', 'OSNPLAY_STORE_PASSWORD',
                    '-alias', values['alias'], '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000',
                    '-dname', 'CN=OsnPlay', '-noprompt'], env=env, check=True)
    keystore.chmod(0o600)
command = [str(root / 'gradlew'), '-PosnPlay', ':mobile:lintRelease', ':mobile:assembleStandaloneRelease',
           '--console=plain', '--max-workers=4']
if args.proxy_url:
    proxy = urlparse(args.proxy_url)
    if not proxy.hostname or not proxy.port:
        raise ValueError('Proxy URL must include a host and port')
    command.extend([f'-Dhttp.proxyHost={proxy.hostname}', f'-Dhttp.proxyPort={proxy.port}',
                    f'-Dhttps.proxyHost={proxy.hostname}', f'-Dhttps.proxyPort={proxy.port}'])
subprocess.run(command, cwd=root, env=env, check=True)
print(f'Release APK: {root / "mobile/build/outputs/apk/release/mobile-release.apk"}')
print(f'Persistent signing configuration: {directory} (retain it for future updates)')
