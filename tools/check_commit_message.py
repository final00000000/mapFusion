"""Check Conventional Commit subject and sensitive contents without printing values."""
import argparse
import re
import sys
from pathlib import Path
from check_sensitive import local_secrets, scan_content

SUBJECT = re.compile(r'^(feat|fix|refactor|docs|test|chore|build|ci|perf|revert)\([a-z][a-z0-9_.-]*\)!?: \S.*$')


def validate(data: bytes, known_secrets: tuple[str, ...] = ()) -> list[str]:
    text = data.decode('utf-8-sig', errors='replace')
    subject = text.splitlines()[0] if text.splitlines() else ''
    errors = []
    if not SUBJECT.fullmatch(subject):
        errors.append('Use type(scope): description; for example feat(app): improve demo state.')
    if scan_content('commit-message.txt', data, known_secrets):
        errors.append('Sensitive content detected in commit message; remove it before committing.')
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', type=Path)
    args = parser.parse_args()
    try:
        errors = validate(args.path.read_bytes(), local_secrets(Path.cwd()))
    except OSError:
        print('Commit message check could not read its input.', file=sys.stderr)
        return 2
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
