#!/usr/bin/env python3
"""Promote the already-qualified backend artifact and dispatch the stable build."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

REPO = 'xorpix/Root-My-Galaxy-Ultra'
MAIN = '9d4c955808f19f0acf73802dbbfeecc5ba4046cc'
BASE = 'debb8b3d0b8acf16a604bd12f876f099a35b9e17'
TREE = '724594a725e02b9fc949feda1c519c0994781719'
VERSION = '1.3.5'
BRANCH = 'maintenance/release-1.3.5-20261108'
TEMP_FILES = ['.github/workflows/release-135-prepare.yml', 'tools/prepare_release_135.py']
OVERLAYS = ['.github/workflows/backend-refresh.yml', 'docs/release-notes/1.3.5.md']
STALE = {
    'codex/susfs-modules-show-20261108': 'd5cf33dac2ac571adcd92cb22988d46971f63a81',
    'codex/nomount-hiding-20261108': '0406dd289c9a2f0d68d35188889fae0667f53bae',
}
INPUT_HASHES = {
    'release.json': '71390fcc089954c9d89a50b0a217fe1ca24a81662284399edc12cc6284d76f49',
    'apply-matched-backends.patch': '4bd4a6079c59fc05f28cd291d0df6e86403eea32ce2b18f8901163bef2e1afc9',
}


def run(args, cwd, **kwargs):
    return subprocess.run([str(a) for a in args], cwd=cwd, check=True, **kwargs)


def capture(args, cwd):
    return subprocess.check_output([str(a) for a in args], cwd=cwd).decode().strip()


def git(root, *args):
    return capture(['git', *args], root)


def api(root, suffix):
    return json.loads(capture(['gh', 'api', f'repos/{REPO}/{suffix}'], root))


def remote(root, branch):
    value = git(root, 'ls-remote', '--exit-code', 'origin', 'refs/heads/' + branch).split()
    assert len(value) == 2 and value[1] == 'refs/heads/' + branch, branch
    return value[0]


def unused_backend_patches(root):
    deleted = []
    for name in ('kernelsu', 'kernelsu-next', 'resukisu', 'kowsu'):
        folder = Path('backends') / name
        manifest = json.loads((root / folder / 'build-manifest.json').read_text())
        keep = (folder / manifest['rebased_patch']['path']).as_posix()
        files = git(root, 'ls-files', '--', str(folder / 'samsung-compat-*.patch')).splitlines()
        for path in files:
            if path == keep or not re.fullmatch(r'samsung-compat-\d+\.patch', Path(path).name):
                continue
            refs = subprocess.run(['git', 'grep', '-l', '-F', '--', Path(path).name,
                                   '.', ':(exclude)' + path], cwd=root, capture_output=True, text=True)
            if refs.returncode == 0:
                continue  # Preserve any file still referenced by code, manifests or documentation.
            assert refs.returncode == 1, refs.stderr
            run(['git', 'rm', '--', path], root)
            deleted.append(path)
    return deleted


def main():
    root = Path(os.environ['GITHUB_WORKSPACE']).resolve()
    job = Path(os.environ['RUNNER_TEMP'])
    result = job / 'matched-backends'
    preparation = os.environ['GITHUB_SHA']
    assert os.environ['GITHUB_REPOSITORY'] == REPO
    assert os.environ['GITHUB_REF'] == 'refs/heads/' + BRANCH
    assert git(root, 'rev-parse', 'HEAD') == preparation
    assert git(root, 'rev-parse', 'HEAD^') == MAIN
    assert remote(root, 'main') == remote(root, 'dev') == MAIN
    assert remote(root, BRANCH) == preparation
    for name, sha in STALE.items():
        assert remote(root, name) == sha, 'Reviewed branch changed: ' + name
        run(['git', 'merge-base', '--is-ancestor', sha, MAIN], root)
    assert api(root, 'actions/runs/42509311883')['conclusion'] == 'success'
    for name, digest in INPUT_HASHES.items():
        assert hashlib.sha256((result / name).read_bytes()).hexdigest() == digest, name
    info = json.loads((result / 'release.json').read_text())
    assert info['base_commit'] == BASE and info['version'] == VERSION
    for name, target in info['targets']['backends'].items():
        answer = json.loads(capture(['gh', 'api', 'repos/' + target['repository'] +
                                     '/git/ref/heads/' + target['branch']], root))
        assert answer['object']['sha'] == target['commit'], 'Upstream advanced: ' + name
    tag = subprocess.run(['gh', 'api', f'repos/{REPO}/git/ref/tags/{VERSION}'],
                         cwd=root, capture_output=True, text=True)
    assert tag.returncode != 0 and ('404' in tag.stderr or '404' in tag.stdout), 'Version already tagged or tag check failed'

    overlays = {p: (root / p).read_bytes() for p in OVERLAYS}
    run(['git', 'fetch', 'origin', BASE], root)
    assert git(root, 'rev-parse', BASE + '^{tree}') == TREE
    assert git(root, 'rev-parse', MAIN + '^{tree}') == TREE
    verified = job / 'verified-backend-source'
    run(['git', 'worktree', 'add', '--detach', verified, BASE], root)
    sys.path.insert(0, str(verified / 'tools/backend-refresh'))
    spec = importlib.util.spec_from_file_location('trusted_release', verified / 'tools/backend-refresh/release.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    checked = module.apply_checked_update(result)
    assert checked == info
    numstat = subprocess.check_output(['git', 'apply', '--numstat', '-'], cwd=verified,
                                      input=(result / 'apply-matched-backends.patch').read_bytes()).decode()
    paths = [line.split('\t', 2)[2] for line in numstat.splitlines()]
    assert paths and all('\n' not in p and not p.startswith('/') and '..' not in Path(p).parts for p in paths)
    run(['git', 'add', '-A', '--', *paths], verified)
    payload_tree = git(verified, 'write-tree')
    run(['git', 'read-tree', '--reset', '-u', payload_tree], root)
    for path, data in overlays.items():
        dest = root / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    assert all(not (root / p).exists() for p in TEMP_FILES), 'Temporary release files retained'
    deleted = unused_backend_patches(root)
    run(['git', 'add', '-A', '--', *OVERLAYS], root)
    allowed = set(paths) | set(TEMP_FILES) | set(OVERLAYS) | set(deleted)
    staged = set(git(root, 'diff', '--cached', '--name-only').splitlines())
    assert staged <= allowed, 'Unexpected staged paths: ' + repr(staged - allowed)
    run(['git', 'diff', '--cached', '--check', '--', '.', ':(exclude)backends/**/*.patch',
         ':(exclude)tools/backend-refresh/*-compat.patch'], root)
    assert module.verify_apk(result / 'apk-input.apk', root, info) == info['apk_version_code']
    run([sys.executable, 'tools/check_susfs_bundle.py', '--apk', result / 'apk-input.apk'], root)
    run([sys.executable, 'tools/test_backend_bundle.py'], root)
    manifest = json.loads((root / 'backends/susfs/build-manifest.json').read_text())
    assert manifest['sourceCommit'] == '08cff8220c7db9e37fa378a5757d950e6bdb0d1a'
    assert manifest['module']['sha256'] == 'da363afd8dbf793526701193b2d89aef01b8efc40b245c840f9ce8a56dafb602'
    assert manifest['validation']['hardwareValidated']
    assert 'val appVersionBase = "1.3.5"' in (root / 'app/build.gradle.kts').read_text()
    run(['git', 'config', 'user.name', 'github-actions[bot]'], root)
    run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], root)
    run(['git', 'commit', '-m', 'Release 1.3.5'], root)
    commit = git(root, 'rev-parse', 'HEAD')
    for branch, expected in {'main': MAIN, 'dev': MAIN, BRANCH: preparation, **STALE}.items():
        assert remote(root, branch) == expected, 'Branch moved; refusing publication: ' + branch
    for sha in [MAIN, preparation, *STALE.values()]:
        run(['git', 'merge-base', '--is-ancestor', sha, commit], root)
    push = ['git', 'push', '--atomic']
    for branch, expected in {BRANCH: preparation, **STALE}.items():
        push.append('--force-with-lease=refs/heads/' + branch + ':' + expected)
    push += ['origin', 'HEAD:refs/heads/main', 'HEAD:refs/heads/dev']
    push += [':refs/heads/' + branch for branch in [*STALE, BRANCH]]
    run(push, root)
    summary = {'version': VERSION, 'sourceCommit': commit, 'backendArtifactRun': 42509311883,
               'artifactBase': BASE, 'removedBranches': [*STALE, BRANCH], 'removedPatches': deleted,
               'scheduledSkipUtc': '2026-11-09', 'nextNormalWeeklyUtc': '2026-11-16T03:17:00Z'}
    (job / 'release-preparation.json').write_text(json.dumps(summary, indent=2) + '\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
        out.write(f'Prepared **{VERSION}** at `{commit}`; main/dev synced. Removed {len(deleted)} unreferenced superseded patches and the three reviewed temporary/merged branches.\n')
    run(['gh', 'workflow', 'run', 'release.yml', '--repo', REPO, '--ref', 'main', '-f', 'tag_name=1.3.5'], root)
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
