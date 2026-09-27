#!/usr/bin/env python3
"""Recover only the verified DDK layers needed for the Android 16 / 6.12 build."""
import concurrent.futures, hashlib, json, os, pathlib, posixpath, shutil, sys, tarfile, urllib.request
root=pathlib.Path(sys.argv[1]).resolve()
root.mkdir(parents=True,exist_ok=True)
repository='ylarod/ddk-min'
tag='android16-6.12-20260828'
registry='https://ghcr.io/v2/'+repository
def fetch(url, token=None, accept=None):
    headers={}
    if token: headers['Authorization']='Bearer '+token
    if accept: headers['Accept']=accept
    return urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=180)
with fetch('https://ghcr.io/token?scope=repository:'+repository+':pull&service=ghcr.io') as r:
    token=json.load(r)['token']
accept='application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'
with fetch(registry+'/manifests/'+tag,token,accept) as r: manifest=json.load(r)
if 'manifests' in manifest:
    descriptor=next(x for x in manifest['manifests'] if x.get('platform',{}).get('architecture')=='amd64' and x.get('platform',{}).get('os')=='linux')
    with fetch(registry+'/manifests/'+descriptor['digest'],token,accept) as r: manifest=json.load(r)
(root/'ddk-manifest.json').write_text(json.dumps(manifest,indent=2))
with fetch(registry+'/blobs/'+manifest['config']['digest'],token) as r:
    (root/'ddk-config.json').write_bytes(r.read())
digests=[
 'dab286fa65778c677a832433518088dfd79ccd5312e66c799addf5359fd90933',
 'c2ed66ea184eca558851f2576b5ca2a4197daed1cabddf39160c930c9ba30771',
 '00278a1d6b5bfe3356172f058f8fda6dc531eb2bc7a8af915e73dcf6ae958b82',
]
assert all('sha256:'+d in {x['digest'] for x in manifest['layers']} for d in digests), 'DDK manifest no longer has pinned layers'
def download(digest):
    path=root/(digest+'.tar.gz')
    if not path.exists():
        temp=path.with_suffix('.partial')
        with fetch(registry+'/blobs/sha256:'+digest,token) as src,temp.open('wb') as dst:
            shutil.copyfileobj(src,dst,1024*1024)
        temp.rename(path)
    hasher=hashlib.sha256()
    with path.open('rb') as src:
        for chunk in iter(lambda:src.read(1024*1024),b''):hasher.update(chunk)
    assert hasher.hexdigest()==digest
    print('Verified DDK layer',digest,flush=True)
    return path
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: layers=list(pool.map(download,digests))
destination=root/'ddk-root'
destination.mkdir(exist_ok=True)
def ddksafe(member,path):
    name=member.name.lstrip('./')
    if not name.startswith('opt/ddk/') or '..' in pathlib.PurePosixPath(name).parts:return None
    member.name=name
    if member.issym() and member.linkname.startswith('/opt/ddk/'):
        member.linkname=posixpath.relpath(member.linkname.lstrip('/'),posixpath.dirname(name))
    if member.islnk() and member.linkname.startswith('/opt/ddk/'):
        member.linkname=member.linkname.lstrip('/')
    return tarfile.data_filter(member,path)
for path in layers:
    marker=destination/(path.stem+'.complete')
    if not marker.exists():
        with tarfile.open(path) as t:t.extractall(destination,filter=ddksafe)
        marker.touch()
link=pathlib.Path('/opt/ddk')
if not link.exists():link.symlink_to(destination/'opt/ddk',target_is_directory=True)
for directory in (link/'clang/clang-r536225/bin',link/'rust/rust-1.82.0/bin'):
    for p in directory.iterdir():
        if p.is_file():p.chmod(p.stat().st_mode|0o111)
print('DDK ready:',link,flush=True)
