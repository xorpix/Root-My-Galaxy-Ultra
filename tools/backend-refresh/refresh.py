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

from common import BACKEND_LABELS, HERE, PROJECT, app_version, baseline, capture, load_config, next_version, reviewed_uapi, write_json
from probe import needs_rebuild, probe
import patching

# BakaSU dropped a revert that only lifted its CONFIG_KSU_SUSFS build stop.
# RMGU uses its separate Samsung SusFS module and does not enable that config.
# The runtime source delta was reviewed for 1.2.5. This exception covers only
# this exact pair; any later history rewrite still requires another review.
REVIEWED_HISTORY_TRANSITIONS = {
    # The previous Renovate-only tip was replaced by its spelling correction;
    # 2489b385 then updates libc in Cargo.lock. Kernel/UAPI sources are identical
    # to b8754b20. Reviewed for 1.2.12; retain the exact-pair lineage guard.
    ('resukisu', 'b8754b203edfc03342d0f58adfc582719b31363c',
     '2489b38582a08fee7b586d05185da5e5d12c6a5c'),
    ('resukisu', '6200478dd76fc63093a8226d360f5072c46aea26',
     'd7260ca1e5c128061122924d0d48754e361e135c'),
    # Next rewrote its last two pinned commits. The replacement autoload
    # commit 96bf7ff9 has identical runtime sources; only README text differs.
    # Subsequent changes retain UAPI 5 and merge with our Samsung patch cleanly.
    ('kernelsu-next', '3157e7822190eba628ca6796242db970405e440a',
     '204f007164905395e280e9290cc7774f57af8c61'),
    # Next rebased the four commits after f90d59fb, replacing their object
    # IDs. Reviewed old/new trees: the remaining changes fix bind-mounted
    # init.rc detection and gate the vendored SusFS control-node relabel.
    # UAPI/version formulas are unchanged; version 33405 increases from 33403
    # and the Samsung patch applies directly. Accept only this exact pair.
    ('kernelsu-next', 'd65c9db1b07edcdc3ad531ad2108fc66227e502c',
     'b427a3f9c2421a443f961007bfce66b25486643f'),
}

# The old pin remains the built baseline until a matched pair is staged. Accept
# this reviewed replacement and ordinary descendants of it, even if upstream
# advances before the next scheduled build. Another rewrite still needs review.
REVIEWED_HISTORY_BRIDGES = {
    ('kowsu', '6366f25b89f2bc5e287c1983715ebae60cd5540e'):
        '6ecdd69224fe6769e6c8715a4ef2b91602b23362',
}


def reviewed_history_transition(name, previous, current, repo=None):
    if (name, previous, current) in REVIEWED_HISTORY_TRANSITIONS:
        return True
    anchor = REVIEWED_HISTORY_BRIDGES.get((name, previous))
    if anchor is None:
        return False
    if current == anchor:
        return True
    if repo is None:
        return False
    present = subprocess.run(['git', 'cat-file', '-e', anchor + '^{commit}'],
                             cwd=repo, stderr=subprocess.DEVNULL).returncode == 0
    if not present:
        return False
    ancestor = subprocess.run(['git', 'merge-base', '--is-ancestor', anchor, current],
                              cwd=repo, stderr=subprocess.DEVNULL).returncode
    if ancestor not in (0, 1):
        raise RuntimeError(f'{name}: could not verify reviewed history anchor {anchor}')
    return ancestor == 0


def run(args, log, cwd=None, env=None):
    print('Running:', ' '.join(map(str, args)), flush=True)
    with log.open('w') as output:
        result = subprocess.run(list(map(str, args)), cwd=cwd, env=env,
                                stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text(errors='replace')[-6000:], flush=True)
        raise RuntimeError(f'Command failed ({result.returncode}); see {log}')


def fetch_snapshot_commit(repo, sha, log):
    """Retrieve an exact snapshot pin omitted from the upstream's advertised refs."""
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Expected a full commit SHA in snapshot')
    present = subprocess.run(['git', 'cat-file', '-e', sha + '^{commit}'],
                             cwd=repo, stderr=subprocess.DEVNULL).returncode == 0
    if not present:
        run(['git', 'fetch', '--no-tags', 'origin', sha], log, repo)


