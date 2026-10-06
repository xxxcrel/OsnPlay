#!/usr/bin/env python3
"""Resolve tag/config/manual-dispatch version inputs for GitHub Actions."""
import argparse
import json
from pathlib import Path
from osnplay_release_config import resolve

parser = argparse.ArgumentParser()
parser.add_argument('--config', type=Path, default=Path(__file__).resolve().parents[1] / 'mobile/osnplay-release.properties')
parser.add_argument('--tag', default='')
parser.add_argument('--version-code', default='')
parser.add_argument('--prerelease', choices=('true', 'false'), default='false')
parser.add_argument('--github-output', type=Path)
args = parser.parse_args()
try:
    release = resolve(args.config, args.tag, args.version_code, args.prerelease == 'true')
except ValueError as error:
    raise SystemExit(str(error))
if args.github_output:
    with args.github_output.open('a', encoding='utf-8') as output:
        for key, value in release.items():
            output.write(f'{key}={str(value).lower() if isinstance(value, bool) else value}\n')
print(json.dumps(release))
