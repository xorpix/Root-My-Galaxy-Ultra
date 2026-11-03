#!/usr/bin/env python3
"""Reproduce the repair candidate from the audited archive and reviewable patch."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--work',type=Path,required=True)
p.add_argument('--prepare-only',action='store_true')
a=p.parse_args()
project=Path(__file__).resolve().parents[1]
work=a.work.resolve(); work.mkdir(parents=True,exist_ok=True)
out=work/'output'; out.mkdir(exist_ok=True)
archive=project/'backends/susfs/source.tar.gz'
manifest=json.loads((project/'backends/susfs/build-manifest.json').read_text())
raw_commit=(project/'docs/susfs-repairs-20261103/native.commit').read_bytes()
candidate_commit=hashlib.sha1(b'commit '+str(len(raw_commit)).encode()+b'\0'+raw_commit).hexdigest()
archive_digest=hashlib.sha256(archive.read_bytes()).hexdigest()
assert archive_digest==manifest['sourceArchiveSha256']
with tarfile.open(archive) as t: t.extractall(work,filter='data')
source=work/'susfs'
patch=project/'docs/susfs-repairs-20261103/native.patch'
if manifest['sourceCommit']=='8b9d19b3b348439b519260706896247e25633ec9':
    assert archive_digest=='953216c328b35ab9bf5ebb7599d0f9a5627565276a0ea340d2be1372a1fddd46'
    subprocess.run(['git','apply','--check',str(patch)],cwd=source,check=True)
    subprocess.run(['git','apply',str(patch)],cwd=source,check=True)
else:
    assert manifest['sourceCommit']==candidate_commit,'archive must match reviewed candidate'
    subprocess.run(['python3',str(project/'tools/check_susfs_bundle.py')],check=True)
receipt={'base_native_commit':'8b9d19b3b348439b519260706896247e25633ec9',
         'source_commit':candidate_commit,
         'patch_sha256':hashlib.sha256(patch.read_bytes()).hexdigest(),'hardware_validated':False}
(out/'source.json').write_text(json.dumps(receipt,indent=2)+'\n')
if a.prepare_only: raise SystemExit(0)
clang=Path('/opt/ddk/clang/clang-r536225/bin')
kdir=Path('/opt/ddk/kdir/android16-6.12')
baseline=out/'audited-baseline.ko'
baseline.write_bytes(subprocess.check_output(['git','show','3c1394edcbc49e40cc46b0c31eb4d09f0be4c42b:app/src/main/assets/susfs/bzig/susfs_guard_lkm.ko'],cwd=project))
build=subprocess.run(['python3',str(source/'tools/build_review.py'),'--kdir',str(kdir),
    '--clang-bin',str(clang),'--rust-bin','/opt/ddk/rust/rust-1.82.0/bin',
    '--evidence-dir',str(out/'build'),'--omit-module-btf'])
if build.returncode:
    print((out/'build/compile.log').read_text(),flush=True)
    raise SystemExit(build.returncode)
module=out/'susfs_guard_lkm.ko'; shutil.copy2(source/'kernel/susfs_guard_lkm.ko',module)
subprocess.run([str(clang/'llvm-strip'),'-d',str(module)],check=True)
subprocess.run(['python3',str(source/'tools/check_priority_artifact.py'),
    '--baseline-is-audit','--candidate',str(module),'--baseline',str(baseline),
    '--kdir',str(kdir),'--clang-bin',str(clang),'--output',str(out/'abi')],check=True)
loader=out/'libsusfs_loader.so'
subprocess.run([str(clang/'clang'),'--target=aarch64-linux-gnu','-O2','-nostdlib','-static-pie',
    '-ffreestanding','-fno-stack-protector','-fno-builtin','-fuse-ld=lld','-Wl,-e,_start',
    '-Wl,--build-id=none','-Wl,-z,max-page-size=4096','-o',str(loader),str(source/'tools/susfs_insmod.c')],check=True)
receipt['artifacts']={x.name:{'bytes':x.stat().st_size,'sha256':hashlib.sha256(x.read_bytes()).hexdigest()} for x in (module,loader)}
if manifest['sourceCommit']==candidate_commit:
    assert module.read_bytes()==(project/manifest['module']['path']).read_bytes(),'rebuilt module differs from bundled candidate'
    assert loader.read_bytes()==(project/manifest['loader']['path']).read_bytes(),'rebuilt loader differs from bundled candidate'
(out/'source.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