def verify_history(repo, name, previous, current, logs):
    """Check lineage before version/patch checks, retrieving only reviewed old pins."""
    reviewed = reviewed_history_transition(name, previous, current, repo)
    present = subprocess.run(['git', 'cat-file', '-e', previous + '^{commit}'],
                             cwd=repo, stderr=subprocess.DEVNULL).returncode == 0
    if not present and reviewed:
        run(['git', 'fetch', '--no-tags', 'origin', previous],
            logs / f'{name}-reviewed-base-fetch.log', repo)
        present = subprocess.run(['git', 'cat-file', '-e', previous + '^{commit}'],
                                 cwd=repo, stderr=subprocess.DEVNULL).returncode == 0
    if not present:
        raise ValueError(
            f'{name}: pinned base {previous} is no longer reachable upstream (history '
            f'rebased); review the replacement lineage and {name}-compat.patch')
    ancestor = subprocess.run(['git', 'merge-base', '--is-ancestor', previous, current],
                              cwd=repo).returncode
    if ancestor == 1 and not reviewed:
        raise ValueError(
            f'{name}: new head {current} is not a descendant of pinned base {previous} '
            f'(upstream rollback or rewritten history); manual review required')
    if ancestor not in (0, 1):
        raise RuntimeError(
            f'{name}: git merge-base failed comparing {previous}..{current} (exit {ancestor})')
    if ancestor == 1:
        print(f'{name}: using reviewed history transition {previous} -> {current}', flush=True)


def restore_pinned_version_tags(repo, expected, log):
    """Reproduce a recorded git-describe name when rebuilding the same source pin."""
    current = capture(['git', 'describe', '--tags', '--always'], repo).removeprefix('v')
    if current == expected:
        return
    match = re.fullmatch(r'(.+)-\d+-g([0-9a-f]{7,40})', expected)
    tag_name = match[1] if match else expected
    tags = capture(['git', 'tag', '--list'], repo).splitlines()
    git = ['git'] + (['-c', f'core.abbrev={len(match[2])}'] if match else [])
    selected = None
    for tag in ('v' + tag_name, tag_name):
        if tag not in tags:
            continue
        described = capture(git + ['describe', '--tags', '--always', '--match', tag], repo)
        if described.removeprefix('v') == expected:
            selected = tag
            break
    if selected is None:
        raise ValueError(f'Cannot reproduce pinned daemon version {expected}; found {current}')
    # Only this disposable source clone is changed. Commit history and source
    # remain intact; later upstream tags must not rename an existing build pin.
    removed = [tag for tag in tags if tag != selected]
    if removed:
        run(['git', 'tag', '-d', *removed], log, repo)
    if match:
        capture(['git', 'config', 'core.abbrev', str(len(match[2]))], repo)
    with log.open('a') as output:
        output.write(f'Pinned version: {expected}; upstream refs reported: {current}; kept tag: {selected}\n')
    if capture(['git', 'describe', '--tags', '--always'], repo).removeprefix('v') != expected:
        raise ValueError('Pinned daemon version could not be restored')


def verify_upstream(repo, name, target):
    if capture(['git', 'rev-parse', '--is-shallow-repository'], repo) != 'false':
        raise ValueError('Complete upstream history is required for version calculation')
    header = (repo / 'uapi/supercall.h').read_text()
    uapi = reviewed_uapi(header, name)
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
    return count, version, describe, uapi


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
        target['rebuild'] = snapshot.get('force_rebuild') is True or needs_rebuild(target, sha, manifests[name])
        if not target['rebuild']:
            continue
        repo = work / name
        if repo.exists():
            raise ValueError('Use a new work directory for source clones')
        run(['git', 'clone', 'https://github.com/' + target['repository'] + '.git', repo],
            logs / f'{name}-clone.log')
        fetch_snapshot_commit(repo, sha, logs / f'{name}-snapshot-fetch.log')
        run(['git', 'checkout', '--detach', sha], logs / f'{name}-checkout.log', repo)
        verify_history(repo, name, target['commit'], sha, logs)
        if not source_changed:
            restore_pinned_version_tags(repo, target['daemon_version'],
                                        logs / f'{name}-pinned-version-tags.log')
        count, version, describe, uapi = verify_upstream(repo, name, target)
        if source_changed and version <= target['version']:
            raise ValueError('Upstream version must increase when the pinned commit changes')
        if not source_changed and (count, version, describe) != (
                target['commit_count'], target['version'], target['daemon_version']):
            raise ValueError('Patch-only rebuild must retain the pinned upstream version')
        target.update(commit=sha, commit_count=count, version=version, daemon_version=describe, uapi=uapi)
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
        'Use a manager matching the UAPI recorded for its backend. Fully reboot after APK installation.\n'
        'These automated checks are not hardware testing or new firmware support.\n')


