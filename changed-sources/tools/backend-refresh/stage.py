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
CONFIG = json.loads((HERE / 'targets.json').read_text())
JAVA = Path('app/src/main/java/dev/busung/s25uroot')
ASSETS = Path('app/src/main/assets')
JNI = Path('app/src/main/jniLibs/arm64-v8a')
BRIDGE = Path('app/src/main/cpp/dirtyfrag/ko/dirtyfrag-android16-6.12-resukisu.ko')
BRIDGE_SHA = '37ef61dfb00ea77b350820c86779c72d0c078499d420e555bd656e8a570c9c0e'
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
    patch = git(repo, 'diff', '--binary', '--full-index', '--', *scope)
    changed = git(repo, 'diff', '--name-only', '--', *scope).decode().splitlines()
    hashes = {name: record((repo / name).read_bytes())['sha256'] for name in changed}
    if b'diff --git a/uapi/' in patch:
        raise ValueError('Unexpected UAPI override')
    return patch, hashes


def bridge_with_baka_package(data):
    if record(data)['sha256'] != BRIDGE_SHA:
        raise ValueError('Unrecognized DirtyFrag manager bridge')
    check_elf(data, 1)
    old, new = b'com.resukisu.resukisu', b'org.bakasu.bakasu'
    if data.count(old) != 1:
        raise ValueError('Expected exactly one manager package in the bridge')
    offset = data.index(old)
    shoff = struct.unpack_from('<Q', data, 40)[0]
    size, count = struct.unpack_from('<HH', data, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * size)
                for i in range(count)]
    if not any(s[1] == 1 and not s[2] & 4 and
               s[4] <= offset and offset + len(old) <= s[4] + s[5] for s in sections):
        raise ValueError('Manager package is not in a non-executable data section')
    # The package is part of a shell command: spaces preserve the command suffix.
    result = data[:offset] + new.ljust(len(old), b' ') + data[offset + len(old):]
    command = result[result.rfind(b'\0', 0, offset) + 1:result.index(b'\0', offset)]
    # This checks shell grammar only; it does not execute the command.
    subprocess.run(['/bin/sh', '-n'], input=command, check=True)
    return result


