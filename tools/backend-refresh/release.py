#!/usr/bin/env python3
"""Validate APK contents, sign in a fresh job, then publish the exact source commit."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

from common import ASSETS, HERE, JAVA, JNI, PROJECT, app_version, baseline, capture, load_config, record, write_json
from refresh import package_result


def metadata(result):
    info = json.loads((result / 'release.json').read_text())
    if info.get('schema') != 1 or not re.fullmatch(r'\d+\.\d+\.\d+', info['version']):
        raise ValueError('Invalid release version/schema')
    if not re.fullmatch(r'[0-9a-f]{40}', info['base_commit']):
        raise ValueError('Invalid release source revision')
    return info


def android_tool(name):
    root = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not root:
        raise ValueError('Android SDK location is not set')
    tool = Path(root) / 'build-tools/36.0.0' / name
    if not tool.is_file():
        raise ValueError(f'Missing Android Build Tools 36.0.0: {name}')
    return tool


def verify_apk(apk, project, info):
    if not apk.is_file():
        raise ValueError('APK output missing')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK ZIP entries')
        files = list((project / ASSETS / 'azhl').glob('*/ksud'))
        files += [project / ASSETS / f'{f}/catalog.json' for f in ('azhl', 'df')]
        files += list((project / ASSETS / 'm3q').glob('*.so'))
        files += list((project / JNI).glob('libm3q*.so'))
        for path in files:
            if ASSETS.as_posix() in path.as_posix():
                entry = 'assets/' + path.relative_to(project / ASSETS).as_posix()
            else:
                entry = 'lib/arm64-v8a/' + path.name
            if archive.read(entry) != path.read_bytes():
                raise ValueError(f'APK does not contain the validated bundle: {entry}')
    output = capture([android_tool('aapt2'), 'dump', 'badging', apk])
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not match or (match[1], match[3]) != ('dev.experimental.azhlroot', info['version']):
        raise ValueError('APK package/version does not match this release')
    return int(match[2])


def finish_build(work):
    source, result = work / 'source', work / 'result'
    info = metadata(result)
    apks = list((source / 'app/build/outputs/apk/release').glob('*.apk'))
    if len(apks) != 1:
        raise ValueError('Expected exactly one release APK')
    version_code = verify_apk(apks[0], source, info)
    for name in info['targets']['backends']:
        path = source / 'backends' / name / 'build-manifest.json'
        manifest = json.loads(path.read_text())
        manifest['validation'].update(apk_build='passed (GitHub Actions)',
                                      android_unit_tests='passed (GitHub Actions)', device_test='not run')
        manifest['app_version'] = info['version']
        write_json(path, manifest)
    package_result(source, work)
    shutil.copy2(apks[0], result / 'apk-input.apk')
    info.update(apk_input=record(apks[0].read_bytes()), apk_version_code=version_code)
    write_json(result / 'release.json', info)


def apply_checked_update(result):
    """Run only trusted base scripts. Restrict the artifact patch to expected data/version edits."""
    info = metadata(result)
    if capture(['git', 'rev-parse', 'HEAD'], PROJECT) != info['base_commit']:
        raise ValueError('Artifact was built from a different source revision')
    old_config = load_config()
    old = baseline(PROJECT, old_config)
    fixed = [Path('README.md'), Path('app/build.gradle.kts'), Path('tools/prepare_m3q_helpers.py'),
             JAVA / 'AzhlCatalog.kt', JAVA / 'M3qLaunch.kt', Path('native/azhl/src/azhl_backend.h'),
             ASSETS / 'azhl/catalog.json', ASSETS / 'df/catalog.json',
             ASSETS / 'm3q/helper-variants.json', Path('tools/backend-refresh/targets.json'),
             Path('backends/README.md')]
    allowed = set(map(str, fixed))
    for name in old:
        allowed.update([str(ASSETS / 'azhl' / name / 'ksud'),
                        str(JNI / ('libm3qksud_' + name.replace('-', '_') + '.so')),
                        str(JNI / ('libm3qroot_' + name.replace('-', '_') + '.so')),
                        f'tools/backend-refresh/{name}-compat.patch',
                        f'backends/{name}/build-manifest.json',
                        f'backends/{name}/' + old[name]['rebased_patch']['path'],
                        f'backends/{name}/samsung-compat-{info["targets"]["backends"][name]["version"]}.patch'])
    patch = (result / 'apply-matched-backends.patch').read_bytes()
    changed = subprocess.check_output(['git', 'apply', '--numstat', '-'], input=patch, cwd=PROJECT).decode()
    paths = {line.split('\t', 2)[2] for line in changed.splitlines()}
    if not paths <= allowed:
        raise ValueError(f'Artifact patch changes unexpected files: {paths - allowed}')
    originals = {p: (PROJECT / p).read_text() for p in fixed if p.suffix in ('.kt', '.h', '.py', '.kts')}
    # Load the known helper generator BEFORE applying the artifact patch.
    spec = importlib.util.spec_from_file_location('trusted_helpers', PROJECT / 'tools/prepare_m3q_helpers.py')
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    subprocess.run(['git', 'apply', '--check', '-'], input=patch, cwd=PROJECT, check=True)
    subprocess.run(['git', 'apply', '-'], input=patch, cwd=PROJECT, check=True)
    config = load_config()
    targets = info['targets']['backends']
    for name, target in targets.items():
        for key in ('repository', 'branch', 'commit', 'commit_count', 'version', 'rust', 'daemon_version'):
            if config['backends'][name][key] != target[key]:
                raise ValueError('Release metadata differs from the staged targets')
    for path, text in originals.items():
        if path.name == 'build.gradle.kts':
            text = text.replace(f'val appVersionBase = "{app_version_from_text(text)}"',
                                f'val appVersionBase = "{info["version"]}"')
        elif path.name == 'M3qLaunch.kt':
            variants = json.loads((PROJECT / ASSETS / 'm3q/helper-variants.json').read_text())['variants']
            for name, target in targets.items():
                before = f'"{name}" -> Backend(id, {old[name]["driver_version"]}, "{old[name]["outputs"]["helper"]["sha256"]}")'
                after = f'"{name}" -> Backend(id, {target["version"]}, "{variants[name]["sha256"]}")'
                if text.count(before) != 1:
                    raise ValueError('Unexpected base helper declaration')
                text = text.replace(before, after)
        else:
            for name, target in targets.items():
                text = re.sub(r'\b' + str(old[name]['driver_version']) + r'\b', str(target['version']), text)
        if (PROJECT / path).read_text() != text:
            raise ValueError(f'Unexpected control/build logic change: {path}')
    original = (PROJECT / ASSETS / 'm3q/libm3qroot.so').read_bytes()
    helpers.VERSIONS = {n: t['version'] for n, t in targets.items()}
    for name in targets:
        generated, _ = helpers.variant(original, name)
        if (PROJECT / JNI / helpers.LIBRARIES[name]).read_bytes() != generated:
            raise ValueError('Helper artifact differs from the trusted version-only generation')
    for family in ('azhl', 'df'):
        path = ASSETS / family / 'catalog.json'
        before = json.loads(capture(['git', 'show', f'HEAD:{path}'], PROJECT))
        after = json.loads((PROJECT / path).read_text())
        for a, b in zip(before['payloads'], after['payloads'], strict=True):
            for item in (a, b):
                for key in ('size', 'sha256'):
                    item['kernelsu'].pop(key)
        if before != after:
            raise ValueError('Artifact modifies firmware/routing or catalog identity')
    baseline(PROJECT, clean=False)
    subprocess.run(['python3', 'tools/test_backend_bundle.py'], cwd=PROJECT, check=True)
    if record((result / 'apk-input.apk').read_bytes()) != info['apk_input']:
        raise ValueError('APK artifact hash mismatch')
    if verify_apk(result / 'apk-input.apk', PROJECT, info) != info['apk_version_code']:
        raise ValueError('APK version code differs from the build receipt')
    return info


def app_version_from_text(text):
    return re.search(r'^val appVersionBase = "(\d+\.\d+\.\d+)"$', text, re.M)[1]


def sign(result):
    info = apply_checked_update(result)
    required = ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')
    for name in required:
        if not os.environ.get(name):
            raise ValueError(f'Required repository secret is not configured: {name}')
    output = result / f'RootMyGalaxyUltra-{info["version"]}.apk'
    with tempfile.TemporaryDirectory(dir=os.environ.get('RUNNER_TEMP')) as directory:
        key = Path(directory) / 'release.p12'
        encoded = ''.join(os.environ['KEYSTORE_BASE64'].split())
        key.write_bytes(base64.b64decode(encoded, validate=True))
        key.chmod(0o600)
        cert = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(key),
                '-storepass:env', 'KEYSTORE_PASSWORD', '-alias', os.environ['KEY_ALIAS']])
        expected = hashlib.sha256(cert).hexdigest()
        subprocess.run([android_tool('apksigner'), 'sign', '--ks', key,
                '--ks-pass', 'env:KEYSTORE_PASSWORD', '--key-pass', 'env:KEY_PASSWORD',
                '--ks-key-alias', os.environ['KEY_ALIAS'], '--out', output, result / 'apk-input.apk'], check=True)
        certificate = capture([android_tool('apksigner'), 'verify', '--verbose', '--print-certs', output])
        digests = re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', certificate)
        if digests != [expected]:
            raise ValueError('Published APK is not signed with the repository release key')
    verify_apk(output, PROJECT, info)
    write_json(result / 'signed-apk.json', {'file': output.name, **record(output.read_bytes()),
                                          'certificate_sha256': expected})
    # Keep only the distributable APK, not the throwaway-key input.
    (result / 'apk-input.apk').unlink()
    for sidecar in result.glob('*.idsig'):
        sidecar.unlink()
    (result / 'SHA256SUMS').write_text(record(output.read_bytes())['sha256'] + '  ' + output.name + '\n')
    print('Signed APK verified:', output.name)


def publish(result, branch):
    info = metadata(result)
    signed = json.loads((result / 'signed-apk.json').read_text())
    if signed['file'] != f'RootMyGalaxyUltra-{info["version"]}.apk':
        raise ValueError('Unexpected signed APK filename')
    apk = result / signed['file']
    if record(apk.read_bytes()) != {k: signed[k] for k in ('size', 'sha256')}:
        raise ValueError('Signed APK hash mismatch')
    if not re.fullmatch(r'[0-9A-Za-z_./-]+', branch) or branch.startswith('-'):
        raise ValueError('Invalid release branch')
    remote = capture(['git', 'ls-remote', 'origin', 'refs/heads/' + branch], PROJECT).split()
    if not remote or remote[0] != info['base_commit']:
        raise ValueError('The default branch moved during this build. Rerun; no branch/tag was pushed.')
    tag = info['version']
    if capture(['git', 'tag', '--list', tag], PROJECT):
        raise ValueError('Release tag already exists; never move or overwrite it')
    capture(['git', 'config', 'user.name', 'github-actions[bot]'], PROJECT)
    capture(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], PROJECT)
    subprocess.run(['git', 'add', '--all'], cwd=PROJECT, check=True)
    subprocess.run(['git', 'commit', '-m', f'build: Refresh backends for {tag}'], cwd=PROJECT, check=True)
    sha = capture(['git', 'rev-parse', 'HEAD'], PROJECT)
    subprocess.run(['git', 'tag', '-a', tag, '-m', f'Weekly backend release {tag}'], cwd=PROJECT, check=True)
    # GitHub either accepts BOTH the main update and tag, or neither. No force pushes.
    subprocess.run(['git', 'push', '--atomic', 'origin', 'HEAD:refs/heads/' + branch, 'refs/tags/' + tag],
                   cwd=PROJECT, check=True)
    assets = [apk, result / 'SHA256SUMS', result / 'apply-matched-backends.patch']
    subprocess.run(['gh', 'release', 'create', tag, *map(str, assets), '--verify-tag', '--draft',
                    '--title', 'Root My Galaxy Ultra ' + tag, '--notes-file', result / 'release-notes.md'],
                   cwd=PROJECT, check=True)
    subprocess.run(['gh', 'release', 'edit', tag, '--draft=false', '--latest'], cwd=PROJECT, check=True)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as stream:
            stream.write(f'Published **{tag}** from `{sha}`. APK SHA-256: `{signed["sha256"]}`.\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('finish-build', 'sign', 'publish'))
    parser.add_argument('--work', type=Path)
    parser.add_argument('--result', type=Path)
    parser.add_argument('--branch')
    args = parser.parse_args()
    if args.command == 'finish-build':
        finish_build(args.work.resolve())
    elif args.command == 'sign':
        sign(args.result.resolve())
    else:
        publish(args.result.resolve(), args.branch)


if __name__ == '__main__':
    main()