def render_curated_notes(template, config):
    marker = '<!-- RMGU_BACKEND_TABLE -->'
    begin = '<!-- RMGU_BACKEND_TABLE_BEGIN -->'
    end = '<!-- RMGU_BACKEND_TABLE_END -->'
    table = ['| Backend | Driver / UAPI | Upstream commit | Daemon |', '|---|---|---|---|']
    for name, target in config['backends'].items():
        label = BACKEND_LABELS[name]
        status = 'updated' if target['rebuild'] else 'unchanged'
        table.append(f'| {label} ({status}) | {target["version"]} / {target["uapi"]} | '
                     f'[{target["commit"][:8]}](https://github.com/{target["repository"]}/commit/{target["commit"]}) | '
                     f'{target["daemon_version"]} |')
    # Keep an explicit boundary after the first render: an untagged release
    # can be refreshed again without losing its curated notes or old table.
    rendered = begin + '\n' + '\n'.join(table) + '\n' + end
    if template.count(marker) == 1 and begin not in template and end not in template:
        return template.replace(marker, rendered)
    if template.count(marker) == 0 and template.count(begin) == template.count(end) == 1:
        start, finish = template.index(begin), template.index(end)
        if start < finish:
            return template[:start] + rendered + template[finish + len(end):]
    raise ValueError('Curated backend release notes need one table marker or one managed table block')


def prepare_release(source, work, config):
    old = app_version(source)
    version = next_version(old, capture(['git', 'tag', '--list'], source).splitlines())
    path = source / 'app/build.gradle.kts'
    path.write_text(path.read_text().replace(f'val appVersionBase = "{old}"', f'val appVersionBase = "{version}"'))
    changed = [n for n, t in config['backends'].items() if t['rebuild']]
    write_json(work / 'result/release.json', {'schema': 1, 'base_commit': config['app_base'],
                'version': version, 'changed': changed, 'targets': config})
    rows = ["## What's new", '', '### Refreshed backends', '',
            'Rebuilt the changed backends from pinned official source commits, with the existing',
            'Samsung compatibility changes. Each daemon embeds its matching driver. Use the manager UAPI shown below.', '',
            '| Backend | Driver / UAPI | Upstream commit | Daemon |', '|---|---|---|---|']
    for name, t in config['backends'].items():
        label = BACKEND_LABELS[name]
        status = 'updated' if name in changed else 'unchanged'
        rows.append(f'| {label} ({status}) | {t["version"]} / {t["uapi"]} | '
                    f'[{t["commit"][:8]}](https://github.com/{t["repository"]}/commit/{t["commit"]}) | '
                    f'{t["daemon_version"]} |')
    rows += ['', '## Validation and current limits', '',
             '- Backend builds, Android unit tests, driver/daemon matching, APK bundle integrity and release-signature checks passed.',
             '- Firmware support and exploit/bridge files are unchanged. The refreshed backend revisions still need device testing.',
             '- SusFS keeps its existing configuration. Installed-app/package-list visibility is a separate layer.', '',
             '## Updating', '',
             f'1. Install **RootMyGalaxyUltra-{version}.apk** over the existing app; it uses the same package and release signing key.',
             '2. Perform a **full reboot** to replace the loaded backend and SusFS module. A soft reboot keeps the existing kernel modules loaded.',
             '3. Activate root again through Root My Galaxy Ultra and enable SusFS in the updated app.', '',
             'Use a manager matching the UAPI shown for your selected backend. Updating the manager alone does not replace the loaded driver.', '']
    notes = '\n'.join(rows)
    curated = source / 'docs/release-notes' / f'{version}.md'
    if curated.is_file():
        notes = render_curated_notes(curated.read_text(), config)
        curated.write_text(notes)
    (work / 'result/release-notes.md').write_text(notes)


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
