#!/usr/bin/env python3
"""Explicitly upload existing local release inputs to GitHub Actions secrets through gh stdin."""
import argparse
import base64
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--repo', default='xxxcrel/OsnPlay')
parser.add_argument('--auth-assets-dir', type=Path, required=True)
parser.add_argument('--signing-dir', type=Path, default=Path(__file__).resolve().parents[1] / '.private/osnplay-signing')
args = parser.parse_args()
signing = json.loads((args.signing_dir / 'signing.json').read_text())
values = {
    'OSNPLAY_KEYSTORE_B64': base64.b64encode((args.signing_dir / 'osnplay-release.p12').read_bytes()),
    'OSNPLAY_KEYSTORE_PASSWORD': signing['password'].encode(),
    'OSNPLAY_KEY_ALIAS': signing['alias'].encode(),
    'OSNPLAY_AUTH_IDENTITY_B64': base64.b64encode((args.auth_assets_dir / 'offline-mfi/identity.pk8').read_bytes()),
    'OSNPLAY_AUTH_CERTIFICATE_B64': base64.b64encode((args.auth_assets_dir / 'offline-mfi/certificate.p7b').read_bytes()),
}
if not all(values.values()):
    raise SystemExit('All release inputs must be nonempty')
subprocess.run(['gh', 'auth', 'status'], check=True)
for name, value in values.items():
    subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repo], input=value, check=True)
    print('Configured repository secret: ' + name)
