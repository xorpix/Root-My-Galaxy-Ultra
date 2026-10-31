#!/usr/bin/env python3
"""Build and verify the freestanding ARM64 configuration/probe client."""
import argparse,hashlib,json,struct,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--clang-bin',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
root=Path(__file__).resolve().parent;source=root/'rmgu_priority_ctl.c';a.output.parent.mkdir(parents=True,exist_ok=True)
command=[str(a.clang_bin/'clang'),'--target=aarch64-linux-gnu','-O2','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-nostdlib','-static-pie','-ffreestanding','-fno-builtin','-fno-stack-protector','-fuse-ld=lld','-Wl,-e,_start','-Wl,--build-id=none','-Wl,-z,max-page-size=4096','-o',str(a.output),str(source)]
subprocess.run(command,check=True)
b=a.output.read_bytes();assert b[:6]==b'\x7fELF\x02\x01'
assert struct.unpack_from('<HH',b,16)==(3,183),'must be ARM64 PIE'
phoff,shoff=struct.unpack_from('<QQ',b,32);phsize,phnum,shsize,shnum=struct.unpack_from('<HHHH',b,54)
for i in range(phnum):
 kind=struct.unpack_from('<I',b,phoff+i*phsize)[0];assert kind!=3,'no interpreter/CRT is provided'
for i in range(shnum):
 s=struct.unpack_from('<IIQQQQIIQQ',b,shoff+i*shsize);kind,offset,size=s[1],s[4],s[5]
 assert kind not in (4,9) or not size,'freestanding client has no dynamic relocator'
 if kind==6:
  assert all(struct.unpack_from('<Q',b,j)[0]!=1 for j in range(offset,offset+size,16)),'unexpected libc dependency'
report={'compiler':subprocess.check_output([str(a.clang_bin/'clang'),'--version'],text=True).strip(),'argv':command,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b),'arm64_pie':True,'interpreter':False,'dynamic_relocations':False,'hardware_validated':False}
a.output.with_suffix('.build.json').write_text(json.dumps(report,indent=2)+'\n');print('PASS ARM64 PIE client, no libc/interpreter/dynamic relocations')
