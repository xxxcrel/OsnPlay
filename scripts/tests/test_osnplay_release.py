import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from osnplay_release_config import resolve
from osnplay_release_ci import restore, require_tag_source, publish

class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = self.root / 'release.properties'
        self.config.write_text('versionName=1.5.1\nversionCode=12\n')

    def tearDown(self):
        self.temp.cleanup()

    def test_config_drives_the_default_release(self):
        self.assertEqual(resolve(self.config), {'tag': 'osnplay-v1.5.1', 'versionName': '1.5.1', 'versionCode': 12, 'prerelease': False})

    def test_tag_and_dispatch_code_override_the_configuration(self):
        release = resolve(self.config, 'osnplay-v1.5.2', '13')
        self.assertEqual(release['versionName'], '1.5.2')
        self.assertEqual(release['versionCode'], 13)
        self.config.write_text('versionName=1.5.2\nversionCode=13\n')
        self.assertEqual(resolve(self.config, 'osnplay-v1.5.2')['versionCode'], 13)

    def test_a_new_tag_cannot_silently_reuse_an_old_configured_version(self):
        with self.assertRaises(ValueError):
            resolve(self.config, 'osnplay-v1.5.2')

    def test_beta_tags_and_explicit_prereleases_are_not_stable(self):
        self.assertTrue(resolve(self.config, 'osnplay-v1.6.0-beta.1', '14')['prerelease'])
        self.assertTrue(resolve(self.config, prerelease=True)['prerelease'])

    def test_untrusted_tag_characters_and_invalid_codes_are_rejected(self):
        for tag in ('v1.5.2', 'osnplay-v1.5.2";exit', 'osnplay-v1.5.2\nkey=value', 'osnplay-v../file'):
            with self.assertRaises(ValueError):
                resolve(self.config, tag)
        for code in ('0', '-1', '12x', '2147483648'):
            with self.assertRaises(ValueError):
                resolve(self.config, code=code)

    def test_duplicate_version_properties_are_rejected(self):
        self.config.write_text('versionName=1.5.1\nversionCode=12\nversionCode=13\n')
        with self.assertRaises(ValueError):
            resolve(self.config)

    def inputs(self):
        return {'OSNPLAY_KEYSTORE_B64': base64.b64encode(b'synthetic-keystore').decode(),
                'OSNPLAY_KEYSTORE_PASSWORD': 'synthetic-password', 'OSNPLAY_KEY_ALIAS': 'osnplay',
                'OSNPLAY_AUTH_IDENTITY_B64': base64.b64encode(b'synthetic-identity').decode(),
                'OSNPLAY_AUTH_CERTIFICATE_B64': base64.b64encode(b'synthetic-certificate').decode()}

    def test_missing_or_invalid_secrets_fail_before_creating_signing_files(self):
        with self.assertRaises(ValueError):
            restore(self.root, self.root / 'runtime', {})
        values = self.inputs()
        values['OSNPLAY_KEYSTORE_B64'] = 'not base64'
        with self.assertRaises(ValueError):
            restore(self.root, self.root / 'runtime', values)
        self.assertFalse((self.root / '.private').exists())

    def test_restored_key_and_runtime_inputs_are_private_and_not_regenerated(self):
        restore(self.root, self.root / 'runtime', self.inputs())
        directory = self.root / '.private/osnplay-signing'
        self.assertEqual((directory / 'osnplay-release.p12').read_bytes(), b'synthetic-keystore')
        self.assertEqual(json.loads((directory / 'signing.json').read_text())['alias'], 'osnplay')
        self.assertEqual((directory / 'osnplay-release.p12').stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.root / 'runtime/offline-mfi/identity.pk8').read_bytes(), b'synthetic-identity')

    def test_a_tag_pointing_to_another_commit_cannot_publish_a_mismatched_source_archive(self):
        with patch('osnplay_release_ci.gh', return_value=json.dumps({'object': {'type': 'commit', 'sha': 'old'}})):
            with self.assertRaises(ValueError):
                require_tag_source('xxxcrel/OsnPlay', 'osnplay-v1.5.2', 'new')

    def test_an_annotated_tag_is_resolved_to_the_source_commit(self):
        responses = [json.dumps({'object': {'type': 'tag', 'sha': 'annotation'}}),
                     json.dumps({'object': {'type': 'commit', 'sha': 'source'}})]
        with patch('osnplay_release_ci.gh', side_effect=responses):
            require_tag_source('xxxcrel/OsnPlay', 'osnplay-v1.5.2', 'source')

    def test_upload_both_assets_before_publishing_the_draft(self):
        (self.root / 'update.json').write_text(json.dumps({'applicationId': 'com.sinyee.babybus.story',
                                                        'apkAsset': 'OsnPlay-1.5.2.apk', 'versionName': '1.5.2'}))
        with patch('osnplay_release_ci.release_for_tag', return_value=None), \
             patch('osnplay_release_ci.require_tag_source'), patch('osnplay_release_ci.gh', return_value='') as github:
            publish('xxxcrel/OsnPlay', 'osnplay-v1.5.2', 'source', self.root, False)
            commands = [call.args[0] for call in github.call_args_list]
            self.assertIn('--draft', commands[0])
            self.assertEqual(commands[1][1], 'upload')
            self.assertIn(str(self.root / 'update.json'), commands[1])
            self.assertIn('--draft=false', commands[2])

    def test_upload_failure_never_makes_an_incomplete_release_visible(self):
        (self.root / 'update.json').write_text(json.dumps({'applicationId': 'com.sinyee.babybus.story',
                                                        'apkAsset': 'OsnPlay-1.5.2.apk', 'versionName': '1.5.2'}))
        with patch('osnplay_release_ci.release_for_tag', return_value=None), \
             patch('osnplay_release_ci.require_tag_source'), \
             patch('osnplay_release_ci.gh', side_effect=['', RuntimeError('upload failed')]) as github:
            with self.assertRaises(RuntimeError):
                publish('xxxcrel/OsnPlay', 'osnplay-v1.5.2', 'source', self.root, False)
            self.assertEqual(github.call_count, 2)

    def test_published_releases_are_not_overwritten(self):
        (self.root / 'update.json').write_text(json.dumps({'applicationId': 'com.sinyee.babybus.story',
                                                        'apkAsset': 'OsnPlay-1.5.2.apk', 'versionName': '1.5.2'}))
        with patch('osnplay_release_ci.release_for_tag', return_value={'draft': False}), \
             patch('osnplay_release_ci.gh') as github:
            with self.assertRaises(ValueError):
                publish('xxxcrel/OsnPlay', 'osnplay-v1.5.2', 'source', self.root, False)
            github.assert_not_called()

if __name__ == '__main__':
    unittest.main()
