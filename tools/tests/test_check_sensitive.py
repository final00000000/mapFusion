"""Standard-library tests; Git repositories and generated samples live in scratch."""
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'check_sensitive.py'
HOOK = SCRIPT.parents[1] / '.githooks' / 'pre-commit'
spec = importlib.util.spec_from_file_location('check_sensitive', SCRIPT)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def rules(path, content, known=()):
    return {rule for _, rule in check.scan_content(path, content.encode(), known)}


class ContentTests(unittest.TestCase):
    def test_quoted_map_key(self):
        content = 'val apiKey = "' + 'a1' * 16 + '"'
        self.assertIn('literal-credential', rules('sample.kt', content))

    def test_properties_key(self):
        content = 'AMAP_API_KEY=' + 'b2' * 16
        self.assertIn('literal-credential', rules('settings.properties', content))

    def test_json_password(self):
        content = '{"password": "' + 'unsafe' * 2 + '"}'
        self.assertIn('literal-credential', rules('config.json', content))

    def test_placeholder_and_chinese_examples(self):
        for value in ('YOUR_AMAP_API_KEY', '<从当前 APK 读取>', '你的高德Key', '${AMAP_API_KEY}', '***REDACTED***'):
            self.assertFalse(rules('sample.md', 'AMAP_API_KEY=' + value))

    def test_property_and_environment_references(self):
        self.assertFalse(rules('sample.kts', 'val apiKey = providers.environmentVariable("AMAP_API_KEY")'))
        self.assertFalse(rules('sample.kts', 'password = repositoryPassword'))
        self.assertFalse(rules('sample.kt', 'apiKey = key'))

    def test_existing_test_placeholders(self):
        for value in ('key', 'sensitive-map-key', 'consumer-smoke-placeholder'):
            self.assertFalse(rules('sample.kt', 'apiKey = "' + value + '"'))

    def test_local_copy_check_does_not_use_placeholder_allowlist(self):
        value = 'TEST_' + 'a1' * 16
        self.assertIn('local-credential-copy', rules('sample.txt', value, (value,)))

    def test_private_key(self):
        content = '-----BEGIN ' + 'PRIVATE KEY-----'
        self.assertIn('private-key', rules('sample.pem', content))

    def test_access_token(self):
        content = 'gh' + 'p_' + 'a' * 36
        self.assertIn('access-token', rules('sample.txt', content))

    def test_colon_identity(self):
        content = 'SHA1: ' + ':'.join(['AB'] * 20)
        self.assertIn('signing-identity', rules('sample.md', content))

    def test_unseparated_identity(self):
        content = 'SHA1: ' + 'ab' * 20
        self.assertIn('signing-identity', rules('sample.md', content))

    def test_multiline_manifest(self):
        content = '<meta-data\n android:name="com.amap.api.v2.apikey"\n android:value="' + 'a1' * 16 + '" />'
        self.assertIn('manifest-credential', rules('AndroidManifest.xml', content))

    def test_manifest_placeholder(self):
        content = '<meta-data android:name="com.amap.api.v2.apikey" android:value="${AMAP_API_KEY}" />'
        self.assertFalse(rules('AndroidManifest.xml', content))

    def test_binary_local_credential(self):
        value = 'a1' * 16
        found = check.scan_content('sample.bin', b'\0' + value.encode(), (value,))
        self.assertIn((1, 'local-credential-copy'), found)

    def test_forbidden_files(self):
        for path in ('artifacts/identity.png', 'local.properties', 'app/build/config.txt', 'signing.jks', '.env', 'demo.apk'):
            self.assertIn('private-or-generated-file', rules(path, ''))
        self.assertFalse(check.forbidden_path('gradle/wrapper/gradle-wrapper.jar'))

    def test_large_file_fails_closed(self):
        self.assertEqual(check.scan_content('sample.txt', b'x' * (check.MAX_BYTES + 1)), [(1, 'file-too-large-to-scan')])


class RepositoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('PI_SCRATCH_DIR'))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Local Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'core.autocrlf', 'false')

    def git(self, *args):
        result = subprocess.run(['git', '-C', str(self.root), *args], capture_output=True)
        self.assertEqual(result.returncode, 0, 'Git fixture operation failed')
        return result

    def test_dirty_index_clean_working_tree_is_blocked(self):
        file = self.root / 'config.txt'
        value = 'a1' * 16
        file.write_text('AMAP_API_KEY=' + value)
        self.git('add', 'config.txt')
        file.write_text('AMAP_API_KEY=YOUR_AMAP_API_KEY')
        _, findings = check.check_repository(self.root, True)
        self.assertTrue(findings)
        _, working = check.check_repository(self.root, False)
        self.assertFalse(working)
        result = subprocess.run([sys.executable, str(SCRIPT), '--staged'], cwd=self.root, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(value, result.stdout + result.stderr)

    def test_clean_index_is_not_confused_by_dirty_working_tree(self):
        file = self.root / 'config.txt'
        file.write_text('AMAP_API_KEY=YOUR_AMAP_API_KEY')
        self.git('add', 'config.txt')
        file.write_text('AMAP_API_KEY=' + 'b2' * 16)
        _, findings = check.check_repository(self.root, True)
        self.assertFalse(findings)
        _, working = check.check_repository(self.root, False)
        self.assertTrue(working)

    def test_ignored_local_key_copy_is_detected(self):
        (self.root / '.gitignore').write_text('local.properties\n')
        value = 'c3' * 16
        (self.root / 'local.properties').write_text('AMAP_API_KEY=' + value)
        (self.root / 'notes.txt').write_text('Copied value: ' + value)
        self.git('add', '.gitignore', 'notes.txt')
        _, findings = check.check_repository(self.root, True)
        self.assertIn(('notes.txt', 1, 'local-credential-copy'), findings)

    def test_real_hook_blocks_commit_without_printing_values(self):
        tools = self.root / 'tools'
        tools.mkdir()
        shutil.copy2(SCRIPT, tools / SCRIPT.name)
        hooks = self.root / '.githooks'
        hooks.mkdir()
        shutil.copy2(HOOK, hooks / 'pre-commit')
        (hooks / 'pre-commit').chmod(0o755)
        self.git('config', 'core.hooksPath', '.githooks')
        value = 'd4' * 16
        (self.root / 'config.txt').write_text('AMAP_API_KEY=' + value)
        self.git('add', '.')
        result = subprocess.run(['git', '-C', str(self.root), 'commit', '-m', 'fixture'], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Commit blocked', result.stderr)
        self.assertNotIn(value, result.stdout + result.stderr)
        (self.root / 'config.txt').write_text('AMAP_API_KEY=YOUR_AMAP_API_KEY')
        self.git('add', 'config.txt')
        self.git('commit', '-m', 'fixture')


if __name__ == '__main__':
    unittest.main()
