#!/usr/bin/env python3
"""Build a pinned, already patched backend with the recovered DDK and NDK."""
import hashlib, json, os, pathlib, shutil, subprocess, sys
tools=pathlib.Path(sys.argv[1]).resolve()
flavor=sys.argv[2]
assert flavor in ('kernelsu','kernelsu-next','resukisu')
repo=tools/flavor
plan=json.loads((tools/'targets.json').read_text())
target=plan['backends'][flavor]
expected=(target['commit'],target['version'])
def git(*args):return subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()
assert git('rev-parse','HEAD')==expected[0]
assert int(git('rev-list','--count','HEAD'))==target['commit_count']
assert target['version_offset']+target['commit_count']==expected[1]
assert plan['uapi']==5, 'A new UAPI requires a reviewed app/helper update'
assert 'KERNEL_SU_UAPI_VERSION = 5;' in (repo/'uapi/supercall.h').read_text()
for name in git('ls-files','*Cargo.toml','*Cargo.lock').splitlines():
    if name.startswith('manager/'):continue
    p=repo/name
    p.write_text(p.read_text().replace('github.com/Kernel-SU/','github.com/KernelSU2/'))
env=os.environ.copy()
env['PATH']='/opt/ddk/clang/clang-r536225/bin:/opt/ddk/rust/rust-1.82.0/bin:'+env['PATH']
kernel=repo/'kernel'
args=['make','-C','/opt/ddk/kdir/android16-6.12','M='+str(kernel),'src='+str(kernel),
 'ARCH=arm64','CROSS_COMPILE=aarch64-linux-gnu-','LLVM=1','LLVM_IAS=1','CONFIG_KSU=m',
 'CONFIG_KSU_SAMSUNG_KDP=y','CONFIG_KSU_SAMSUNG_RKP=y','CONFIG_KSU_SAMSUNG_DEFEX=y','CONFIG_DEBUG_INFO_BTF_MODULES=']
if flavor=='resukisu':args+=['CONFIG_KSU_TRACEPOINT_HOOK=y','CONFIG_KSU_MULTI_MANAGER_SUPPORT=y']
subprocess.run(args+['modules','-j4'],env=env,check=True)
module=kernel/'kernelsu.ko'
subprocess.run(['/opt/ddk/clang/clang-r536225/bin/llvm-strip','-d',str(module)],check=True)
shutil.copy2(module,repo/'userspace/ksud/bin/aarch64/android16-6.12_kernelsu.ko')
ndk=tools/'android-ndk-r28c/toolchains/llvm/prebuilt/linux-x86_64'
config=repo/'.cargo/config.toml'
config.parent.mkdir(exist_ok=True)
config.write_text('[target.aarch64-linux-android]\nlinker = '+json.dumps(str(ndk/'bin/aarch64-linux-android26-clang'))+'\n\n[env]\n'+
 '\n'.join(k+' = '+json.dumps(v) for k,v in {
 'CC_aarch64_linux_android':str(ndk/'bin/aarch64-linux-android26-clang'),
 'CXX_aarch64_linux_android':str(ndk/'bin/aarch64-linux-android26-clang++'),
 'AR_aarch64_linux_android':str(ndk/'bin/llvm-ar'),
 'BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android':'--sysroot='+str(ndk/'sysroot')+' -I'+str(ndk/'sysroot/usr/include/aarch64-linux-android')}.items())+'\n')
env.update(CARGO_HOME=str(tools/'cargo'),RUSTUP_HOME=str(tools/'rustup'),CARGO_BUILD_JOBS='2',LIBCLANG_PATH=str(ndk/'lib'),ANDROID_NDK_HOME=str(tools/'android-ndk-r28c'))
env['PATH']=str(ndk/'bin')+':'+str(tools/'cargo/bin')+':'+env['PATH']
cargo=[str(tools/'cargo/bin/cargo'),'+'+target['rust']]
if flavor=='kernelsu':
    for step in ('check','clippy'):
        extra=['--','-D','warnings'] if step=='clippy' else []
        subprocess.run(cargo+['ndk','-t','arm64-v8a','--platform','26',step,'--locked']+extra,cwd=repo/'userspace/ksud',env=env,check=True)
    subprocess.run(cargo+['fmt'],cwd=repo/'userspace/ksud',env=env,check=True)
subprocess.run(cargo+['build','--release','--locked','--target','aarch64-linux-android','--manifest-path','userspace/ksud/Cargo.toml'],cwd=repo,env=env,check=True)
metadata=json.loads(subprocess.check_output(cargo+['metadata','--no-deps','--format-version=1','--manifest-path','userspace/ksud/Cargo.toml'],cwd=repo,env=env,text=True))
daemon=pathlib.Path(metadata['target_directory'])/'aarch64-linux-android/release/ksud'
assert daemon.is_file(), 'Daemon output missing'
artifacts=tools/'built'/flavor
artifacts.mkdir(parents=True,exist_ok=True)
shutil.copy2(module,artifacts/'kernelsu.ko')
shutil.copy2(daemon,artifacts/'ksud')
def fingerprint(path):
    data=path.read_bytes()
    return {'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}
(artifacts/'build-receipt.json').write_text(json.dumps({
    'commit':expected[0],'driver_version':expected[1],'uapi':5,
    'ksud':fingerprint(daemon),'kernelsu.ko':fingerprint(module)},indent=2)+'\n')
print('BUILT',flavor,expected,flush=True)
print('DAEMON',daemon,flush=True)
