#!/usr/bin/env python3
"""Generate pinned per-backend M3Q helper variants, without compiling or running them.

The original helper is closed source. Two expected-version instructions change
for updated KernelSU; forks also change the end of execl's argument list. Ending
the list before --package-name lets each fork use its own compiled default.
The ioctl, version comparison, UAPI=4 and required flag checks remain intact.
"""
import hashlib
import json
from pathlib import Path
import struct

ORIGINAL_SHA256 = '39b018c3648c26fc7e801f6ec7a25018b3ef8544033afadbad4b36dd714d9d59'
VERSIONS = {'kernelsu': 32653, 'kernelsu-next': 33313, 'resukisu': 35195}
LIBRARIES = {key: 'libm3qroot_' + key.replace('-', '_') + '.so' for key in VERSIONS}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_offset(data, address):
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('Expected a little-endian ELF64 AArch64 helper')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    for index in range(count):
        kind, flags, offset, va, _, size, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + index * entsize)
        if kind == 1 and flags & 1 and va <= address and address + 4 <= va + size:
            return offset + address - va
    raise ValueError(f'Instruction 0x{address:x} is not in a file-backed executable segment')


def mov_w(register, value):
    if not 0 <= value <= 65535:
        raise ValueError('Driver version does not fit MOVZ W')
    return 0x52800000 | value << 5 | register


def variant(original, backend):
    if len(original) != 24232 or sha(original) != ORIGINAL_SHA256:
        raise ValueError('Refusing to patch an unrecognized M3Q helper')
    version = VERSIONS[backend]
    edits = [(0x6c94, mov_w(9, 32636), mov_w(9, version)),
             (0x6cc8, mov_w(2, 32636), mov_w(2, version))]
    # Original: add x5, x5, #0x7ca ("--package-name"). For forks, x5=NULL
    # terminates execl after --kmi android16-6.12; no package string is spoofed.
    if backend != 'kernelsu':
        edits.append((0x7234, 0x911F28A5, 0xAA1F03E5))
    result = bytearray(original)
    changes = []
    for address, expected, replacement in edits:
        offset = file_offset(original, address)
        if struct.unpack_from('<I', original, offset)[0] != expected:
            raise ValueError(f'Unexpected instruction at 0x{address:x}')
        struct.pack_into('<I', result, offset, replacement)
        if expected != replacement:
            changes.append({'virtual_address': hex(address), 'file_offset': offset,
                            'before': f'{expected:08x}', 'after': f'{replacement:08x}'})
    return bytes(result), changes


def prepare(project):
    assets = project / 'app/src/main/assets/m3q'
    jni = project / 'app/src/main/jniLibs/arm64-v8a'
    jni.mkdir(parents=True, exist_ok=True)
    original = (assets / 'libm3qroot.so').read_bytes()
    records = {}
    for backend in VERSIONS:
        data, changes = variant(original, backend)
        name = LIBRARIES[backend]
        (jni / name).write_bytes(data)
        records[backend] = dict(library=name, driverVersion=VERSIONS[backend],
                                size=len(data), sha256=sha(data), changes=changes)
    payload = (assets / 'libm3qpayload.so').read_bytes()
    if sha(payload) != '9ceb86833b9ae5da350dc04bc1a77d992680931973da1e9172b4ddd999b404dc':
        raise ValueError('Unrecognized M3Q payload')
    (jni / 'libm3qpayload.so').write_bytes(payload)
    catalog = json.loads((project / 'app/src/main/assets/azhl/catalog.json').read_text())
    for profile in catalog['payloads']:
        backend = profile['flavor']
        if backend not in VERSIONS:
            raise ValueError('Unrecognized backend')
        artifact = profile['kernelsu']
        if artifact['url'] != f'asset://azhl/{backend}/ksud':
            raise ValueError('Unexpected daemon asset path')
        daemon = (project / 'app/src/main/assets/azhl' / backend / 'ksud').read_bytes()
        if len(daemon) != artifact['size'] or sha(daemon) != artifact['sha256']:
            raise ValueError(f'Daemon checksum mismatch: {backend}')
        (jni / ('libm3qksud_' + backend.replace('-', '_') + '.so')).write_bytes(daemon)
    (assets / 'helper-variants.json').write_text(json.dumps(
        dict(originalSha256=ORIGINAL_SHA256, variants=records), indent=2) + '\n')
    print(json.dumps(records, indent=2))


if __name__ == '__main__':
    prepare(Path(__file__).resolve().parents[1])
