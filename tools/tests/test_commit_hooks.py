import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class CommitHookTests(unittest.TestCase):
    def test_real_hooks_block_bad_subject_and_secret_body_then_accept_valid_commit(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('PI_SCRATCH_DIR')) as directory:
            root = Path(directory)
            (root / 'tools').mkdir()
            (root / '.githooks').mkdir()
            for name in ('check_sensitive.py', 'check_commit_message.py'):
                shutil.copy2(ROOT / 'tools' / name, root / 'tools' / name)
            for name in ('pre-commit', 'commit-msg'):
                hook = root / '.githooks' / name
                shutil.copy2(ROOT / '.githooks' / name, hook)
                hook.chmod(0o755)

            def git(*args):
                return subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True)
            for args in (('init', '-q'), ('config', 'user.name', 'Local Test'),
                         ('config', 'user.email', 'test@example.invalid'), ('config', 'core.hooksPath', '.githooks'),
                         ('config', 'core.autocrlf', 'false'), ('add', '.')):
                self.assertEqual(git(*args).returncode, 0)
            invalid = git('commit', '-m', 'changes without scope')
            self.assertNotEqual(invalid.returncode, 0)
            self.assertIn('Use type(scope)', invalid.stderr)
            value = 'a1' * 16
            secret = git('commit', '-m', 'feat(repo): fixture\n\nAMAP_API_KEY=' + value)
            self.assertNotEqual(secret.returncode, 0)
            self.assertNotIn(value, secret.stdout + secret.stderr)
            self.assertIn('Sensitive content', secret.stderr)
            self.assertEqual(git('commit', '-m', 'test(repo): verify scoped commits').returncode, 0)


if __name__ == '__main__':
    unittest.main()