def apply_branding(project):
    flavor = project / JAVA / 'KernelSuFlavor.kt'
    replace(flavor, 'label = "ReSukiSU"', 'label = "BakaSU"')
    replace(flavor, 'managerPackage = "com.resukisu.resukisu"',
            'managerPackage = "org.bakasu.bakasu"')
    replace(flavor, 'repository = "ReSukiSU/ReSukiSU"', 'repository = "Baka-SU/BakaSU"')
    text = flavor.read_text()
    start = text.index('        // A pre-release,', text.index('    ReSukiSU('))
    end = text.index('        supportsDynamicManager', start)
    text = text[:start] + (
        '        // Keep the internal id for saved preferences and existing assets.\n'
        '        // The old rc3 release APK uses UAPI 4. Use an official UAPI 5 build.\n'
        '        defaultManagerVersion = "4.2.0-rc3",\n'
        '        defaultManagerAsset = "",\n') + text[end:]
    flavor.write_text(text)
    replace(flavor, 'url = releaseAssetUrl(defaultManagerVersion, defaultManagerAsset),',
            'url = if (defaultManagerAsset.isEmpty()) "https://github.com/$repository/actions"\n'
            '                else releaseAssetUrl(defaultManagerVersion, defaultManagerAsset),')
    manager = project / JAVA / 'KernelSuManager.kt'
    replace(manager, '    if (published != null) return ManagerIdentity(published, spoofed = false)',
            '    if (published != null) return ManagerIdentity(published, spoofed = false)\n'
            '    if (packageName.trim().equals("com.resukisu.resukisu", ignoreCase = true)) {\n'
            '        return ManagerIdentity(KernelSuFlavor.ReSukiSU, spoofed = false)\n'
            '    }')
    replace(manager, '"resukisu" in words || "re suki su" in words ->',
            '"bakasu" in words || "baka su" in words || "resukisu" in words || "re suki su" in words ->')
    replace(project / 'app/src/main/AndroidManifest.xml',
            '        <package android:name="com.resukisu.resukisu" />',
            '        <package android:name="com.resukisu.resukisu" />\n'
            '        <package android:name="org.bakasu.bakasu" />')
    for relative in ('native/azhl/src/azhl_backend.h', 'build-df-variants.sh',
                     'app/src/main/cpp/dirtyfrag/DF_ORIGIN.md'):
        path = project / relative
        text = path.read_text()
        if 'com.resukisu.resukisu' not in text:
            raise ValueError(f'Manager package missing from {relative}')
        path.write_text(text.replace('com.resukisu.resukisu', 'org.bakasu.bakasu'))
    strings = project / 'app/src/main/res/values/strings.xml'
    strings.write_text(strings.read_text().replace('ReSukiSU', 'BakaSU'))
    tests = project / 'app/src/test/java/dev/busung/s25uroot'
    flavor_test = tests / 'KernelSuFlavorTest.kt'
    replace(flavor_test, 'assertEquals("com.resukisu.resukisu", KernelSuFlavor.ReSukiSU.managerPackage)',
            'assertEquals("org.bakasu.bakasu", KernelSuFlavor.ReSukiSU.managerPackage)')
    replace(flavor_test, 'assertEquals("ReSukiSU/ReSukiSU", KernelSuFlavor.ReSukiSU.repository)',
            'assertEquals("Baka-SU/BakaSU", KernelSuFlavor.ReSukiSU.repository)')
    replace(flavor_test,
            '"https://github.com/ReSukiSU/ReSukiSU/releases/download/v4.2.0-rc3/" +\n'
            '                "ReSukiSU_v4.2.0-rc3_35171-universal-release.apk"',
            '"https://github.com/Baka-SU/BakaSU/actions"')
    replace(flavor_test, '        for (flavor in KernelSuFlavor.entries) {\n            assertTrue(',
            '        for (flavor in KernelSuFlavor.entries) {\n'
            '            if (flavor.defaultManagerAsset.isEmpty()) {\n'
            '                assertEquals(KernelSuFlavor.ReSukiSU, flavor)\n'
            '                assertEquals("https://github.com/Baka-SU/BakaSU/actions", flavor.defaultManagerRelease.url)\n'
            '                continue\n'
            '            }\n            assertTrue(')
    replace(tests / 'KernelSuManagerTest.kt', '        assertFalse(next.spoofed)',
            '        assertFalse(next.spoofed)\n\n'
            '        val baka = identifyManager("org.bakasu.bakasu", "BakaSU")\n'
            '        assertEquals(KernelSuFlavor.ReSukiSU, baka.flavor)\n'
            '        assertFalse(baka.spoofed)\n'
            '        assertFalse(identifyManager("com.resukisu.resukisu", "ReSukiSU").spoofed)\n'
            '        assertEquals(KernelSuFlavor.ReSukiSU, identifyManager("custom.manager", "BakaSU").flavor)')
    bridge = project / BRIDGE
    bridge.write_bytes(bridge_with_baka_package(bridge.read_bytes()))
    origin = project / 'app/src/main/cpp/dirtyfrag/DF_ORIGIN.md'
    with origin.open('a') as out:
        out.write('\n## BakaSU manager migration (2026-10-05)\n\n'
                  'The resukisu bridge keeps its filename and size. Only the manager-package\n'
                  'literal in its non-executable data section changes to `org.bakasu.bakasu`,\n'
                  'with space padding. The command suffix and executable sections are unchanged.\n'
                  'The reproducible, input-hash-checked edit is in\n'
                  '`tools/backend-refresh/stage.py`. This is not a new exploit build.\n')


