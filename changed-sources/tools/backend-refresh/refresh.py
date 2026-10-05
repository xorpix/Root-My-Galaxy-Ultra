#!/usr/bin/env python3
"""Build a complete update in a separate checkout; never replace live assets early."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
CONFIG = json.loads((HERE / 'targets.json').read_text())


def capture(args, cwd=None):
    return subprocess.check_output(list(map(str, args)), cwd=cwd, text=True).strip()


def run(args, log, cwd=None, env=None):
    print('Running:', ' '.join(map(str, args)), flush=True)
    with log.open('w') as output:
        result = subprocess.run(list(map(str, args)), cwd=cwd, env=env,
                                stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text(errors='replace')[-6000:], flush=True)
        raise RuntimeError(f'Command failed ({result.returncode}); see {log}')


def verify_inputs(project):
    # A previous partial 35207 patch must not be silently combined with this kit.
    if capture(['git', 'status', '--porcelain'], project):
        raise RuntimeError('Commit the build-kit changes first; a clean source checkout is required.')
    subprocess.run(['git', 'diff', '--exit-code', CONFIG['app_base'], '--',
                    'app/src', 'native/azhl', 'tools/prepare_m3q_helpers.py'],
                   cwd=project, check=True, stdout=subprocess.DEVNULL)
    for name, target in CONFIG['backends'].items():
        patch = (HERE / f'{name}-compat.patch').read_bytes()
        if hashlib.sha256(patch).hexdigest() != target['compat_sha256']:
            raise ValueError(f'Compatibility patch checksum mismatch: {name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', required=True, type=Path)
    args = parser.parse_args()
    work = args.work.resolve()
    if work == PROJECT or PROJECT in work.parents:
        raise ValueError('Use a work directory outside the app checkout.')
    verify_inputs(PROJECT)
    work.mkdir(parents=True, exist_ok=True)
    logs = work / 'logs'
    logs.mkdir(exist_ok=True)
    if (work / 'source').exists() or (work / 'result').exists():
        raise ValueError('Use a new work directory; source/result already exists.')
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
    for toolchain in ('1.98.1', 'nightly-2026-09-25'):
        run([work / 'cargo/bin/rustup', 'toolchain', 'install', toolchain, '--profile', 'minimal',
             '--component', 'clippy,rustfmt', '--target', 'aarch64-linux-android'],
            logs / f'rust-{toolchain}.log', env=env)
    run([work / 'cargo/bin/cargo', '+1.98.1', 'install', 'cargo-ndk', '--version', '4.1.2', '--locked'],
        logs / 'cargo-ndk.log', env=env)

    for name, target in CONFIG['backends'].items():
        if not target['rebuild']:
            continue
        repo = work / name
        if repo.exists():
            raise ValueError(f'Use a new work directory; {repo} already exists.')
        run(['git', 'clone', 'https://github.com/' + target['repository'] + '.git', repo],
            logs / f'{name}-clone.log')
        run(['git', 'checkout', '--detach', target['commit']], logs / f'{name}-checkout.log', repo)
        if int(capture(['git', 'rev-list', '--count', 'HEAD'], repo)) != target['commit_count']:
            raise ValueError(f'Unexpected commit count: {name}')
        patch = HERE / f'{name}-compat.patch'
        run(['git', 'apply', '--check', patch], logs / f'{name}-patch-check.log', repo)
        run(['git', 'apply', patch], logs / f'{name}-patch.log', repo)
        run([sys.executable, PROJECT / 'tools/build_backend.py', work, name],
            logs / f'{name}-build.log')

    # Only after BOTH required native builds succeed do we create the new app bundle.
    source = work / 'source'
    run(['git', 'clone', '--no-hardlinks', PROJECT, source], logs / 'app-clone.log')
    run(['git', 'checkout', '--detach', capture(['git', 'rev-parse', 'HEAD'], PROJECT)],
        logs / 'app-checkout.log', source)
    run([sys.executable, HERE / 'stage.py', source, work], logs / 'stage.log')
    run([sys.executable, 'tools/test_m3q_host.py', 'BundleTests'], logs / 'bundle-tests.log', source)
    run([sys.executable, 'tools/test_backend_bundle.py'], logs / 'backend-tests.log', source)
    run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-I', 'native/azhl/src',
         'native/azhl/tests/policy.c', '-o', work / 'policy-check'], logs / 'policy-build.log', source)
    run([work / 'policy-check'], logs / 'policy-test.log', source)
    run(['git', 'add', '-N', 'backends'], logs / 'index-new-files.log', source)
    # Embedded upstream patches contain intentional context-line whitespace.
    run(['git', 'diff', '--check', '--', '.', ':(exclude)backends/**/*.patch'],
        logs / 'diff-check.log', source)
    patch = subprocess.check_output(['git', 'diff', '--binary', '--full-index'], cwd=source)
    # Validate the actual deliverable against the build-kit checkout.
    subprocess.run(['git', 'apply', '--check', '-'], input=patch, cwd=PROJECT, check=True)
    result = work / 'result'
    result.mkdir()
    (result / 'apply-matched-backends.patch').write_bytes(patch)
    shutil.copytree(source / 'backends', result / 'backends')
    shutil.copytree(logs, result / 'validation')
    (result / 'README.txt').write_text(
        'Native builds and bundle checks passed. No phone test or APK build was performed.\n'
        'Apply to the build-kit source checkout used for this workflow:\n'
        'git apply --check apply-matched-backends.patch\n'
        'git apply apply-matched-backends.patch\n'
        'Then build your APK normally. Use a UAPI 5 manager, fully reboot and activate.\n'
        'KernelSU-Next binaries are unchanged. SM-S948W/BZID is not enabled.\n')
    print(f'Validated update: {result}', flush=True)


if __name__ == '__main__':
    main()
