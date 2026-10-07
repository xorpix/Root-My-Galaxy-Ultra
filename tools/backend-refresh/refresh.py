#!/usr/bin/env python3
"""Build changed, snapshot-pinned upstreams, then stage one complete validated update."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request

from common import HERE, PROJECT, app_version, baseline, capture, load_config, next_version, write_json
from probe import needs_rebuild, probe
import patching


def run(args, log, cwd=None, env=None):
    print('Running:', ' '.join(map(str, args)), flush=True)
    with log.open('w') as output:
        result = subprocess.run(list(map(str, args)), cwd=cwd, env=env,
                                stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text(errors='replace')[-6000:], flush=True)
        raise RuntimeError(f'Command failed ({result.returncode}); see {log}')


def verify_upstream(repo, name, target):
    if capture(['git', 'rev-parse', '--is-shallow-repository'], repo) != 'false':
        raise ValueError('Complete upstream history is required for version calculation')
    header = (repo / 'uapi/supercall.h').read_text()
    if not re.search(r'KERNEL_SU_UAPI_VERSION\s*=\s*5\s*;', header):
        raise ValueError(f'{name}: new UAPI requires a reviewed helper/control-channel update')
    kbuild = (repo / 'kernel/Kbuild').read_text()
    formula = (r'expr 30000 \+ \$\(KSU_LOCAL_VERSION\) \+ 700' if name == 'resukisu'
               else r'expr 30000 \+ \$\(KSU_GIT_VERSION\)')
    build_rs = (repo / 'userspace/ksud/build.rs').read_text()
    code = '30000 + 700 + version_code' if name == 'resukisu' else '30000 + version_code'
    if not re.search(formula, kbuild) or code not in build_rs:
        raise ValueError(f'{name}: upstream version calculation changed; manual review required')
    count = int(capture(['git', 'rev-list', '--count', 'HEAD'], repo))
    version = target['version_offset'] + count
    if not 0 < version <= 65535:
        raise ValueError('New driver version no longer fits the reviewed helper')
    describe = capture(['git', 'describe', '--tags', '--always'], repo).removeprefix('v')
    if not re.fullmatch(r'[0-9A-Za-z.+-]+', describe):
        raise ValueError('Unexpected upstream version name')
    return count, version, describe


def discover(project, work, snapshot):
    config = load_config(project / 'tools/backend-refresh/targets.json')
    manifests = baseline(project, config)
    base = capture(['git', 'rev-parse', 'HEAD'], project)
    if (snapshot.get('schema') != 1 or snapshot['app_base'] != base
            or set(snapshot['heads']) != set(config['backends'])):
        raise ValueError('Snapshot does not belong to this clean source revision')
    config.update(app_base=base, checked_date=datetime.now(timezone.utc).date().isoformat(),
                  build_status='not_built')
    logs = work / 'logs'
    logs.mkdir(parents=True, exist_ok=True)
    for name, target in config['backends'].items():
        sha = snapshot['heads'][name]
        if not re.fullmatch(r'[0-9a-f]{40}', sha):
            raise ValueError('Expected a full commit SHA in snapshot')
        source_changed = sha != target['commit']
        target['rebuild'] = needs_rebuild(target, sha, manifests[name])
        if not target['rebuild']:
            continue
        repo = work / name
        if repo.exists():
            raise ValueError('Use a new work directory for source clones')
        run(['git', 'clone', 'https://github.com/' + target['repository'] + '.git', repo],
            logs / f'{name}-clone.log')
        run(['git', 'checkout', '--detach', sha], logs / f'{name}-checkout.log', repo)
        # Refuse a rewritten history or an accidental rollback.
        subprocess.run(['git', 'merge-base', '--is-ancestor', target['commit'], sha], cwd=repo, check=True)
        count, version, describe = verify_upstream(repo, name, target)
        if source_changed and version <= target['version']:
            raise ValueError('Upstream version must increase when the pinned commit changes')
        if not source_changed and (count, version, describe) != (
                target['commit_count'], target['version'], target['daemon_version']):
            raise ValueError('Patch-only rebuild must retain the pinned upstream version')
        target.update(commit=sha, commit_count=count, version=version, daemon_version=describe)
        method = patching.check(repo, HERE / f'{name}-compat.patch',
                                logs / f'{name}-patch-check.log')
        print(f'{name}: compatibility check passed ({method})', flush=True)
    write_json(work / 'targets.json', config)
    return config


def provision(work, config, logs):
    run([sys.executable, PROJECT / 'tools/provision_ddk.py', work], logs / 'ddk.log')
    run([sys.executable, PROJECT / 'tools/provision_android.py', work, '--ndk-only'], logs / 'ndk.log')
    rustup = work / 'rustup-init'
    url = 'https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init'
    with urllib.request.urlopen(url, timeout=180) as response:
        data = response.read()
    with urllib.request.urlopen(url + '.sha256', timeout=60) as response:
        expected = response.read().decode().split()[0]
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError('rustup-init checksum mismatch')
    rustup.write_bytes(data)
    rustup.chmod(0o755)
    env = os.environ.copy()
    env.update(CARGO_HOME=str(work / 'cargo'), RUSTUP_HOME=str(work / 'rustup'))
    env['PATH'] = str(work / 'cargo/bin') + os.pathsep + env['PATH']
    run([rustup, '-y', '--no-modify-path', '--profile', 'minimal', '--default-toolchain', 'none'],
        logs / 'rustup.log', env=env)
    toolchains = {'1.98.1'} | {t['rust'] for t in config['backends'].values() if t['rebuild']}
    for toolchain in sorted(toolchains):
        run([work / 'cargo/bin/rustup', 'toolchain', 'install', toolchain, '--profile', 'minimal',
             '--component', 'clippy,rustfmt', '--target', 'aarch64-linux-android'],
            logs / f'rust-{toolchain}.log', env=env)
    run([work / 'cargo/bin/cargo', '+1.98.1', 'install', 'cargo-ndk', '--version', '4.1.2', '--locked'],
        logs / 'cargo-ndk.log', env=env)


def package_result(source, work):
    result = work / 'result'
    result.mkdir(exist_ok=True)
    # Include added provenance files while preserving deleted superseded patches.
    subprocess.run(['git', 'add', '-N', '--', '.'], cwd=source, check=True)
    subprocess.run(['git', 'diff', '--check', '--', '.', ':(exclude)backends/**/*.patch',
                    ':(exclude)tools/backend-refresh/*-compat.patch'], cwd=source, check=True)
    patch = subprocess.check_output(['git', 'diff', '--no-renames', '--binary', '--full-index'], cwd=source)
    subprocess.run(['git', 'apply', '--check', '-'], input=patch, cwd=PROJECT, check=True)
    (result / 'apply-matched-backends.patch').write_bytes(patch)
    shutil.copytree(source / 'backends', result / 'backends', dirs_exist_ok=True)
    shutil.copytree(work / 'logs', result / 'validation', dirs_exist_ok=True)
    (result / 'README.txt').write_text(
        'Matched native pairs and bundle checks passed. See validation/ for build results.\n'
        'Apply only to the source revision recorded in release.json/targets.json.\n'
        'git apply --check apply-matched-backends.patch\n'
        'git apply apply-matched-backends.patch\n'
        'Keep tools/backend-refresh and backends/ in git for future builds.\n'
        'Use a UAPI 5 manager. Fully reboot after APK installation before activation.\n'
        'These automated checks are not hardware testing or new firmware support.\n')


def prepare_release(source, work, config):
    old = app_version(source)
    version = next_version(old, capture(['git', 'tag', '--list'], source).splitlines())
    path = source / 'app/build.gradle.kts'
    path.write_text(path.read_text().replace(f'val appVersionBase = "{old}"', f'val appVersionBase = "{version}"'))
    changed = [n for n, t in config['backends'].items() if t['rebuild']]
    write_json(work / 'result/release.json', {'schema': 1, 'base_commit': config['app_base'],
                'version': version, 'changed': changed, 'targets': config})
    rows = [f'Weekly backend update — {version}', '',
            'Rebuilt the changed backends from pinned official source commits, with the existing',
            'Samsung compatibility changes. Each daemon embeds its matching driver.', '',
            '| Backend | Driver / UAPI | Upstream commit | Daemon |', '|---|---|---|---|']
    for name, t in config['backends'].items():
        label = {'kernelsu': 'KernelSU', 'kernelsu-next': 'KernelSU-Next', 'resukisu': 'BakaSU'}[name]
        status = 'updated' if name in changed else 'unchanged'
        rows.append(f'| {label} ({status}) | {t["version"]} / 5 | '
                    f'[{t["commit"][:8]}](https://github.com/{t["repository"]}/commit/{t["commit"]}) | '
                    f'{t["daemon_version"]} |')
    rows += ['', 'The release APK uses the repository release key and the existing app package.',
             'Use a UAPI 5 manager. Fully reboot after installation, then activate root to load',
             'the new driver. Updating the manager alone does not load it.', '',
             'Firmware support and exploit/bridge files are unchanged. Build, unit, bundle and',
             'signature checks are automated; this release has not been tested on a phone.', '']
    (work / 'result/release-notes.md').write_text('\n'.join(rows))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', required=True, type=Path)
    parser.add_argument('--snapshot', type=Path)
    parser.add_argument('--discover-only', action='store_true')
    parser.add_argument('--release', action='store_true')
    args = parser.parse_args()
    work = args.work.resolve()
    if work == PROJECT or PROJECT in work.parents:
        raise ValueError('Use a new work directory outside the app checkout')
    if (work / 'source').exists() or (work / 'result').exists():
        raise ValueError('Use a new work directory; source/result already exists')
    snapshot = json.loads(args.snapshot.read_text()) if args.snapshot else probe(PROJECT)
    config = discover(PROJECT, work, snapshot)
    changed = [n for n, t in config['backends'].items() if t['rebuild']]
    if args.discover_only or not changed:
        print('Pinned changes:', ', '.join(changed) or 'none; no build or release needed')
        return
    logs = work / 'logs'
    provision(work, config, logs)
    for name in changed:
        repo = work / name
        method = patching.apply(repo, HERE / f'{name}-compat.patch', logs / f'{name}-patch.log')
        print(f'{name}: compatibility applied ({method})', flush=True)
        run([sys.executable, PROJECT / 'tools/build_backend.py', work, name], logs / f'{name}-build.log')
    source = work / 'source'
    run(['git', 'clone', '--no-hardlinks', PROJECT, source], logs / 'app-clone.log')
    run(['git', 'checkout', '--detach', config['app_base']], logs / 'app-checkout.log', source)
    run([sys.executable, HERE / 'stage.py', source, work], logs / 'stage.log')
    run([sys.executable, 'tools/test_m3q_host.py', 'BundleTests'], logs / 'bundle-tests.log', source)
    run([sys.executable, 'tools/test_backend_bundle.py'], logs / 'backend-tests.log', source)
    run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-I', 'native/azhl/src',
         'native/azhl/tests/policy.c', '-o', work / 'policy-check'], logs / 'policy-build.log', source)
    run([work / 'policy-check'], logs / 'policy-test.log', source)
    if args.release:
        prepare_release(source, work, config)
    package_result(source, work)
    print(f'Validated update: {work / "result"}', flush=True)


if __name__ == '__main__':
    main()