def main(project, work):
    if git(project, 'status', '--porcelain').strip():
        raise ValueError('Staging requires a clean, disposable source checkout')
    subprocess.run(['git', '-C', str(project), 'diff', '--exit-code', CONFIG['app_base'],
                    '--', 'app/src', 'native/azhl', 'tools/prepare_m3q_helpers.py'], check=True)
    previous = {name: json.loads((HERE / f'{name}-previous.json').read_text())
                for name in CONFIG['backends']}
    inputs = {}
    # Check every input before replacing anything in the app.
    for name, target in CONFIG['backends'].items():
        patch = (HERE / f'{name}-compat.patch').read_bytes()
        if record(patch)['sha256'] != target['compat_sha256']:
            raise ValueError(f'Compatibility patch checksum mismatch: {name}')
        if not target['rebuild']:
            daemon = (project / ASSETS / 'azhl' / name / 'ksud').read_bytes()
            if record(daemon) != previous[name]['outputs']['ksud']:
                raise ValueError('Unchanged Next daemon differs from the previous bundle')
            module = extract_driver(daemon, previous[name]['outputs']['kernelsu.ko'])
            verify_pair(daemon, module, target)
            inputs[name] = daemon, patch, previous[name]['patched_source_sha256'], record(module)
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
                       'uapi': CONFIG['uapi'], 'ksud': record(daemon), 'kernelsu.ko': record(module)}:
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
            profile['displayName'] = profile['displayName'].replace('ReSukiSU', 'BakaSU')
        write_json(path, catalog)
    for relative in ('tools/prepare_m3q_helpers.py', JAVA / 'AzhlCatalog.kt',
                     'native/azhl/src/azhl_backend.h'):
        path = project / relative
        text = path.read_text()
        for name, target in CONFIG['backends'].items():
            text = text.replace(str(previous[name]['driver_version']), str(target['version']))
        path.write_text(text)
    subprocess.run([sys.executable, project / 'tools/prepare_m3q_helpers.py'], check=True)
    helpers = json.loads((project / ASSETS / 'm3q/helper-variants.json').read_text())['variants']
    launch = project / JAVA / 'M3qLaunch.kt'
    for name, target in CONFIG['backends'].items():
        old = previous[name]
        replace(launch,
                f'"{name}" -> Backend(id, {old["driver_version"]}, "{old["outputs"]["helper"]["sha256"]}")',
                f'"{name}" -> Backend(id, {target["version"]}, "{helpers[name]["sha256"]}")')
    apply_branding(project)

    for name, target in CONFIG['backends'].items():
        daemon, patch, hashes, module_record = inputs[name]
        folder = project / 'backends' / name
        folder.mkdir(parents=True, exist_ok=True)
        patch_name = f'samsung-compat-{target["version"]}.patch'
        (folder / patch_name).write_bytes(patch)
        manifest = previous[name]
        if target['rebuild']:
            manifest = {key: manifest[key] for key in ('schema', 'toolchains', 'kernel_config', 'companion_patch')}
            manifest.update(created_utc=CONFIG['checked_date'], app_base_commit=CONFIG['app_base'],
                            upstream_repository='https://github.com/' + target['repository'],
                            upstream_commit=target['commit'], upstream_commit_count=target['commit_count'],
                            driver_version=target['version'], uapi_version=CONFIG['uapi'],
                            manager_required_uapi=CONFIG['uapi'], daemon_version=target['daemon_version'],
                            validation={'kernel_build': 'passed', 'daemon_build': 'passed',
                                        'exact_driver_in_daemon': 'passed',
                                        'apk_build': 'not run', 'device_test': 'not run'},
                            source_provenance='Existing Samsung compatibility changes rebased onto pinned upstream.')
        manifest['outputs'] = {'ksud': record(daemon), 'kernelsu.ko': module_record,
                               'helper': record((project / JNI / helpers[name]['library']).read_bytes())}
        manifest['rebased_patch'] = dict(path=patch_name, **record(patch))
        manifest['patched_source_sha256'] = hashes
        write_json(folder / 'build-manifest.json', manifest)
    readme = project / 'README.md'
    text = readme.read_text().replace('ReSukiSU', 'BakaSU')
    text = text.replace('https://github.com/BakaSU/BakaSU/releases', 'https://github.com/Baka-SU/BakaSU/actions')
    for name, target in CONFIG['backends'].items():
        text = text.replace(f'`{previous[name]["driver_version"]} / 5`', f'`{target["version"]} / 5`')
    readme.write_text(text)
    (project / 'backends/README.md').write_text((HERE / 'BACKENDS.md').read_text())
    # Firmware profiles, routing and the actual exploit code must stay identical.
    for family in ('azhl', 'df'):
        relative = ASSETS / family / 'catalog.json'
        before = json.loads(git(project, 'show', f'{CONFIG["app_base"]}:{relative}'))
        after = json.loads((project / relative).read_text())
        for old, new in zip(before['payloads'], after['payloads'], strict=True):
            for key in ('kernelsu', 'displayName'):
                old.pop(key)
                new.pop(key)
        if before != after:
            raise ValueError(f'Unexpected firmware/routing change: {family}')
    allowed = {str(BRIDGE), 'app/src/main/cpp/dirtyfrag/DF_ORIGIN.md'}
    changed = set(git(project, 'diff', '--name-only', CONFIG['app_base'], '--',
                      'app/src/main/cpp/dirtyfrag', 'app/src/main/assets/m3q/libm3qpayload.so',
                      'app/src/main/assets/m3q/libm3qroot.so',
                      'app/src/main/jniLibs/arm64-v8a/libm3qpayload.so').decode().splitlines())
    if changed - allowed:
        raise ValueError(f'Unexpected payload edits: {changed - allowed}')
    print('Matching native pairs staged; firmware and exploit code preserved.')


if __name__ == '__main__':
    main(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
