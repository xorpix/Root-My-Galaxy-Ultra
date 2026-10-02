#!/usr/bin/env python3
"""Copy the checkpoint's compiled, matched payload pairs into the Android bundle."""
from pathlib import Path
import hashlib, json, shutil, sys
from prepare_m3q_helpers import prepare

project = Path(__file__).resolve().parents[1]
built = Path(sys.argv[1]).resolve()
assets = project / 'app/src/main/assets/azhl'
assets.mkdir(parents=True, exist_ok=True)
shutil.copy2(built / 'native/loader.so', assets / 'loader.so')
jni = project / 'app/src/main/jniLibs/arm64-v8a'
jni.mkdir(parents=True, exist_ok=True)
shutil.copy2(built / 'native/libcve43499root.so', jni / 'libcve43499root.so')
# Tested M3Q exploit binaries (closed-source, extracted from the working M3Q APK;
# see <workspace>/m3q-reference/ORIGIN.md). The exploit is backend-agnostic, so
# every flavor references the same payload and differs by ksud.
m3q_assets = project / 'app/src/main/assets/m3q'
m3q_assets.mkdir(parents=True, exist_ok=True)
for name in ('libm3qpayload.so', 'libm3qoracle.so', 'libm3qroot.so'):
    shutil.copy2(built / 'm3q' / name, m3q_assets / name)
m3q_payload = m3q_assets / 'libm3qpayload.so'
assert hashlib.sha256(m3q_payload.read_bytes()).hexdigest() == \
    '9ceb86833b9ae5da350dc04bc1a77d992680931973da1e9172b4ddd999b404dc'
assert hashlib.sha256((m3q_assets / 'libm3qoracle.so').read_bytes()).hexdigest() == \
    'd85008e49bf72395455baefb697fb212e92b5533e0ab53a0e75cff818f064bf7'
assert hashlib.sha256((m3q_assets / 'libm3qroot.so').read_bytes()).hexdigest() == \
    '39b018c3648c26fc7e801f6ec7a25018b3ef8544033afadbad4b36dd714d9d59'
identity = dict(model='SM-S948B', device='m3q', incremental='S948BXXS4AZHL',
    kernelRelease='6.12.30-android16-5-pd30ff70-abogkiS948BXXS4AZHL-4k',
    sdk=36, abi='arm64-v8a', pageSize=4096)
route = dict(slideRoute='default', attempts=1, attemptTimeoutSec=120,
    p0AttemptTimeoutSec=45, p0OffsetCache=False, prefersShellTransport=True)
def artifact(path):
    return dict(url='asset://'+path.relative_to(assets.parent).as_posix(),
        size=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())
profiles = []
for flavor, version in [('kernelsu','3.3.0'),('kernelsu-next','3.4.0'),('resukisu','4.2.0-rc3')]:
    target = assets / flavor / 'ksud'
    target.parent.mkdir(exist_ok=True)
    shutil.copy2(built / flavor / 'ksud', target)
    if flavor == 'kernelsu':
        assert hashlib.sha256(target.read_bytes()).hexdigest() == 'c95eb525d2c50d9eb046be9e57ba624d5b7f456fcb81859809af9a3565a97d56'
    profiles.append(dict(payloadId='m3q-azhl-'+flavor, displayName='SM-S948B AZHL / '+flavor,
        models=[identity['model']], kernelVersions=[identity['kernelRelease']], firmware=identity,
        flavor=flavor, routePolicy=route, exploit=artifact(m3q_payload),
        kernelsu=dict(**artifact(target), version=version)))
(assets/'catalog.json').write_text(json.dumps(dict(schemaVersion=3,payloads=profiles), indent=2)+'\n')
prepare(project)
print('Bundled three matched backends and the M3Q exploit payload.')
