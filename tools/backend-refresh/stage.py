#!/usr/bin/env python3
"""Stage verified, matching native builds in a disposable app checkout."""
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys

HERE = Path(__file__).resolve().parent
from common import baseline, load_config
CONFIG = load_config()
JAVA = Path('app/src/main/java/dev/busung/s25uroot')
ASSETS = Path('app/src/main/assets')
JNI = Path('app/src/main/jniLibs/arm64-v8a')
sys.path.insert(0, str(HERE.parent))
from test_backend_bundle import extract_driver


def record(data):
    return {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def replace(path, before, after, count=1):
    text = path.read_text()
    if text.count(before) != count:
        raise ValueError(f'Unexpected source at {path}: {before!r}')
    path.write_text(text.replace(before, after))


def check_elf(data, kind):
    if (len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01'
            or struct.unpack_from('<HH', data, 16) != (kind, 183)):
        raise ValueError('Expected little-endian ARM64 ELF of the required type')


def verify_pair(daemon, module, target):
    check_elf(daemon, 3)
    check_elf(module, 1)
    if target['daemon_version'].encode() not in daemon:
        raise ValueError('Daemon version does not match the pinned revision')
    for marker in (b'vermagic=', b'name=kernelsu'):
        if marker not in module:
            raise ValueError('Unexpected kernel module metadata')
    if extract_driver(daemon, record(module)) != module:
        raise ValueError('Daemon embeds a different kernel driver')


def source_patch(repo, original_patch):
    # Include new compatibility source files, but never compiled module assets.
    paths = re.findall(r'^\+\+\+ b/(.+)$', original_patch.decode(), re.M)
    subprocess.run(['git', '-C', str(repo), 'add', '-N', '--', *paths], check=True)
    scope = ['kernel', 'userspace', 'Cargo.toml', 'Cargo.lock',
             ':(exclude)userspace/ksud/bin/**']
    # Three-way application stages its result. Include staged AND unstaged edits,
    # including later Cargo formatting, when recording the actual built source.
    patch = git(repo, 'diff', 'HEAD', '--binary', '--full-index', '--', *scope)
    changed = git(repo, 'diff', 'HEAD', '--name-only', '--', *scope).decode().splitlines()
    hashes = {name: record((repo / name).read_bytes())['sha256'] for name in changed}
    if b'diff --git a/uapi/' in patch:
        raise ValueError('Unexpected UAPI override')
    return patch, hashes


def backend_readme(config):
    labels = {'kernelsu': 'KernelSU', 'kernelsu-next': 'KernelSU-Next', 'resukisu': 'BakaSU'}
    rows = ['# Bundled backends', '', '| Backend | Driver / UAPI | Source | Daemon |',
            '|---|---|---|---|']
    for name, t in config['backends'].items():
        url = 'https://github.com/' + t['repository'] + '/commit/' + t['commit']
        rows.append(f'| {labels[name]} | {t["version"]} / {config["uapi"]} | '
                    f'[{t["commit"][:8]}]({url}) | {t["daemon_version"]} |')
    rows += ['', 'Each folder contains the exact driver/daemon hashes and the complete Samsung',
             'compatibility patch for that revision. Keep these records in git: they are used',
             'by the weekly build and by `tools/test_backend_bundle.py`.', '',
             'Use a UAPI 5 manager. BakaSU retains the internal `resukisu` id.',
             'After installing a new APK, fully reboot and activate root to load its driver.',
             'A manager APK update alone does not replace the running driver.', '',
             'Build and bundle validation is not phone testing. Firmware support, exploit',
             'algorithms, and Samsung compatibility behavior are not changed by this pipeline.',
             'See [the build workflow](../tools/backend-refresh/README.md).', '']
    return '\n'.join(rows)


def main(project, work, config=None):
    config = config or load_config(work / 'targets.json')
    old_config = load_config(project / 'tools/backend-refresh/targets.json')
    previous = baseline(project, old_config)
    base = git(project, 'rev-parse', 'HEAD').decode().strip()
    if base != config['app_base']:
        raise ValueError('The source checkout changed after upstream discovery')
    inputs = {}
    # Validate ALL inputs before any app asset is replaced.
    for name, target in config['backends'].items():
        patch = (project / 'tools/backend-refresh' / f'{name}-compat.patch').read_bytes()
        if record(patch)['sha256'] != target['compat_sha256']:
            raise ValueError(f'Compatibility patch checksum mismatch: {name}')
        if not target['rebuild']:
            if target['commit'] != previous[name]['upstream_commit']:
                raise ValueError('A changed revision cannot reuse an old daemon')
            if target['compat_sha256'] != previous[name]['rebased_patch']['sha256']:
                raise ValueError('A changed compatibility patch requires a native rebuild')
            daemon = (project / ASSETS / 'azhl' / name / 'ksud').read_bytes()
            module = extract_driver(daemon, previous[name]['outputs']['kernelsu.ko'])
            verify_pair(daemon, module, target)
            provenance = project / 'backends' / name / previous[name]['rebased_patch']['path']
            inputs[name] = daemon, provenance.read_bytes(), previous[name]['patched_source_sha256'], record(module)
            continue
        repo = work / name
        if git(repo, 'rev-parse', 'HEAD').decode().strip() != target['commit']:
            raise ValueError(f'Wrong source revision: {name}')
        if int(git(repo, 'rev-list', '--count', 'HEAD')) != target['commit_count']:
            raise ValueError(f'Incomplete source history: {name}')
        if 'KERNEL_SU_UAPI_VERSION = 5;' not in (repo / 'uapi/supercall.h').read_text():
            raise ValueError(f'Unexpected source UAPI: {name}')
        subprocess.run(['git', '-C', str(repo), 'diff', '--exit-code', 'HEAD', '--', 'uapi'], check=True)
        built = work / 'built' / name
        daemon, module = (built / 'ksud').read_bytes(), (built / 'kernelsu.ko').read_bytes()
        receipt = json.loads((built / 'build-receipt.json').read_text())
        if receipt != {'commit': target['commit'], 'driver_version': target['version'],
                       'uapi': config['uapi'], 'ksud': record(daemon), 'kernelsu.ko': record(module)}:
            raise ValueError(f'Native build receipt mismatch: {name}')
        verify_pair(daemon, module, target)
        actual_patch, hashes = source_patch(repo, patch)
        inputs[name] = daemon, actual_patch, hashes, record(module)

    for name, (daemon, _, _, _) in inputs.items():
        (project / ASSETS / 'azhl' / name / 'ksud').write_bytes(daemon)
    for family in ('azhl', 'df'):
        path = project / ASSETS / family / 'catalog.json'
        catalog = json.loads(path.read_text())
        for profile in catalog['payloads']:
            profile['kernelsu'].update(record(inputs[profile['flavor']][0]))
        write_json(path, catalog)
    # Change the existing expected-version literals, keeping control logic intact.
    for relative in ('tools/prepare_m3q_helpers.py', JAVA / 'AzhlCatalog.kt',
                     'native/azhl/src/azhl_backend.h'):
        path = project / relative
        text = path.read_text()
        for name, target in config['backends'].items():
            old = previous[name]['driver_version']
            text, count = re.subn(r'\b' + str(old) + r'\b', str(target['version']), text)
            if count == 0:
                raise ValueError(f'Expected version literal missing: {relative}/{name}')
        path.write_text(text)
    subprocess.run([sys.executable, project / 'tools/prepare_m3q_helpers.py'], check=True,
                   stdout=subprocess.DEVNULL)
    helpers = json.loads((project / ASSETS / 'm3q/helper-variants.json').read_text())['variants']
    launch = project / JAVA / 'M3qLaunch.kt'
    for name, target in config['backends'].items():
        old = previous[name]
        replace(launch,
                f'"{name}" -> Backend(id, {old["driver_version"]}, "{old["outputs"]["helper"]["sha256"]}")',
                f'"{name}" -> Backend(id, {target["version"]}, "{helpers[name]["sha256"]}")')
    for name, target in config['backends'].items():
        daemon, patch, hashes, module_record = inputs[name]
        folder = project / 'backends' / name
        patch_name = f'samsung-compat-{target["version"]}.patch'
        old_patch = folder / previous[name]['rebased_patch']['path']
        if old_patch.name != patch_name:
            old_patch.unlink()
        (folder / patch_name).write_bytes(patch)
        manifest = dict(previous[name])
        if target['rebuild']:
            manifest.update(created_utc=config['checked_date'], app_base_commit=base,
                            upstream_repository='https://github.com/' + target['repository'],
                            previous_upstream_commit=previous[name]['upstream_commit'],
                            upstream_commit=target['commit'], upstream_commit_count=target['commit_count'],
                            driver_version=target['version'], uapi_version=config['uapi'],
                            manager_required_uapi=config['uapi'], daemon_version=target['daemon_version'],
                            validation={'kernel_build': 'passed', 'daemon_build': 'passed',
                                        'exact_driver_in_daemon': 'passed',
                                        'apk_build': 'not run', 'device_test': 'not run'})
            manifest.pop('driver_version_string', None)
            manifest['toolchains']['daemon_rust'] = target['rust']
            # Carry forward the full rebased patch as input to the next weekly run.
            (project / 'tools/backend-refresh' / f'{name}-compat.patch').write_bytes(patch)
            target['compat_sha256'] = record(patch)['sha256']
        manifest['outputs'] = {'ksud': record(daemon), 'kernelsu.ko': module_record,
                               'helper': record((project / JNI / helpers[name]['library']).read_bytes())}
        manifest['rebased_patch'] = dict(path=patch_name, **record(patch))
        manifest['patched_source_sha256'] = hashes
        write_json(folder / 'build-manifest.json', manifest)
    readme = project / 'README.md'
    text = readme.read_text()
    for name, target in config['backends'].items():
        text = text.replace(f'`{previous[name]["driver_version"]} / 5`', f'`{target["version"]} / 5`')
    readme.write_text(text)
    (project / 'backends/README.md').write_text(backend_readme(config))
    write_json(project / 'tools/backend-refresh/targets.json', dict(
        config, build_status='built', backends={n: dict(t, rebuild=False) for n, t in config['backends'].items()}))
    # Profiles/routing and every exploit/bridge file must be byte-for-byte preserved.
    for family in ('azhl', 'df'):
        relative = ASSETS / family / 'catalog.json'
        before = json.loads(git(project, 'show', f'{base}:{relative}'))
        after = json.loads((project / relative).read_text())
        for old, new in zip(before['payloads'], after['payloads'], strict=True):
            old.pop('kernelsu')
            new.pop('kernelsu')
        if before != after:
            raise ValueError(f'Unexpected firmware/routing change: {family}')
    protected = ['app/src/main/cpp/dirtyfrag', 'app/src/main/assets/m3q/libm3qpayload.so',
                 'app/src/main/assets/m3q/libm3qroot.so',
                 'app/src/main/jniLibs/arm64-v8a/libm3qpayload.so']
    if git(project, 'diff', '--name-only', base, '--', *protected).strip():
        raise ValueError('Unexpected exploit or bridge edits')
    baseline(project, clean=False)
    print('Matching native pairs staged; firmware and exploit code preserved.')


if __name__ == '__main__':
    main(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
