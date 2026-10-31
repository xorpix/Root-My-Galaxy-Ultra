#!/usr/bin/env python3
"""Build and validate the source-pinned Samsung priority entry-probe candidate."""
import argparse,hashlib,json,shutil,subprocess,sys,tarfile
from pathlib import Path,PurePosixPath
from check_susfs_bundle import git_hash,tree_hash
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--kdir',type=Path,default=Path('/opt/ddk/kdir/android16-6.12'))
p.add_argument('--clang-bin',type=Path,default=Path('/opt/ddk/clang/clang-r536225/bin'))
a=p.parse_args();root=Path(__file__).resolve().parents[1];source=root/'backends/susfs/priority-probe-candidate';out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
spec=json.loads((source/'source.json').read_text());commit=(source/'source.commit').read_bytes();archive=source/'source.tar.gz'
assert digest(archive)==spec['sourceArchiveSha256']
assert git_hash('commit',commit)==spec['sourceCommit']
assert commit.startswith(('tree '+spec['sourceTree']+'\nparent '+spec['sourceParent']+'\n').encode())
tree={}
with tarfile.open(archive) as t:
 assert t.pax_headers.get('comment')==spec['sourceCommit']
 for item in t:
  parts=PurePosixPath(item.name).parts;assert parts and parts[0]=='susfs' and '..' not in parts
  if item.isdir():continue
  assert item.isfile() or item.issym()
  data=t.extractfile(item).read() if item.isfile() else item.linkname.encode()
  node=tree
  for part in parts[1:-1]:node=node.setdefault(part,{})
  assert parts[-1] not in node
  node[parts[-1]]=('120000' if item.issym() else ('100755' if item.mode&0o111 else '100644'),git_hash('blob',data))
 assert tree_hash(tree)==spec['sourceTree']
 t.extractall(out/'work',filter='data')
assert digest(a.kdir/'include/generated/autoconf.h')=='1fbc9de7fd352a6a44f0a67296bad10464b956bf59144382f404ac17d7e2fde2'
assert digest(a.kdir/'Module.symvers')=='7d6930b74c649e4e2ceda3ef24e74774458844c34a41d29e042ccf916b509f96'
baseline=root/'app/src/main/assets/susfs/bzig/susfs_guard_lkm.ko'
assert digest(baseline)==spec['baselineModuleSha256']
native=out/'work/susfs'
def run(*args):subprocess.run([str(x) for x in args],check=True)
run(sys.executable,native/'tools/run_host_suite.py','--output',out/'host','--jobs','2')
run(sys.executable,native/'tools/build_review.py','--kdir',a.kdir,'--clang-bin',a.clang_bin,'--rust-bin','/opt/ddk/rust/rust-1.82.0/bin','--omit-module-btf','--evidence-dir',out/'build','--jobs','2')
module=native/'kernel/susfs_guard_lkm.ko'
run(sys.executable,native/'tools/check_priority_artifact.py','--candidate',module,'--baseline',baseline,'--baseline-is-priority','--kdir',a.kdir,'--clang-bin',a.clang_bin,'--output',out/'abi')
shutil.copyfile(module,out/'susfs_guard_lkm.unstripped.ko')
run(a.clang_bin/'llvm-strip','--strip-debug','-o',out/'susfs_guard_lkm.ko',module)
run(sys.executable,native/'tools/build_priority_client.py','--clang-bin',a.clang_bin,'--output',out/'rmgu-priority-ctl')
assert digest(out/'rmgu-priority-ctl')==spec['priorityControlSha256'],'Unchanged client must reproduce exactly'
for name in ['source.tar.gz','source.commit','source.json']:shutil.copyfile(source/name,out/name)
# Keep corresponding source, precise provenance and review evidence; discard build intermediates.
shutil.rmtree(out/'work')
files={str(f.relative_to(out)):{'bytes':f.stat().st_size,'sha256':digest(f)} for f in sorted(out.rglob('*')) if f.is_file()}
(out/'candidate.json').write_text(json.dumps({'source':spec,'hardwareValidated':False,'files':files},indent=2)+'\n')
print('PASS source provenance, pinned DDK, host regressions, real-header callback ABI and matched client')
