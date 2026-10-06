#!/usr/bin/env python3
"""Generate a GitHub Release APK filename and update.json from the actual signed artifact."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--build-tools', type=Path, required=True)
parser.add_argument('--output-dir', type=Path, required=True)
args = parser.parse_args()
if not args.output_dir.is_dir():
    raise SystemExit('Output directory must already exist')
badging = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
package = re.search(r"(?m)^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
sdk = re.search(r"(?m)^sdkVersion:'(\d+)'", badging)
if not package or not sdk or package.group(1) != 'com.sinyee.babybus.story' or 'application-debuggable' in badging:
    raise SystemExit('Expected a non-debug OsnPlay product APK')
name = package.group(3)
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,70}', name) or int(package.group(2)) <= 0:
    raise SystemExit('Invalid release version')
subprocess.run([str(args.build_tools / 'apksigner'), 'verify', str(args.apk)], check=True)
digest = hashlib.sha256()
with args.apk.open('rb') as apk:
    for block in iter(lambda: apk.read(1024 * 1024), b''):
        digest.update(block)
checksum = digest.hexdigest()
asset = f'OsnPlay-{name}.apk'
target = args.output_dir / asset
if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != checksum:
    raise SystemExit('A different APK already exists under the release filename')
if args.apk.resolve() != target.resolve():
    shutil.copy2(args.apk, target)
metadata = {
    'formatVersion': 1,
    'applicationId': package.group(1),
    'versionCode': int(package.group(2)),
    'versionName': name,
    'minSdk': int(sdk.group(1)),
    'apkAsset': asset,
    'sizeBytes': args.apk.stat().st_size,
    'sha256': checksum,
}
(args.output_dir / 'update.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'GitHub Release assets: {target} and {args.output_dir / "update.json"}')
