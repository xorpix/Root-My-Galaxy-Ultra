#!/usr/bin/env python3
"""Build a pinned, already patched backend with the recovered DDK and NDK."""
import json, os, pathlib, shutil, subprocess, sys
tools=pathlib.Path(sys.argv[1]).resolve()
flavor=sys.argv[2]
assert flavor in ('kernelsu-next','resukisu')
repo=tools/flavor
expected={'kernelsu-next':('1a879d6a866f80b1fa1c1009a2ffa747873cbb5e',33294),'resukisu':('239e1e8871b8fcd51a6e5b3002e0ba522fdd99fb',35171)}[flavor]
def git(*args):return subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()
assert git('rev-parse','HEAD')==expected[0]
assert 30000+int(git('rev-list','--count','HEAD'))+(700 if flavor=='resukisu' else 0)==expected[1]
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
env.update(CARGO_HOME=str(tools/'cargo'),RUSTUP_HOME=str(tools/'rustup'),CARGO_BUILD_JOBS='2',LIBCLANG_PATH='/opt/ddk/clang/clang-r536225/lib')
env['PATH']=str(ndk/'bin')+':'+str(tools/'cargo/bin')+':'+env['PATH']
subprocess.run([str(tools/'cargo/bin/cargo'),'+nightly' if flavor=='resukisu' else '+stable','build','--release','--locked','--target','aarch64-linux-android','--manifest-path','userspace/ksud/Cargo.toml'],cwd=repo,env=env,check=True)
print('BUILT',flavor,expected,flush=True)
