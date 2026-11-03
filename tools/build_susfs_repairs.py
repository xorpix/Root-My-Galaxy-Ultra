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
archive=project/'backends/susfs/bundle/source.tar.gz'
if not archive.exists():
    candidates=list(project.glob('backends/**/source.tar.gz'))
    candidates=[x for x in candidates if hashlib.sha256(x.read_bytes()).hexdigest()=='953216c328b35ab9bf5ebb7599d0f9a5627565276a0ea340d2be1372a1fddd46']
    if len(candidates)!=1: raise RuntimeError('Pinned audited source archive not found')
    archive=candidates[0]
assert hashlib.sha256(archive.read_bytes()).hexdigest()=='953216c328b35ab9bf5ebb7599d0f9a5627565276a0ea340d2be1372a1fddd46'
with tarfile.open(archive) as t: t.extractall(work,filter='data')
source=work/'susfs'
patch=project/'docs/susfs-repairs-20261103/native.patch'
subprocess.run(['git','apply','--check',str(patch)],cwd=source,check=True)
subprocess.run(['git','apply',str(patch)],cwd=source,check=True)
receipt={'base_native_commit':'8b9d19b3b348439b519260706896247e25633ec9',
         'patch_sha256':hashlib.sha256(patch.read_bytes()).hexdigest(),'hardware_validated':False}
(out/'source.json').write_text(json.dumps(receipt,indent=2)+'\n')
if a.prepare_only: raise SystemExit(0)
clang=Path('/opt/ddk/clang/clang-r536225/bin')
kdir=Path('/opt/ddk/kdir/android16-6.12')
subprocess.run(['python3',str(source/'tools/build_review.py'),'--kdir',str(kdir),
    '--clang-bin',str(clang),'--rust-bin','/opt/ddk/rust/rust-1.82.0/bin',
    '--evidence-dir',str(out/'build'),'--omit-module-btf'],check=True)
module=out/'susfs_guard_lkm.ko'; shutil.copy2(source/'kernel/susfs_guard_lkm.ko',module)
subprocess.run([str(clang/'llvm-strip'),'-d',str(module)],check=True)
loader=out/'libsusfs_loader.so'
subprocess.run([str(clang/'clang'),'--target=aarch64-linux-gnu','-O2','-nostdlib','-static-pie',
    '-ffreestanding','-fno-stack-protector','-fno-builtin','-fuse-ld=lld','-Wl,-e,_start',
    '-Wl,--build-id=none','-Wl,-z,max-page-size=4096','-o',str(loader),str(source/'tools/susfs_insmod.c')],check=True)
receipt['artifacts']={x.name:{'bytes':x.stat().st_size,'sha256':hashlib.sha256(x.read_bytes()).hexdigest()} for x in (module,loader)}
(out/'source.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
