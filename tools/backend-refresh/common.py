"""Shared checks for the repeatable backend build/release pipeline."""
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
REPOSITORIES = {
    'kernelsu': ('tiann/KernelSU', 'main', 30000),
    'kernelsu-next': ('KernelSU-Next/KernelSU-Next', 'dev', 30000),
    'resukisu': ('Baka-SU/BakaSU', 'main', 30700),
}
ASSETS = Path('app/src/main/assets')
JNI = Path('app/src/main/jniLibs/arm64-v8a')
JAVA = Path('app/src/main/java/dev/busung/s25uroot')


def capture(args, cwd=None):
    return subprocess.check_output(list(map(str, args)), cwd=cwd, text=True).strip()


def record(data):
    return {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def load_config(path=HERE / 'targets.json'):
    config = json.loads(path.read_text())
    if config.get('schema') != 2 or config.get('uapi') != 5:
        raise ValueError('Only the reviewed UAPI 5 build configuration is supported')
    if set(config['backends']) != set(REPOSITORIES):
        raise ValueError('Unexpected backend list')
    for name, target in config['backends'].items():
        expected = REPOSITORIES[name]
        if (target['repository'], target['branch'], target['version_offset']) != expected:
            raise ValueError(f'Unreviewed source/version rules: {name}')
        if not re.fullmatch(r'[0-9a-f]{40}', target['commit']):
            raise ValueError(f'Expected full upstream commit: {name}')
        if not 0 < target['version'] <= 65535:
            raise ValueError(f'Version exceeds the existing helper encoding: {name}')
        if target['version'] != target['commit_count'] + target['version_offset']:
            raise ValueError(f'Version/count mismatch: {name}')
        if not re.fullmatch(r'(?:\d+\.\d+\.\d+|nightly-\d{4}-\d{2}-\d{2})', target['rust']):
            raise ValueError('Rust toolchain must be explicitly pinned')
    return config


def baseline(project, config=None, clean=True):
    config = config or load_config(project / 'tools/backend-refresh/targets.json')
    if clean and capture(['git', 'status', '--porcelain'], project):
        raise ValueError('Commit source/build-kit changes first; a clean checkout is required')
    helper_path = project / ASSETS / 'm3q/helper-variants.json'
    helpers = json.loads(helper_path.read_text())['variants']
    generator = (project / 'tools/prepare_m3q_helpers.py').read_text()
    versions = ast.literal_eval(re.search(r'^VERSIONS = (.+)$', generator, re.M)[1])
    if versions != {n: t['version'] for n, t in config['backends'].items()}:
        raise ValueError('Helper generator is not aligned with the bundled targets')
    manifests = {}
    for name, target in config['backends'].items():
        patch = (project / 'tools/backend-refresh' / f'{name}-compat.patch').read_bytes()
        if record(patch)['sha256'] != target['compat_sha256']:
            raise ValueError(f'Compatibility patch checksum mismatch: {name}')
        if b'diff --git a/uapi/' in patch:
            raise ValueError('A header-only UAPI override is not permitted')
        folder = project / 'backends' / name
        manifest = json.loads((folder / 'build-manifest.json').read_text())
        for key, value in [('upstream_commit', target['commit']),
                           ('upstream_commit_count', target['commit_count']),
                           ('driver_version', target['version']), ('uapi_version', config['uapi']),
                           ('daemon_version', target['daemon_version'])]:
            if manifest[key] != value:
                raise ValueError(f'Baseline manifest/target mismatch: {name}/{key}')
        provenance = manifest['rebased_patch']
        if Path(provenance['path']).name != provenance['path']:
            raise ValueError('Invalid provenance patch path')
        if record((folder / provenance['path']).read_bytes()) != {
                k: provenance[k] for k in ('size', 'sha256')}:
            raise ValueError(f'Baseline patch hash mismatch: {name}')
        daemon = (project / ASSETS / 'azhl' / name / 'ksud').read_bytes()
        if record(daemon) != manifest['outputs']['ksud']:
            raise ValueError(f'Bundled daemon differs from build records: {name}')
        helper = helpers[name]
        if (helper['driverVersion'], helper['uapiVersion']) != (target['version'], config['uapi']):
            raise ValueError(f'Helper version mismatch: {name}')
        if record((project / JNI / helper['library']).read_bytes()) != manifest['outputs']['helper']:
            raise ValueError(f'Helper hash mismatch: {name}')
        if (project / JNI / ('libm3qksud_' + name.replace('-', '_') + '.so')).read_bytes() != daemon:
            raise ValueError(f'JNI/asset daemon mismatch: {name}')
        for family in ('azhl', 'df'):
            profiles = json.loads((project / ASSETS / family / 'catalog.json').read_text())['payloads']
            matches = [p for p in profiles if p['flavor'] == name]
            if not matches or any({k: p['kernelsu'][k] for k in ('size', 'sha256')} != record(daemon)
                                  for p in matches):
                raise ValueError(f'Catalog hash mismatch: {family}/{name}')
        manifests[name] = manifest
    return manifests


def app_version(project):
    matches = re.findall(r'^val appVersionBase = "(\d+\.\d+\.\d+)"$',
                         (project / 'app/build.gradle.kts').read_text(), re.M)
    if len(matches) != 1:
        raise ValueError('Expected one plain semantic appVersionBase')
    return matches[0]


def next_version(value, tags):
    major, minor, patch = map(int, value.split('.'))
    # Never republish or move an existing release tag, including manually made tags.
    for tag in tags:
        match = re.fullmatch(r'v?(\d+)\.(\d+)\.(\d+)', tag)
        if match and tuple(map(int, match.groups()[:2])) == (major, minor):
            patch = max(patch, int(match[3]))
    return f'{major}.{minor}.{patch + 1}'
