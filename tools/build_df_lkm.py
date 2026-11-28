#!/usr/bin/env python3
"""Build the android16-6.12 DirtyFrag LKM variants for Root My Galaxy Ultra.

The module source is vendored from diabl0w/DFRoot at 7a6513a (v4.7 plus the
libbase hook fallback), GPL-2.0. Upstream's module execs a bootstrap binary;
RMGU keeps its own late-load command so the staged per-flavor ksud and the
existing /dev/dfm0, /dev/dfm1 and pre-load marker contract stay intact.

The old e47ea6e module had no module_exit. On kernels whose cfi_init walks
both the init and cleanup jump tables, loading it crashes in strcmp before
the module can run. Upstream added the empty exit function for that reason;
the command below also keeps the newer avc_has_perm guard, direct DEFEX
patching and crash_dump64 page-cache invalidation that the old module lacked.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LKM_DIR = ROOT / 'app/src/main/cpp/dirtyfrag/lkm'
KO_DIR = ROOT / 'app/src/main/cpp/dirtyfrag/ko'
SOURCE = LKM_DIR / 'dfroot.c'
PROVENANCE = KO_DIR / 'provenance.json'

UPSTREAM_REPO = 'https://github.com/diabl0w/DFRoot'
UPSTREAM_COMMIT = '7a6513a'
STAGED = '/data/user_de/0/dev.experimental.azhlroot'

FLAVORS = {
    'kernelsu': 'me.weishu.kernelsu',
    'kernelsu-next': 'com.rifsxd.ksunext',
    'resukisu': 'org.bakasu.bakasu',
}
KOWSU_PACKAGE = 'com.kowx712.supermanager'
KOWSU_OVERRIDE = b' --package-name me.weishu.kernelsu'
GENERIC_PACKAGE = 'me.weishu.kernelsu'

# The bridge runs as the init-domain shell the module starts. It keeps the
# old command's order: clear stale markers, protect images, wipe or disable
# modules when armed, then start the staged daemon and wait for its driver.
# The daemon is backgrounded because late-load hangs after the driver is up;
# dfm0 is touched as soon as the driver appears, dfm1 only on a real failure.
COMMAND = (
    'rm -f /dev/dfro /dev/dfwipe /dev/dfwipeskip;'
    f'if [ ! -f {STAGED}/df-noro ];then n=0;'
    'for p in boot dtbo init_boot vendor_boot super optics prism vbmeta vbmeta_system;do '
    '[ -e /dev/block/by-name/$p ]&&/system/bin/blockdev --setro /dev/block/by-name/$p 2>/dev/null&&n=$((n+1));'
    '[ -e /dev/block/by-name/${p}_a ]&&/system/bin/blockdev --setro /dev/block/by-name/${p}_a 2>/dev/null&&n=$((n+1));'
    '[ -e /dev/block/by-name/${p}_b ]&&/system/bin/blockdev --setro /dev/block/by-name/${p}_b 2>/dev/null&&n=$((n+1));'
    'done;[ $n -gt 0 ]&&touch /dev/dfro;fi;'
    f'W={STAGED}/df-wipe-requested;if [ -f $W ];then '
    'if [ -e /sys/module/kernelsu ]||[ -e /sys/module/ksunext ]||[ -e /sys/module/sukisu ]||[ -e /sys/module/resukisu ]||[ -e /sys/module/ksu ];then '
    'touch /dev/dfwipeskip;else '
    'rm -rf /data/adb/modules/* /data/adb/modules/.[!.]* /data/adb/modules/..?* /data/adb/modules_update/* /data/adb/modules_update/.[!.]* /data/adb/modules_update/..?* 2>/dev/null;'
    'touch /dev/dfwipe;rm -f $W;fi;fi;'
    f'D={STAGED}/df-disable-modules;if [ -f $D ];then n=0;'
    'for m in /data/adb/modules/* /data/adb/modules_update/*;do [ -d $m ]&&[ ! -L $m ]&&[ ! -e $m/disable ]&&: >> $m/disable&&n=$((n+1));done;'
    '[ $n -gt 0 ]&&touch /dev/dfmoddis;rm -f $D;fi;'
    f'mkdir -p /data/adb&&cat {STAGED}/ksud > /data/local/tmp/.ksud-stage&&{STAGED}/ksud late-load --package-name __PACKAGE__ > /data/local/tmp/.ll.log 2>&1 & LL=$!;'
    'i=0;while [ $i -lt 60 ];do '
    '[ -e /sys/module/kernelsu ]||[ -e /sys/module/ksunext ]||[ -e /sys/module/sukisu ]||[ -e /sys/module/resukisu ]||[ -e /sys/module/ksu ]&&touch /dev/dfm0&&exit 0;'
    'kill -0 $LL 2>/dev/null||break;i=$((i+1));sleep 2;done;'
    'if [ $i -ge 60 ];then kill -9 $LL 2>/dev/null;touch /dev/dfm1;else wait $LL&&touch /dev/dfm0||touch /dev/dfm1;fi'
)

OLD_BLOCK = '''    static const char bootstrap[] = "/data/user_de/0/df.root/bootstrap";
    static char cmd[512];
    static char *envp[] = { "PATH=/system/bin", NULL };
    static char *argv[] = { (char *)sh, "-c", cmd, NULL };
    snprintf(cmd, sizeof(cmd), "touch /dev/dfm0; echo -n 'u:r:init:s0' > /proc/self/attr/exec; exec %s", bootstrap);'''

REQUIRED_SOURCE = (
    'module_exit(dfroot_exit);',
    'cfi_init walks both',
    'aarch64_insn_patch_text',
    'avc_has_perm',
    'task_defex_enforce',
)


def vendored_source() -> bytes:
    """The vendored source with LF endings, whatever the checkout used.

    Provenance hashes these bytes, so a CRLF Windows checkout and an LF
    Linux checkout must agree on them.
    """
    return SOURCE.read_bytes().replace(b'\r\n', b'\n')


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def record(data: bytes) -> dict:
    return {'size': len(data), 'sha256': sha256(data)}


def command_for(package: str) -> str:
    command = COMMAND.replace('__PACKAGE__', package)
    if '"' in command or '\\' in command:
        raise ValueError('The bridge command must stay a plain C string')
    if len(command) >= 4096:
        raise ValueError('The bridge command no longer fits the module cmd buffer')
    return command


def render_source(source: str, package: str) -> str:
    if OLD_BLOCK not in source:
        raise ValueError('Unrecognized upstream LKM command block; review before rebuilding')
    for marker in REQUIRED_SOURCE:
        if marker not in source:
            raise ValueError(f'Vendored LKM is missing the expected fix: {marker}')
    command = command_for(package)
    new_block = f'''    static const char late_load[] = "{command}";
    static char cmd[4096];
    static char *envp[] = {{ "PATH=/system/bin", NULL }};
    static char *argv[] = {{ (char *)sh, "-c", cmd, NULL }};
    snprintf(cmd, sizeof(cmd), "echo -n 'u:r:init:s0' > /proc/self/attr/exec; %s", late_load);'''
    return source.replace(OLD_BLOCK, new_block)


def elf_has_exit(data: bytes) -> bool:
    if data[:4] != b'\x7fELF' or data[4:6] != b'\x02\x01':
        return False
    shoff = struct.unpack_from('<Q', data, 40)[0]
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', data, 58)
    if shentsize != 64 or shoff + shentsize * shnum > len(data):
        return False
    strings_off = struct.unpack_from('<Q', data, shoff + shentsize * shstrndx + 24)[0]
    strings_size = struct.unpack_from('<Q', data, shoff + shentsize * shstrndx + 32)[0]
    strings = data[strings_off:strings_off + strings_size]
    names = []
    for index in range(shnum):
        name_off = struct.unpack_from('<I', data, shoff + shentsize * index)[0]
        end = strings.find(b'\0', name_off)
        names.append(strings[name_off:end].decode('ascii', 'replace'))
    return '.exit.text' in names and b'cleanup_module' in data


def derive_kowsu(source: bytes) -> tuple[bytes, int]:
    """Match tools/prepare_df_backends.py: blank the manager override in data."""
    if source.count(KOWSU_OVERRIDE) != 1:
        raise ValueError('Expected one manager override in the KernelSU bridge')
    offset = source.index(KOWSU_OVERRIDE)
    shoff = struct.unpack_from('<Q', source, 40)[0]
    shsize, shnum = struct.unpack_from('<HH', source, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', source, shoff + i * shsize)
                for i in range(shnum)]
    containing = [s for s in sections if s[1] == 1 and s[4] <= offset
                  and offset + len(KOWSU_OVERRIDE) <= s[4] + s[5]]
    if len(containing) != 1 or containing[0][2] & 4:
        raise ValueError('Manager override must be in non-executable data')
    result = bytearray(source)
    result[offset:offset + len(KOWSU_OVERRIDE)] = b' ' * len(KOWSU_OVERRIDE)
    return bytes(result), offset


def build(engine: str, image: str, package: str, work: Path) -> bytes:
    work.mkdir(parents=True, exist_ok=True)
    (work / 'dfroot.c').write_text(
        render_source(vendored_source().decode('utf-8'), package),
        encoding='utf-8', newline='\n')
    shutil.copy2(LKM_DIR / 'Makefile', work / 'Makefile')
    mount = str(work).replace('\\', '/')
    subprocess.run(
        [engine, 'run', '--rm', '--pid=host', '--network=none',
         '-v', f'{mount}:/src', '-w', '/src', image, 'make'],
        check=True,
    )
    output = work / 'dfroot.ko'
    if not output.is_file():
        raise FileNotFoundError(output)
    data = output.read_bytes()
    if not elf_has_exit(data):
        raise ValueError('Built module has no .exit.text/cleanup_module CFI entry')
    return data


def write_provenance(images: dict[str, bytes], source: bytes, command: str) -> None:
    provenance = {
        'schema': 1,
        'upstream': {
            'repo': UPSTREAM_REPO,
            'commit': UPSTREAM_COMMIT,
            'path': 'lkm/dfroot.c',
            'license': 'GPL-2.0',
        },
        'source': {'path': 'app/src/main/cpp/dirtyfrag/lkm/dfroot.c', **record(source)},
        'bridge': {
            'staged': STAGED,
            'command_sha256': sha256(command.encode()),
            'command_bytes': len(command),
            'markers': ['/dev/dfro', '/dev/dfwipe', '/dev/dfwipeskip', '/dev/dfmoddis',
                        '/dev/dfm0', '/dev/dfm1'],
        },
        'images': {name: record(data) for name, data in sorted(images.items())},
    }
    PROVENANCE.write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8', newline='\n')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', default='docker')
    parser.add_argument('--image', default='ghcr.io/ylarod/ddk-min:android16-6.12')
    parser.add_argument('--only', choices=sorted(FLAVORS), help='build one flavor (plus generic)')
    parser.add_argument('--check', action='store_true', help='verify provenance and source only')
    args = parser.parse_args()

    source = vendored_source()
    command = command_for(GENERIC_PACKAGE)
    if args.check:
        if not PROVENANCE.is_file():
            raise SystemExit('provenance.json is missing; run the build first')
        provenance = json.loads(PROVENANCE.read_text())
        if provenance['source'] != {'path': 'app/src/main/cpp/dirtyfrag/lkm/dfroot.c', **record(source)}:
            raise SystemExit('Vendored LKM source changed; rebuild the modules')
        if provenance['bridge']['command_sha256'] != sha256(command.encode()):
            raise SystemExit('Bridge command changed; rebuild the modules')
        for name, expected in provenance['images'].items():
            data = (KO_DIR / name).read_bytes()
            if record(data) != expected:
                raise SystemExit(f'{name} does not match provenance')
            if not elf_has_exit(data):
                raise SystemExit(f'{name} lost its CFI exit entry')
        print(f'verified {len(provenance["images"])} DirtyFrag modules')
        return 0

    selected = {args.only: FLAVORS[args.only]} if args.only else dict(FLAVORS)
    images: dict[str, bytes] = {}
    with tempfile.TemporaryDirectory(prefix='rmgu-df-lkm-') as temp:
        temp_path = Path(temp)
        generic = build(args.engine, args.image, GENERIC_PACKAGE, temp_path / 'generic')
        images['dirtyfrag-android16-6.12.ko'] = generic
        for flavor, package in selected.items():
            data = build(args.engine, args.image, package, temp_path / flavor)
            images[f'dirtyfrag-android16-6.12-{flavor}.ko'] = data
        kernelsu = images['dirtyfrag-android16-6.12-kernelsu.ko']
        kowsu, offset = derive_kowsu(kernelsu)
        images['dirtyfrag-android16-6.12-kowsu.ko'] = kowsu
        for name, data in images.items():
            (KO_DIR / name).write_bytes(data)
        write_provenance(images, source, command)
        print(f'wrote {len(images)} modules; kowsu override at {offset}')
        for name, data in sorted(images.items()):
            print(f'  {name}: {len(data)} bytes {sha256(data)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
