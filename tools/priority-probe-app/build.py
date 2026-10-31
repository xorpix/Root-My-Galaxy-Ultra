#!/usr/bin/env python3
"""Build a standalone ordinary-app UID priority probe with SDK tools, without Gradle."""
import argparse,hashlib,json,os,re,shutil,struct,subprocess,tempfile,zipfile
from pathlib import Path
from android_manifest import manifest,attribute,children
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk-root',type=Path,default=Path(os.environ.get('ANDROID_HOME','/opt/android-sdk')))
p.add_argument('--helper',type=Path)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args();here=Path(__file__).resolve().parent;root=here.parents[1]
sdk=a.sdk_root.resolve();tools=sdk/'build-tools/36.0.0';android=sdk/'platforms/android-36/android.jar';out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
helper=a.helper or (here/'libpriorityprobe.so' if (here/'libpriorityprobe.so').is_file() else root/'app/src/main/jniLibs/arm64-v8a/librmgususfs_priority.so')
data=helper.read_bytes();expected='8a1d3f909d81b029241f8e512763f15a663f12143398b849c99b7f65f5b4a64e'
assert hashlib.sha256(data).hexdigest()==expected and data[:6]==b'\x7fELF\x02\x01'
assert struct.unpack_from('<HH',data,16)==(3,183)
source_names=['AndroidManifest.xml','LICENSE','README.md','ProbeChecksTest.java','android_manifest.py','build.py','build_priority_client.py','rmgu_priority_ctl.c','src/dev/experimental/rmgu/priorityprobe/MainActivity.java','src/dev/experimental/rmgu/priorityprobe/ProbeChecks.java']
source_files={n:hashlib.sha256((here/n).read_bytes()).hexdigest() for n in source_names}
source_fingerprint=hashlib.sha256(json.dumps(source_files,sort_keys=True,separators=(',',':')).encode()).hexdigest()
identity={'schema':1,'sourceCommit':os.environ.get('GITHUB_SHA','local'),'sourceFingerprint':source_fingerprint,'nativeClientSha256':expected}
def run(*args,**kwargs):return subprocess.run([str(x) for x in args],check=True,**kwargs)
with tempfile.TemporaryDirectory(prefix='rmgu-app-probe-') as temp:
 work=Path(temp);classes=work/'classes';classes.mkdir();tests=work/'tests';tests.mkdir();dex=work/'dex';dex.mkdir()
 run('javac','-d',tests,here/'src/dev/experimental/rmgu/priorityprobe/ProbeChecks.java',here/'ProbeChecksTest.java')
 host=subprocess.check_output(['java','-ea','-cp',str(tests),'dev.experimental.rmgu.priorityprobe.ProbeChecksTest'],text=True)
 (out/'host-checks.txt').write_text(host);print(host,end='')
 java=sorted((here/'src').rglob('*.java'))
 run('javac','-source','8','-target','8','-bootclasspath',android,'-d',classes,*java)
 run(tools/'d8','--min-api','26','--lib',android,'--output',dex,*sorted(classes.rglob('*.class')))
 run(tools/'aapt2','link','-o',work/'base.apk','--manifest',here/'AndroidManifest.xml','-I',android,'--min-sdk-version','26','--target-sdk-version','36','--version-code','1','--version-name','1.0')
 shutil.copyfile(work/'base.apk',work/'assembled.apk')
 with zipfile.ZipFile(work/'assembled.apk','a') as z:
  z.write(dex/'classes.dex','classes.dex',compress_type=zipfile.ZIP_DEFLATED)
  z.writestr('assets/probe-build.json',json.dumps(identity,sort_keys=True)+'\n')
  item=zipfile.ZipInfo('lib/arm64-v8a/libpriorityprobe.so');item.external_attr=(0o100755<<16);z.writestr(item,data,compress_type=zipfile.ZIP_STORED)
 run(tools/'zipalign','-P','16','-f','4',work/'assembled.apk',work/'aligned.apk')
 # Test-only key is temporary. No repository signing secrets are read or used.
 run('keytool','-genkeypair','-keystore',work/'test.p12','-storetype','PKCS12','-storepass','android','-keypass','android','-alias','probe','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=RMGU Priority Probe Test')
 apk=out/'RMGU-Priority-Probe-1.0.apk'
 run(tools/'apksigner','sign','--ks',work/'test.p12','--ks-key-alias','probe','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,work/'aligned.apk')
 cert=subprocess.check_output([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
 (out/'signature.txt').write_text(cert)
 run(tools/'zipalign','-c','-P','16','4',apk)
 with zipfile.ZipFile(apk) as z:
  xml=manifest(z.read('AndroidManifest.xml'));app=children(xml,'application')[0]
  assert xml['attributes'][('', 'package')]=='dev.experimental.rmgu.priorityprobe'
  assert attribute(app,'debuggable') is False and attribute(app,'allowBackup') is False and attribute(app,'extractNativeLibs') is True
  assert not children(xml,'uses-permission') and attribute(xml,'sharedUserId') is None
  components=[n for n in app['children'] if n['name'] in ['activity','activity-alias','receiver','provider','service']]
  assert len(components)==1 and components[0]['name']=='activity' and attribute(components[0],'name')=='dev.experimental.rmgu.priorityprobe.MainActivity'
  assert z.read('lib/arm64-v8a/libpriorityprobe.so')==data
  assert [n for n in z.namelist() if n.startswith('lib/')]==['lib/arm64-v8a/libpriorityprobe.so']
 shutil.copyfile(here/'README.md',out/'README.md')
 with zipfile.ZipFile(out/'priority-probe-source.zip','w',compression=zipfile.ZIP_DEFLATED) as z:
  for name in source_names:z.write(here/name,'priority-probe-app/'+name)
  z.writestr('priority-probe-app/libpriorityprobe.so',data)
 report={'sourceCommit':os.environ.get('GITHUB_SHA','local'),'package':'dev.experimental.rmgu.priorityprobe','version':'1.0','apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'apkBytes':apk.stat().st_size,'nativeClientSha256':expected,'nativeClientSource':'Samsung SusFS 98b5149536fdc564132ca82b08d3980114be298a tools/rmgu_priority_ctl.c; unchanged bytes','testOnlySigningKey':True,'debuggable':False,'requestedPermissions':[],'signatureVerified':True,'signingCertSha256':re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})',cert).group(1).lower(),'sourceFingerprint':source_fingerprint,'zipAlignmentVerified':True,'manifestAndNativeBytesVerified':True,'verdictCases':8,'phoneExecution':'pending','sourceFiles':source_files}
 (out/'build-info.json').write_text(json.dumps(report,indent=2)+'\n')
 (out/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(f.read_bytes()).hexdigest()}  {f.name}\n' for f in sorted(out.iterdir()) if f.is_file() and f.name!='SHA256SUMS'))
 print(json.dumps(report,indent=2))
