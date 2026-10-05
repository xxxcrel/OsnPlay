#!/usr/bin/env python3
"""Verify the signed OsnPlay product, effective resources, launcher DEX, and runtime assets."""
import argparse
import hashlib
import io
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path, required=True)
reference = parser.add_mutually_exclusive_group(required=True)
reference.add_argument('--official-apk', type=Path)
reference.add_argument('--auth-assets-dir', type=Path, help='Explicit runtime assets used by the standalone build')
parser.add_argument('--build-tools', type=Path, required=True)
parser.add_argument('--output', type=Path)
parser.add_argument('--upgrade-from', type=Path, help='Check package and signing certificate against an earlier release')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]

badging = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
assert "name='com.sinyee.babybus.story'" in badging
assert "versionName='1.3.0'" in badging
assert "versionCode='8'" in badging
assert "sdkVersion:'28'" in badging and "targetSdkVersion:'28'" in badging
assert "application: label='OsnPlay'" in badging
assert "launchable-activity: name='com.osnplay.app.MainActivity'  label='OsnPlay'" in badging
assert 'application-debuggable' not in badging
manifest = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'xmltree', str(args.apk), 'AndroidManifest.xml'], text=True)
assert 'android:sharedUserId' not in manifest, 'This build must retain an ordinary app UID'
assert 'eCarX_OpenAPI' not in manifest and 'CHANGE_COMPONENT_ENABLED_STATE' not in manifest
subprocess.run([str(args.build_tools / 'apksigner'), 'verify', '--verbose', str(args.apk)], check=True)
if args.upgrade_from:
    previous_badging = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'badging', str(args.upgrade_from)], text=True)
    assert "name='com.sinyee.babybus.story'" in previous_badging, 'Upgrade package differs'
    def signing_certificates(path):
        output = subprocess.check_output([str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(path)], text=True)
        return sorted(set(value.lower() for value in re.findall(
            r'(?m)^(?:Signer #\d+|V[\d.]+ Signer(?: #\d+)?):? certificate SHA-256 digest: ([a-fA-F0-9]{64})$', output)))
    certificates = signing_certificates(args.apk)
    assert certificates and certificates == signing_certificates(args.upgrade_from), 'Upgrade signing certificate differs'
    print(f'Upgrade package/signing certificate matches: {args.upgrade_from.name}')

resources = subprocess.check_output([str(args.build_tools / 'aapt2'), 'dump', 'resources', str(args.apk)], text=True)
def resource(name):
    match = re.search(r'(?m)^\s*resource\s+\S+\s+' + re.escape(name) + r'\s*$', resources)
    assert match is not None, f'Missing resource {name}'
    following = resources[match.end():]
    return re.split(r'(?m)^\s*(?:resource|type)\s', following, maxsplit=1)[0]

for name in ('config_standard_media_keys', 'config_require_hotspot_interface',
             'config_standard_microphone_input', 'config_require_microphone_permission', 'config_osn_ui'):
    assert '() true' in resource('bool/' + name)
assert '() false' in resource('bool/config_system_media_sync')
assert '() false' in resource('bool/config_media_focus_before_connect')
assert '() false' in resource('bool/config_cluster_map_available')
assert '() false' in resource('bool/config_byd_features')
assert '() true' in resource('bool/config_settings_button_default')
assert '() "EDGE_HOLD"' in resource('string/config_default_settings_shortcut')
assert not re.search(r'\b(?:bool/config_ecarx_features|string/ecarx_\w+)\b', resources)
for name in ('color/osn_background', 'color/osn_surface', 'color/osn_text', 'style/Theme.OsnPlay.Startup'):
    assert 'night' in resource(name), f'Missing night configuration: {name}'
for name in ('style/Theme.OsnPlay.Light', 'style/Theme.OsnPlay.Dark', 'string/osn_theme_system', 'string/osn_ui_size'):
    resource(name)
for name, value in [('config_default_hotspot_interface', ''),
                    ('config_default_hotspot_address_mode', 'IPV4'), ('config_oem_label', '领克')]:
    assert f'() "{value}"' in resource('string/' + name)
return_icon_path = re.search(r'\(file\) (\S+)', resource('raw/ic_car_home')).group(1)
app_icon_path = re.search(r"application: label='OsnPlay' icon='([^']+)'", badging).group(1)

