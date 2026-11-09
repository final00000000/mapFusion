"""Fail closed on credentials/identity artifacts; never print matched values.

python tools/check_sensitive.py             # tracked + non-ignored working files
python tools/check_sensitive.py --staged    # entire Git index, not working copies
Python 3.10+; standard library only. This is a guardrail, not a full secret audit.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

PRIVATE_KEY = re.compile(r"-----BEGIN (?:[A-Z0-9 ]*PRIVATE KEY|PGP PRIVATE KEY BLOCK)-----")
TOKEN = re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]{20,})\b")
FINGERPRINT = re.compile(r"\b(?:[a-fA-F0-9]{2}:){19}[a-fA-F0-9]{2}\b")
SECRET_NAME = r"[A-Za-z0-9_.-]*(?:api[_.-]?key|access[_.-]?token|auth[_.-]?token|secret|password|signing[_.-]?key)[A-Za-z0-9_.-]*"
ASSIGNMENT = re.compile(r"[\"']?(" + SECRET_NAME + r")[\"']?\s*[:=]\s*[\"']([^\"'\r\n]+)[\"']", re.I)
PROPERTY = re.compile(r"^\s*(" + SECRET_NAME + r")\s*=\s*(\S+)\s*$", re.I)
META_TAG = re.compile(r"<meta-data\b[^>]*>", re.I)
ATTRIBUTE = re.compile(r"android:(name|value)\s*=\s*[\"']([^\"']*)[\"']")
PLACEHOLDER = re.compile(r"^(?:YOUR_|EXAMPLE_|DUMMY_|TEST_|REDACTED|CHANGEME|<|\$\{|\$\(|@|\*{3})", re.I)
IDENTITY_EXTENSIONS = {'.md', '.txt', '.log', '.json', '.csv', '.xml', '.properties'}
MAX_BYTES = 8 * 1024 * 1024
SAFE_EXAMPLES = {'key', 'sensitive-map-key', 'consumer-smoke-placeholder'}
PROPERTY_EXTENSIONS = {'.properties', '.md', '.txt', '.env'}


def is_literal(value: str) -> bool:
    value = value.strip()
    return (len(value) >= 4 and value.isascii() and not PLACEHOLDER.match(value)
            and value not in SAFE_EXAMPLES
            and not any(c in value for c in ('(', ')', '{', '}', '\\')))


def forbidden_path(path: str) -> bool:
    parts = PurePosixPath(path.lower()).parts
    name = parts[-1] if parts else ''
    return (bool(parts and parts[0] == 'artifacts')
            or 'build' in parts or '.gradle' in parts
            or name in {'local.properties', 'secrets.properties', 'keystore.properties', 'id_rsa', 'id_ed25519'}
            or name == '.env' or (name.startswith('.env.') and not name.endswith(('.example', '.sample')))
            or PurePosixPath(name).suffix in {'.jks', '.keystore', '.p12', '.pfx', '.apk', '.aab'})


def scan_content(path: str, data: bytes, known_secrets: tuple[str, ...] = ()) -> list[tuple[int, str]]:
    if forbidden_path(path):
        return [(1, 'private-or-generated-file')]
    if len(data) > MAX_BYTES:
        return [(1, 'file-too-large-to-scan')]
    # Exact local secrets are checked even in binary content.
    found = set()
    for secret in known_secrets:
        if secret.encode('utf-8') in data:
            found.add((1, 'local-credential-copy'))
    if b'\x00' in data:
        return sorted(found)
    text = data.decode('utf-8-sig', errors='replace')
    for number, line in enumerate(text.splitlines(), 1):
        if PRIVATE_KEY.search(line):
            found.add((number, 'private-key'))
        if TOKEN.search(line):
            found.add((number, 'access-token'))
        if any(is_literal(m[2]) for m in ASSIGNMENT.finditer(line)):
            found.add((number, 'literal-credential'))
        prop = PROPERTY.match(line) if PurePosixPath(path).suffix.lower() in PROPERTY_EXTENSIONS else None
        if prop and is_literal(prop[2]):
            found.add((number, 'literal-credential'))
        if PurePosixPath(path).suffix.lower() in IDENTITY_EXTENSIONS:
            if FINGERPRINT.search(line) or (re.search(r'(?i)sha1|mcode', line) and re.search(r'\b[a-fA-F0-9]{40}\b', line)):
                found.add((number, 'signing-identity'))
    for tag in META_TAG.finditer(text):
        attributes = dict(ATTRIBUTE.findall(tag[0]))
        if attributes.get('name') in {'com.baidu.lbsapi.API_KEY', 'com.amap.api.v2.apikey'}:
            if is_literal(attributes.get('value', '')):
                found.add((text.count('\n', 0, tag.start()) + 1, 'manifest-credential'))
    return sorted(found)


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True)
    if result.returncode:
        raise RuntimeError('Git read failed (details omitted to protect credentials)')
    return result.stdout


def local_secrets(root: Path) -> tuple[str, ...]:
    values = set()
    local = root / 'local.properties'
    if local.is_file():
        for line in local.read_text(encoding='utf-8-sig', errors='replace').splitlines():
            match = PROPERTY.match(line)
            if match and len(match[2]) >= 8 and is_literal(match[2]):
                values.add(match[2])
    for name, value in os.environ.items():
        if (name.startswith(('BAIDU_MAP_API_KEY', 'AMAP_API_KEY', 'MAP_FUSION_SIGNING_', 'MAP_FUSION_REPOSITORY_PASSWORD'))
                and len(value) >= 8 and is_literal(value)):
            values.add(value)
    return tuple(sorted(values))


def check_repository(root: Path, staged: bool) -> tuple[int, list[tuple[str, int, str]]]:
    args = ['ls-files', '-z', '--cached']
    if not staged:
        args.extend(['--others', '--exclude-standard'])
    names = sorted(set(os.fsdecode(n) for n in git(root, *args).split(b'\x00') if n))
    secrets = local_secrets(root)
    findings = []
    count = 0
    for name in names:
        if forbidden_path(name):
            findings.append((name, 1, 'private-or-generated-file'))
            count += 1
            continue
        if staged:
            data = git(root, 'show', ':' + name)
        else:
            file = root / name
            if file.is_symlink():
                findings.append((name, 1, 'symlink-not-scanned'))
                count += 1
                continue
            if not file.exists():  # unstaged deletion
                continue
            data = file.read_bytes()
        count += 1
        findings.extend((name, line, rule) for line, rule in scan_content(name, data, secrets))
    return count, findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staged', action='store_true')
    args = parser.parse_args()
    try:
        root = Path(os.fsdecode(git(Path.cwd(), 'rev-parse', '--show-toplevel')).strip())
        count, findings = check_repository(root, args.staged)
    except (OSError, RuntimeError) as error:
        print('Sensitive check could not complete: ' + type(error).__name__, file=sys.stderr)
        return 2
    if findings:
        for path, line, rule in findings:
            print(f'{ascii(path)}:{line}: {rule}', file=sys.stderr)
        print('Commit blocked. Remove/redact the values, then stage the corrected files.', file=sys.stderr)
        return 1
    print(f'Sensitive check passed: {count} files ({"index" if args.staged else "working tree"}).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
