import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_commit_message import validate


class CommitMessageTests(unittest.TestCase):
    def test_valid_scoped_subjects(self):
        for subject in ('feat(app): 完善状态管理', 'fix(map-baidu): 修复回调', 'docs(plan): 更新验收状态', 'refactor(app)!: 更换接口'):
            self.assertEqual([], validate(subject.encode('utf-8')))

    def test_missing_scope_is_rejected(self):
        self.assertTrue(validate(b'feat: missing scope'))

    def test_unknown_type_is_rejected(self):
        self.assertTrue(validate(b'update(app): change'))

    def test_empty_description_and_fullwidth_delimiters_are_rejected(self):
        self.assertTrue(validate(b'feat(app): '))
        self.assertTrue(validate('feat（app）：描述'.encode('utf-8')))

    def test_body_is_allowed(self):
        self.assertEqual([], validate(b'fix(app): isolate callbacks\n\nAdd request generation tests.\n'))

    def test_sensitive_body_is_rejected_without_including_matched_value(self):
        value = 'a1' * 16
        message = 'feat(app): example\n\nAMAP_API_KEY=' + value
        errors = validate(message.encode())
        self.assertTrue(errors)
        self.assertNotIn(value, '\n'.join(errors))


if __name__ == '__main__':
    unittest.main()
