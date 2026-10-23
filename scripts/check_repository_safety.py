"""Fail closed on private artifacts/recognizable credentials; never print matched values.

This is a targeted repository gate, not proof that every possible secret is absent.
"""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def inspect(path, content):
    name = path.replace('\\', '/')
    parts = name.lower().split('/')
    if any(p in ('.local', 'node_modules', '.venv', '.ci-venv', 'target', 'output') for p in parts):
        return 'private/generated directory'
    if (Path(name).name.startswith('.env') and not name.endswith('.env.example')) or re.search(r'\.(?:pem|p12|pfx|key|jks)$', name, re.I):
        return 'private configuration/key file'
    patterns = [r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
                r'\b(?:ghp_|github_pat_)[A-Za-z0-9_]{30,}',
                r'\bsk-[A-Za-z0-9_-]{24,}',
                r'https?://[^\s/:]+:[^\s/@]+@']
    if any(re.search(pattern, content) for pattern in patterns):
        return 'recognizable credential pattern'
    return None


def main():
    tracked = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode('utf-8').split('\0')
    failures = []
    for name in filter(None, tracked):
        path = ROOT / name
        if not path.is_file():
            continue
        raw = path.read_bytes()
        content = '' if b'\0' in raw else raw.decode('utf-8', errors='replace')
        reason = inspect(name, content)
        if reason:
            failures.append((name, reason))
    for name, reason in failures:
        print(f'BLOCKED {name}: {reason}')
    print(f'Repository safety: {len(failures)} blocked files; matched values suppressed.')
    return bool(failures)


if __name__ == '__main__':
    sys.exit(main())
