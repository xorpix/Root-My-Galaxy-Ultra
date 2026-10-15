#!/usr/bin/env python3
"""Check the actual embedded, signed D2 APK, including its binary Android manifest.

Used by CI, stable releases and the weekly backend release path. No phone or vault access.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = 'dev.experimental.azhlroot'
HELPER = CONTROLLER + '.d2helper'
PERMISSION = CONTROLLER + '.permission.CONTROL_D2_HELPER'
NAMESPACE = 'dev.experimental.rmgu.d2helper'
ANDROID = 'http://schemas.android.com/apk/res/android'


def manifest(data):
    """Decode only the string/element chunks needed from standard Android binary XML."""
    if len(data) < 8 or struct.unpack_from('<HHI', data) != (3, 8, len(data)):
        raise ValueError('Invalid Android binary XML')
    strings, stack, root, offset = [], [], None, 8
    while offset < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, offset)
        if header < 8 or size < header or offset + size > len(data):
            raise ValueError('Invalid XML chunk size')
        if kind == 1:
            count, _, flags, start, _ = struct.unpack_from('<IIIII', data, offset + 8)
            for i in range(count):
                at = offset + start + struct.unpack_from('<I', data, offset + header + 4 * i)[0]
                def length(pos, wide=False):
                    value = struct.unpack_from('<H' if wide else '<B', data, pos)[0]
                    step, mask = (2, 0x8000) if wide else (1, 0x80)
                    if value & mask:
                        other = struct.unpack_from('<H' if wide else '<B', data, pos + step)[0]
                        return ((value & (mask - 1)) << (16 if wide else 8)) | other, pos + 2 * step
                    return value, pos + step
                if flags & 0x100:
                    _, at = length(at)
                    amount, at = length(at)
                    strings.append(data[at:at + amount].decode('utf-8'))
                else:
                    amount, at = length(at, True)
                    strings.append(data[at:at + amount * 2].decode('utf-16le'))
        elif kind == 0x102:
            ns, name, attr_start, attr_size, count = struct.unpack_from('<IIHHH', data, offset + 16)
            node = {'name': strings[name], 'attributes': {}, 'children': []}
            if attr_size != 20:
                raise ValueError('Unexpected XML attribute size')
            for i in range(count):
                at = offset + 16 + attr_start + i * attr_size
                namespace, key, raw, _, _, typed, value = struct.unpack_from('<IIIHBBI', data, at)
                label = (strings[namespace] if namespace != 0xffffffff else '', strings[key])
                if label in node['attributes']:
                    raise ValueError('Duplicate manifest attribute')
                if raw != 0xffffffff:
                    decoded = strings[raw]
                elif typed == 3:
                    decoded = strings[value]
                elif typed == 0x12:
                    decoded = bool(value)
                else:
                    decoded = value
                node['attributes'][label] = decoded
            if stack:
                stack[-1]['children'].append(node)
            elif root is None:
                root = node
            else:
                raise ValueError('Multiple manifest roots')
            stack.append(node)
        elif kind == 0x103:
            name = struct.unpack_from('<I', data, offset + 20)[0]
            if not stack or stack.pop()['name'] != strings[name]:
                raise ValueError('Invalid manifest nesting')
        offset += size
    if stack or root is None or root['name'] != 'manifest':
        raise ValueError('Incomplete Android manifest')
    return root


def attribute(node, name, default=None):
    return node['attributes'].get((ANDROID, name), default)


def children(node, name):
    return [n for n in node['children'] if n['name'] == name]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def source_fingerprint(project):
    files = [p for directory in ('d2helper/src/main', 'd2-shared/src/main/java')
             for p in (project / directory).rglob('*') if p.is_file()]
    files += [project / 'd2helper/build.gradle.kts', project / 'build.gradle.kts']
    digest = hashlib.sha256()
    for p in sorted(files, key=lambda p: p.relative_to(project).as_posix()):
        digest.update(p.relative_to(project).as_posix().encode('utf-8'))
        digest.update(b'\0')
        digest.update(p.read_bytes())
    return digest.hexdigest()


def certificates(apk, signer):
    output = subprocess.check_output([str(signer), 'verify', '--print-certs', str(apk)], text=True)
    found = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', output)
    require(len(found) == 1, 'Exactly one APK signer is required')
    return found[0].lower()


def embed_signed_helper(source, output, helper):
    """Replace only the nested APK and its byte-integrity receipt, before outer alignment/signing."""
    payload = Path(helper).read_bytes()
    with zipfile.ZipFile(source) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate outer APK entries')
        metadata = json.loads(archive.read('assets/d2/helper.json'))
        metadata.update(sha256=hashlib.sha256(payload).hexdigest(), size=len(payload))
        with zipfile.ZipFile(output, 'w') as destination:
            for entry in archive.infolist():
                data = archive.read(entry.filename)
                if entry.filename == 'assets/d2/helper.apk':
                    data = payload
                elif entry.filename == 'assets/d2/helper.json':
                    data = json.dumps(metadata, separators=(',', ':')).encode('utf-8')
                destination.writestr(entry, data)


def verify(apk, project=ROOT, signatures=True):
    with zipfile.ZipFile(apk) as parent:
        require(len(parent.namelist()) == len(set(parent.namelist())), 'Duplicate outer APK entries')
        metadata = json.loads(parent.read('assets/d2/helper.json'))
        payload = parent.read('assets/d2/helper.apk')
        host = manifest(parent.read('AndroidManifest.xml'))
    require(metadata['api'] == 1 and metadata['package'] == HELPER and metadata['version'] == 1, 'Helper identity mismatch')
    require(metadata['size'] == len(payload) and metadata['sha256'] == hashlib.sha256(payload).hexdigest(), 'Embedded helper digest/size mismatch')
    require(metadata['source'] == source_fingerprint(project), 'Helper source fingerprint mismatch')
    require(host['attributes'].get(('', 'package')) == CONTROLLER, 'Unexpected controller package')
    permission = [p for p in children(host, 'permission') if attribute(p, 'name') == PERMISSION]
    require(len(permission) == 1 and attribute(permission[0], 'protectionLevel') in (2, 'signature'), 'Controller must define a signature-only control permission')
    with tempfile.TemporaryDirectory(prefix='rmgu-d2-verify-') as temporary:
        helper_apk = Path(temporary) / 'helper.apk'
        helper_apk.write_bytes(payload)
        with zipfile.ZipFile(helper_apk) as helper:
            names = helper.namelist()
            require(len(names) == len(set(names)), 'Duplicate helper APK entries')
            require(not any(n.startswith(('lib/', 'assets/')) for n in names), 'Companion must not carry native code or extra payloads')
            xml = manifest(helper.read('AndroidManifest.xml'))
            require(metadata['source'].encode('ascii') in helper.read('classes.dex'), 'Companion lacks its source identity')
        require(xml['attributes'].get(('', 'package')) == HELPER, 'Unexpected companion package')
        require(attribute(xml, 'sharedUserId') == 'android.uid.system', 'Companion must request the system UID')
        require(attribute(xml, 'versionCode') == metadata['version'], 'Helper version mismatch')
        apps = children(xml, 'application')
        require(len(apps) == 1, 'Exactly one helper application is required')
        app = apps[0]
        require(attribute(app, 'process') == 'system', 'Helper must use the measured system process')
        require(attribute(app, 'directBootAware') is True, 'Helper is not direct-boot aware')
        require(attribute(app, 'allowBackup') is False and not attribute(app, 'debuggable', False), 'Helper must be non-debuggable with backups disabled')
        identity = [n for n in children(app, 'meta-data') if attribute(n, 'name') == HELPER + '.source']
        require(len(identity) == 1 and attribute(identity[0], 'value') == metadata['source'], 'Installed helper source identity mismatch')
        requested = {attribute(p, 'name') for p in children(xml, 'uses-permission')}
        require(requested == {'android.permission.RECEIVE_BOOT_COMPLETED'}, 'Unexpected helper permissions')
        components = [n for n in app['children'] if n['name'] in ('activity', 'activity-alias', 'service', 'receiver', 'provider')]
        names = {attribute(n, 'name'): n for n in components}
        expected = {NAMESPACE + '.' + n for n in ('D2Provider', 'ActivateActivity', 'D2BootReceiver')}
        require(set(names) == expected and len(components) == 3, 'Unexpected helper components')
        for name, node in names.items():
            if name.endswith('D2BootReceiver'):
                require(attribute(node, 'exported') is False and attribute(node, 'directBootAware') is True, 'Boot receiver exposure/direct-boot mismatch')
                actions = {attribute(a, 'name') for f in children(node, 'intent-filter') for a in children(f, 'action')}
                require(actions == {'android.intent.action.LOCKED_BOOT_COMPLETED', 'android.intent.action.BOOT_COMPLETED', 'android.intent.action.MY_PACKAGE_REPLACED'}, 'Unexpected boot actions')
            else:
                require(attribute(node, 'exported') is True and attribute(node, 'permission') == PERMISSION, 'Unprotected exported companion component')
                require(not children(node, 'intent-filter'), 'Control components must be explicit-only')
        require(attribute(names[NAMESPACE + '.D2Provider'], 'authorities') == HELPER + '.control', 'Unexpected control authority')
        if signatures:
            sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
            require(bool(sdk), 'Android SDK is required to verify APK signatures')
            signer = Path(sdk) / 'build-tools/36.0.0/apksigner'
            require(certificates(apk, signer) == certificates(helper_apk, signer), 'Companion/controller signing certificates differ')
    print(f'D2 companion verified: {len(payload)} bytes, SHA-256 {metadata["sha256"]}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--project', type=Path, default=ROOT)
    args = parser.parse_args()
    verify(args.apk, args.project)
