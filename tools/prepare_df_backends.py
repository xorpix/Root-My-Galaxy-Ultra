#!/usr/bin/env python3
"""Derive KowSU's bridge from the verified, backend-independent 6.12 bridge.

The existing KernelSU bridge passes its manager package to late-load. KowSU's
daemon already compiles in its own manager package, so remove that override in
non-executable string data. All instructions and command handling stay intact.
"""
import hashlib
import json
from pathlib import Path
import struct

SOURCE = 'dirtyfrag-android16-6.12-kernelsu.ko'
OUTPUT = 'dirtyfrag-android16-6.12-kowsu.ko'
SOURCE_SHA256 = 'cb667d8cd59a04b7ff0fa505bbafcf2e91de5b492ab2e84c7f2d0c42095bc0b4'
OVERRIDE = b' --package-name me.weishu.kernelsu'


def record(data):
    return {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def variant(source):
    if record(source) != {'size': 11128, 'sha256': SOURCE_SHA256}:
        raise ValueError('Unrecognized DirtyFrag bridge; review before re-pinning')
    if (source[:7] != b'\x7fELF\x02\x01\x01'
            or struct.unpack_from('<HH', source, 16) != (1, 183)):
        raise ValueError('Expected an ARM64 relocatable bridge')
    if source.count(OVERRIDE) != 1:
        raise ValueError('Expected one manager override')
    offset = source.index(OVERRIDE)
    shoff = struct.unpack_from('<Q', source, 40)[0]
    shsize, shnum = struct.unpack_from('<HH', source, 58)
    if shsize != 64 or shoff + shsize * shnum > len(source):
        raise ValueError('Invalid ELF section table')
    sections = [struct.unpack_from('<IIQQQQIIQQ', source, shoff + i * shsize)
                for i in range(shnum)]
    containing = [s for s in sections if s[1] == 1 and s[4] <= offset
                  and offset + len(OVERRIDE) <= s[4] + s[5]]
    if len(containing) != 1 or containing[0][2] & 4:
        raise ValueError('Manager override must be in non-executable data')
    result = bytearray(source)
    result[offset:offset + len(OVERRIDE)] = b' ' * len(OVERRIDE)
    return bytes(result), offset


def prepare(project):
    folder = project / 'app/src/main/cpp/dirtyfrag/ko'
    source = (folder / SOURCE).read_bytes()
    data, offset = variant(source)
    (folder / OUTPUT).write_bytes(data)
    provenance = project / 'backends/kowsu/dirtyfrag-bridge.json'
    provenance.parent.mkdir(parents=True, exist_ok=True)
    provenance.write_text(json.dumps({
        'schema': 1,
        'source': dict(path=SOURCE, **record(source)),
        'output': dict(path=OUTPUT, **record(data)),
        'manager_package': 'com.kowx712.supermanager',
        'manager_selection': 'KowSU daemon compiled default; no package override',
        'edit': {'file_offset': offset, 'before': OVERRIDE.decode(),
                 'after': ' ' * len(OVERRIDE)},
    }, indent=2) + '\n')


if __name__ == '__main__':
    prepare(Path(__file__).resolve().parents[1])