def launcher_superclass(dex):
    def u32(offset): return struct.unpack_from('<I', dex, offset)[0]
    strings, types = u32(60), u32(68)
    def descriptor(index):
        offset = u32(strings + u32(types + index * 4) * 4)
        while dex[offset] & 0x80: offset += 1
        offset += 1
        return dex[offset:dex.index(b'\0', offset)].decode('utf-8')
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        if descriptor(u32(offset)) == 'Lcom/osnplay/app/MainActivity;':
            return descriptor(u32(offset + 8))
    return None

with zipfile.ZipFile(args.apk) as apk:
    assert apk.testzip() is None
    if args.official_apk:
        with zipfile.ZipFile(args.official_apk) as official:
            expected_assets = {name: official.read(f'assets/offline-mfi/{name}')
                               for name in ('identity.pk8', 'certificate.p7b')}
    else:
        expected_assets = {name: (args.auth_assets_dir / 'offline-mfi' / name).read_bytes()
                           for name in ('identity.pk8', 'certificate.p7b')}
    for name in ('identity.pk8', 'certificate.p7b'):
        entry = f'assets/offline-mfi/{name}'
        assert expected_assets[name] and apk.read(entry) == expected_assets[name], 'Runtime authentication mismatch'
    assert apk.read(return_icon_path) == (root / 'mobile/src/osnplay/res/raw/ic_car_home.png').read_bytes()
    assert struct.unpack('>II', apk.read(return_icon_path)[16:24]) == (512, 512)
    assert apk.read(app_icon_path).startswith(b'\x89PNG\r\n\x1a\n')
    width, height = struct.unpack('>II', apk.read(app_icon_path)[16:24])
    assert width * 9 == height * 16, 'Launcher icon is not 16:9'
    # Inspect all bitmap launcher densities and the banner: extensions must be
    # green and opaque, including corners that otherwise show black in OEM launchers.
    icon_paths = {path for name in ('mipmap/ic_osnplay', 'mipmap/ic_osnplay_round', 'drawable/osnplay_banner')
                  for path in re.findall(r'\(file\) (\S+)', resource(name))}
    assert icon_paths
    for name in icon_paths:
        image = Image.open(io.BytesIO(apk.read(name))).convert('RGBA')
        assert image.width * 9 == image.height * 16, f'Icon aspect ratio: {name}'
        assert image.getchannel('A').getextrema() == (255, 255), f'Transparent icon padding: {name}'
        for x in (0, image.width - 1):
            for y in range(image.height):
                red, green, blue, _ = image.getpixel((x, y))
                assert green > red + 30 and green > blue + 30, f'Non-green icon padding: {name}'
    for name in apk.namelist():
        if re.fullmatch(r'classes\d*\.dex', name):
            dex = apk.read(name)
            assert b'Lcom/shilapi/xcertplay/ecarx/' not in dex, 'Vehicle integration remains in DEX'
            assert b'Lcom/android/dx/stock/ProxyBuilder;' not in dex, 'Unneeded vendor callback dependency remains'
            assert b'Lcom/shilapi/xcertplay/DiPlay' not in dex, 'Old application class names remain'
    assert any(launcher_superclass(apk.read(name)) == 'Lcom/shilapi/xcertplay/OsnPlayActivity;'
               for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name))
    assert any(b'Lcom/shilapi/xcertplay/OsnUiDeviceMonitor;' in apk.read(name)
               for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name)), 'Background UI monitor is missing'
    for abi in ('arm64-v8a', 'armeabi-v7a'):
        assert any(name.startswith(f'lib/{abi}/') for name in apk.namelist())
    assert not any(name.endswith(('.jks', '.keystore', '.p12')) or name.endswith('signing.json') for name in apk.namelist())

digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
if args.output:
    assert args.output.parent.is_dir()
    if args.output.exists() and hashlib.sha256(args.output.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Output already contains a different APK; refusing to overwrite it')
    shutil.copy2(args.apk, args.output)
    args.output.with_suffix('.apk.sha256').write_text(f'{digest}  {args.output.name}\n')
    print(f'Exported: {args.output}')
print(f'Size: {args.apk.stat().st_size / 1024 / 1024:.2f} MiB')
print(f'SHA-256: {digest}')
print('Verified: non-debug release, ordinary UID, launcher class, native themed UI/night resources, green PNG icons, Lynk & Co return mark, OSN defaults, ARM libraries and authentication assets.')
