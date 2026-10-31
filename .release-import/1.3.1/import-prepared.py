#!/usr/bin/env python3
"""Import the authorized, hash-verified RMGU release Git objects without moving refs."""
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path.cwd()
REPO = 'xorpix/Root-My-Galaxy-Ultra'
BASE = '0785fa9b30f8954ae54dda1d81d89c55f7df576d'
TIP = '0879880188f3e754676aeabeff65c042da36162e'
TREE = '52dbf78e1622bedb5f4a13ce740fd15803109ca2'
BUNDLE_SHA256 = 'd1a91802d1c3cde9d769d8b71fa5eda088d74b9ff52f89d582e01f3c279d4f07'
WORK = Path(os.environ['RUNNER_TEMP']) / 'rmgu-1.3.1'
LAST_WRITE = 0.0


def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', '-C', str(cwd), *args])


def api(path, payload=None):
    global LAST_WRITE
    if payload is not None:
        # GitHub's content creation limit is shared across these endpoints.
        time.sleep(max(0.0, 1.05 - (time.monotonic() - LAST_WRITE)))
        LAST_WRITE = time.monotonic()
    request = urllib.request.Request(
        'https://api.github.com/repos/' + REPO + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={
            'Accept': 'application/vnd.github+json',
            'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
            'X-GitHub-Api-Version': '2022-11-28',
            'Content-Type': 'application/json',
            'User-Agent': 'RMGU-verified-release-import',
        },
        method='GET' if payload is None else 'POST',
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read(2000).decode(errors='replace')
        raise RuntimeError(f'GitHub {error.code} for {path}: {detail}') from None


def checked_post(path, payload, expected):
    result = api(path, payload)
    if result.get('sha') != expected:
        raise RuntimeError(f'Object mismatch for {path}: expected {expected}, got {result.get("sha")}')
    print('Verified', path, expected, flush=True)


def signature(value):
    match = re.fullmatch(r'(.*) <([^<>]*)> (\d+) ([+-])(\d{2})(\d{2})', value)
    if not match:
        raise ValueError('Unsupported Git signature format')
    name, email, timestamp, sign, hours, minutes = match.groups()
    offset = datetime.timedelta(hours=int(hours), minutes=int(minutes))
    if sign == '-':
        offset = -offset
    date = datetime.datetime.fromtimestamp(int(timestamp), datetime.timezone(offset))
    return {'name': name, 'email': email, 'date': date.isoformat()}


def main():
    if os.environ.get('GITHUB_REPOSITORY') != REPO:
        raise RuntimeError('Unexpected repository')
    parts = sorted((ROOT / '.release-import/1.3.1/parts').glob('*.b64'))
    expected_names = [f'{i:05d}.b64' for i in range(52)]
    if [p.name for p in parts] != expected_names:
        raise RuntimeError('Incomplete or unexpected bundle parts')
    bundle_bytes = b''.join(base64.b64decode(p.read_text().strip(), validate=True) for p in parts)
    if len(bundle_bytes) != 3342768 or hashlib.sha256(bundle_bytes).hexdigest() != BUNDLE_SHA256:
        raise RuntimeError('Bundle checksum mismatch')
    bundle = Path(os.environ['RUNNER_TEMP']) / 'rmgu-1.3.1.bundle'
    bundle.write_bytes(bundle_bytes)
    git('bundle', 'verify', str(bundle))
    git('-c', 'fetch.fsckObjects=true', 'fetch', '--no-tags', str(bundle), 'HEAD')
    if git('rev-parse', 'FETCH_HEAD').decode().strip() != TIP:
        raise RuntimeError('Unexpected bundle tip')
    if git('rev-parse', TIP + '^{tree}').decode().strip() != TREE:
        raise RuntimeError('Unexpected source tree')
    git('merge-base', '--is-ancestor', BASE, TIP)
    git('worktree', 'add', '--detach', str(WORK), TIP)
    checks = [
        'tools/check_susfs_bundle.py',
        'tools/test_backend_bundle.py',
        'tools/backend-refresh/test_refresh.py',
        'tools/test_su_process.py',
        'tools/test_early_root_speed.py',
        'tools/test_settings_presentation.py',
        'tools/test_translations.py',
        'tools/check_translations.py',
    ]
    for script in checks:
        subprocess.run(['python3', script], cwd=WORK, check=True)
    ids = git('rev-list', '--objects', '--no-object-names', TIP, '^' + BASE).decode().splitlines()
    objects = {sha: git('cat-file', '-t', sha).decode().strip() for sha in ids}
    for sha, kind in objects.items():
        if kind == 'blob':
            payload = git('cat-file', 'blob', sha)
            checked_post('/git/blobs', {'content': base64.b64encode(payload).decode(), 'encoding': 'base64'}, sha)
        elif kind not in ('commit', 'tree'):
            raise RuntimeError('Unexpected Git object type: ' + kind)
    uploaded_trees = set()

    def upload_tree(sha):
        if sha in uploaded_trees or objects.get(sha) != 'tree':
            return
        entries = []
        for item in git('ls-tree', '-z', sha).split(b'\0'):
            if not item:
                continue
            metadata, path = item.split(b'\t', 1)
            mode, kind, child = metadata.decode().split()
            if kind == 'tree':
                upload_tree(child)
            entries.append({'path': path.decode(), 'mode': mode, 'type': kind, 'sha': child})
        checked_post('/git/trees', {'tree': entries}, sha)
        uploaded_trees.add(sha)

    commits = git('rev-list', '--reverse', TIP, '^' + BASE).decode().splitlines()
    if len(commits) != 4:
        raise RuntimeError('Expected exactly four prepared commits')
    for sha in commits:
        raw = git('cat-file', 'commit', sha).decode()
        header, message = raw.split('\n\n', 1)
        fields = {}
        parents = []
        for line in header.splitlines():
            key, value = line.split(' ', 1)
            if key == 'parent':
                parents.append(value)
            elif key in ('tree', 'author', 'committer'):
                fields[key] = value
            else:
                raise RuntimeError('Unsupported commit header: ' + key)
        upload_tree(fields['tree'])
        checked_post('/git/commits', {
            'message': message,
            'tree': fields['tree'],
            'parents': parents,
            'author': signature(fields['author']),
            'committer': signature(fields['committer']),
        }, sha)
    final = api('/git/commits/' + TIP)
    if final.get('sha') != TIP or final.get('tree', {}).get('sha') != TREE:
        raise RuntimeError('Remote source verification failed')
    report = {'source_commit': TIP, 'source_tree': TREE, 'commits': commits,
              'uploaded_objects': len(objects), 'host_checks': checks,
              'branch_updated': False}
    (ROOT / 'import-result.json').write_text(json.dumps(report, indent=2) + '\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write('Verified and imported all four original release commits.\n\n')
        summary.write(f'Source commit: `{TIP}`\n\nSource tree: `{TREE}`\n\n')
        summary.write('Host checks passed. The authorized maintainer can now update PR #35 to this exact commit.\n')


if __name__ == '__main__':
    main()
