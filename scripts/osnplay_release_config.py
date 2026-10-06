"""Shared, side-effect-free OSN release version resolution."""
from pathlib import Path
import re

SEMVER = re.compile(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?')
TAG_PREFIX = 'osnplay-v'

def read_config(path: Path):
    values = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith(('#', '!')):
            continue
        key, separator, value = line.partition('=')
        key, value = key.strip(), value.strip()
        if not separator or key in values:
            raise ValueError('Invalid or duplicate release configuration property')
        values[key] = value
    return values

def resolve(path: Path, tag: str = '', code: str = '', prerelease: bool = False):
    config = read_config(path)
    if tag and not tag.startswith(TAG_PREFIX):
        raise ValueError('Release tag must begin with osnplay-v')
    version = tag[len(TAG_PREFIX):] if tag else config.get('versionName', '')
    number = code or config.get('versionCode', '')
    if tag and not code and version != config.get('versionName'):
        raise ValueError('Tag version must match the release configuration, or supply an explicit versionCode override')
    if not SEMVER.fullmatch(version) or len(version) > 71:
        raise ValueError('Release version must be a valid major.minor.patch version')
    if not re.fullmatch(r'[1-9][0-9]*', number) or int(number) > 2147483647:
        raise ValueError('Release versionCode must be a positive Android integer')
    return {'tag': tag or TAG_PREFIX + version, 'versionName': version, 'versionCode': int(number),
            'prerelease': prerelease or '-' in version}
