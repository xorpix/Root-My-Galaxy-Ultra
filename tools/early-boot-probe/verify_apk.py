#!/usr/bin/env python3
"""Verify a paired, diagnostic-only controller/helper build, including their shared signer."""
import argparse,hashlib,os,re,shutil,subprocess,tempfile,zipfile
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:raise SystemExit('Android SDK is required')
    tools=Path(sdk)/'build-tools'/'36.0.0'
    def run(tool,*argv):
        r=subprocess.run([str(tools/tool),*map(str,argv)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        if r.returncode:raise SystemExit(r.stdout)
        return r.stdout
    def verify(apk,package,expected_permissions):
        sig=run('apksigner','verify','--verbose','--print-certs',apk)
        run('zipalign','-c','-P','16','4',apk)
        badge=run('aapt2','dump','badging',apk)
        assert "package: name='"+package+"'" in badge
        assert "versionCode='4'" in badge and "versionName='0.3.0'" in badge
        assert 'application-debuggable' not in badge
        permissions={line.split("name='")[1].split("'")[0] for line in badge.splitlines() if line.startswith('uses-permission:')}
        assert permissions==expected_permissions,permissions
        manifest=run('aapt2','dump','xmltree',apk,'--file','AndroidManifest.xml')
        cert=re.search(r'Signer #1 certificate SHA-256 digest: (\S+)',sig)
        assert cert,'Signing certificate digest missing'
        assert 'Signer #2 certificate SHA-256 digest' not in sig,'Multiple signers'
        return sig,badge,manifest,cert[1]
    permission='dev.experimental.rmgu.bootprobe.permission.HELPER'
    c=verify(args.apk,'dev.experimental.rmgu.bootprobe',{'android.permission.REQUEST_INSTALL_PACKAGES',permission})
    assert 'sharedUserId' not in c[2] and 'ProbeAccessibilityService' not in c[2]
    level=[line for line in c[2].splitlines() if 'android:protectionLevel(' in line]
    assert len(level)==1,level
    encoded=re.search(r'0x([0-9a-fA-F]+)\s*$',level[0].rsplit('=',1)[-1])
    assert encoded and int(encoded[1],16)==2,level
    with zipfile.ZipFile(args.apk) as z:
        names=set(z.namelist());assert 'assets/privileged-helper.apk' in names
        assert not any(n.endswith(('.ko','.jks','.p12')) for n in names)
        assert {n for n in names if n.endswith('.apk')}=={'assets/privileged-helper.apk'}
        helper=z.read('assets/privileged-helper.apk')
    args.output.mkdir(parents=True,exist_ok=True)
    helper_path=args.output/'RMGU-Privileged-Boot-Helper-0.3.0.apk';helper_path.write_bytes(helper)
    h=verify(helper_path,'dev.experimental.rmgu.boothelper',{'android.permission.INTERNET','android.permission.ACCESS_NOTIFICATION_POLICY'})
    assert c[3]==h[3],'Controller/helper signing certificates differ'
    for token in ('android.uid.system','EarlyConditionService','BIND_CONDITION_PROVIDER_SERVICE','HelperProvider',permission,'directBootAware'):
        assert token in h[2],token
    assert '"system"' in h[2],'Helper must be hosted in the system process'
    with zipfile.ZipFile(helper_path) as z:
        names=set(z.namelist())
        assert not any(n.endswith(('.so','.ko','.apk','.jks','.p12')) for n in names),'Privileged helper must be Java-only'
        dex=b''.join(z.read(n) for n in names if n.endswith('.dex'))
        for forbidden in (b'Ljava/lang/ProcessBuilder;',b'PrivilegeSetupMain',b'NativeProperties',b'AbxDocument'):
            assert forbidden not in dex,forbidden
    controller=args.output/'RMGU-Privileged-Boot-Probe-0.3.0.apk';shutil.copyfile(args.apk,controller)
    for apk in (controller,helper_path):
        digest=hashlib.sha256(apk.read_bytes()).hexdigest();(args.output/(apk.name+'.sha256')).write_text(digest+'  '+apk.name+'\n');print('Verified',apk.name,digest)
    (args.output/'apk-validation.txt').write_text('\n'.join(c[:3])+ '\n--- HELPER ---\n'+'\n'.join(h[:3]))
if __name__=='__main__':main()
