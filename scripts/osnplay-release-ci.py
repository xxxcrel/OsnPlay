#!/usr/bin/env python3
import argparse
import os
from pathlib import Path
from osnplay_release_ci import restore, release_for_tag, publish

parser = argparse.ArgumentParser()
commands = parser.add_subparsers(dest='command', required=True)
credentials = commands.add_parser('restore')
credentials.add_argument('--assets-dir', type=Path, required=True)
credentials.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
existing = commands.add_parser('exists')
existing.add_argument('--repo', required=True)
existing.add_argument('--tag', required=True)
existing.add_argument('--github-output', type=Path, required=True)
release = commands.add_parser('publish')
release.add_argument('--repo', required=True)
release.add_argument('--tag', required=True)
release.add_argument('--source-sha', required=True)
release.add_argument('--directory', type=Path, required=True)
release.add_argument('--prerelease', choices=('true', 'false'), required=True)
args = parser.parse_args()
try:
    if args.command == 'restore':
        restore(args.root, args.assets_dir, os.environ)
        print('Restored existing signing key and explicit runtime inputs.')
    elif args.command == 'exists':
        value = release_for_tag(args.repo, args.tag)
        published = value is not None and not value['draft']
        with args.github_output.open('a') as output:
            output.write('published=' + str(published).lower() + '\n')
        print('Release already published; skipping.' if published else 'Release tag is available for publishing.')
    else:
        publish(args.repo, args.tag, args.source_sha, args.directory, args.prerelease == 'true')
        print('Published verified APK and update.json.')
except (ValueError, RuntimeError) as error:
    raise SystemExit(str(error))
