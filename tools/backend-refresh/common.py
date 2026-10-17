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
# KernelSU 6 adds symbol lookup and a preinit flag. Its GET_INFO layout,
# install-fd magic and existing flag meanings are unchanged. Forks remain at 5.
REVIEWED_UAPIS = {'kernelsu': {5, 6}, 'kernelsu-next': {5, 6}, 'resukisu': {5}}
ASSETS = Path('app/src/main/assets')
JNI = Path('app/src/main/jniLibs/arm64-v8a')
JAVA = Path('app/src/main/java/dev/busung/s25uroot')
FLAVOR_ENUMS = {'kernelsu': 'KernelSu', 'kernelsu-next': 'KernelSuNext', 'resukisu': 'ReSukiSU'}


def capture(args, cwd=None):
    return subprocess.check_output(list(map(str, args)), cwd=cwd, text=True).strip()


def record(data):
    return {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def load_config(path=HERE / 'targets.json'):
    config = json.loads(path.read_text())
    if config.get('schema') != 3 or 'uapi' in config:
        raise ValueError('Expected reviewed per-backend UAPI configuration')
    if set(config['backends']) != set(REPOSITORIES):
        raise ValueError('Unexpected backend list')
    for name, target in config['backends'].items():
        if target.get('uapi') not in REVIEWED_UAPIS[name]:
            raise ValueError(f'Unreviewed UAPI: {name}')
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


def reviewed_uapi(header, name):
    """Check the control contract before accepting a reviewed upstream API number."""
    versions = re.findall(r'KERNEL_SU_UAPI_VERSION\s*=\s*(\d+)\s*;', header)
    if len(versions) != 1 or int(versions[0]) not in REVIEWED_UAPIS[name]:
        raise ValueError(f'{name}: new UAPI requires a reviewed helper/control-channel update')
    clean = re.sub(r'/\*.*?\*/|//[^\n]*', '', header, flags=re.S)
    match = re.search(r'struct ksu_get_info_cmd\s*\{([^}]+)\};', clean)
    expected = '__u32 version; __u32 flags; __u32 features; __u32 uapi_version;'
    if not match or ' '.join(match[1].split()) != expected:
        raise ValueError(f'{name}: GET_INFO control layout requires review')
    if not re.search(r"KSU_IOCTL_GET_INFO\s*(?:=|,)\s*_IOR\(\s*'K'\s*,\s*2\s*,\s*struct ksu_get_info_cmd\s*\)", clean):
        raise ValueError(f'{name}: GET_INFO ioctl requires review')
    return int(versions[0])


def daemon_release(target):
    version = re.sub(r'-\d+-g[0-9a-f]+$', '', target['daemon_version'])
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', version):
        raise ValueError('Unrecognized daemon release name')
    return version


def catalog_releases(project):
    versions = {}
    for family in ('azhl', 'df'):
        profiles = json.loads((project / ASSETS / family / 'catalog.json').read_text())['payloads']
        for profile in profiles:
            name, version = profile['flavor'], profile['kernelsu']['version']
            if name in versions and versions[name] != version:
                raise ValueError('Bundled catalogs disagree on the backend release')
            versions[name] = version
    if set(versions) != set(REPOSITORIES):
        raise ValueError('Missing backend release metadata')
    return versions


def release_label_edit(text, previous, targets):
    for name, target in targets.items():
        before = f'KernelSuFlavor.{FLAVOR_ENUMS[name]} -> "{previous[name]}"'
        if text.count(before) != 1:
            raise ValueError(f'Expected one backend release label: {name}')
        text = text.replace(before, f'KernelSuFlavor.{FLAVOR_ENUMS[name]} -> "{daemon_release(target)}"')
    return text


def sync_release_labels(project, targets):
    """Update version labels together without touching payload hashes or firmware identities."""
    previous = catalog_releases(project)
    source = project / JAVA / 'AzhlCatalog.kt'
    source.write_text(release_label_edit(source.read_text(), previous, targets))
    for family in ('azhl', 'df'):
        path = project / ASSETS / family / 'catalog.json'
        catalog = json.loads(path.read_text())
        for profile in catalog['payloads']:
            profile['kernelsu']['version'] = daemon_release(targets[profile['flavor']])
        write_json(path, catalog)


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
    uapis = ast.literal_eval(re.search(r'^UAPI_VERSIONS = (.+)$', generator, re.M)[1])
    if uapis != {n: t['uapi'] for n, t in config['backends'].items()}:
        raise ValueError('Helper UAPI requirements differ from bundled targets')
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
                           ('driver_version', target['version']), ('uapi_version', target['uapi']),
                           ('manager_required_uapi', target['uapi']),
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
        if (helper['driverVersion'], helper['uapiVersion']) != (target['version'], target['uapi']):
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
