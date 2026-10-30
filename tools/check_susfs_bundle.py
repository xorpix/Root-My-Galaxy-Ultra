#!/usr/bin/env python3
"""Verify the bundled Samsung module, firmware profiles and complete source provenance."""
import argparse,gzip,hashlib,json,tarfile,zipfile
from pathlib import Path,PurePosixPath
ROOT=Path(__file__).resolve().parents[1]
def git_hash(kind,data):
    return hashlib.sha1(kind.encode()+b' '+str(len(data)).encode()+b'\0'+data).hexdigest()
def tree_hash(node):
    entries=[]
    for name,value in node.items():
        mode,digest=('40000',tree_hash(value)) if isinstance(value,dict) else value
        entries.append((name,mode,digest))
    entries.sort(key=lambda e:e[0].encode()+(b'/' if e[1]=='40000' else b''))
    data=b''.join(mode.encode()+b' '+name.encode()+b'\0'+bytes.fromhex(digest) for name,mode,digest in entries)
    return git_hash('tree',data)
def verify(apk=None):
    base=ROOT/'backends/susfs';manifest=json.loads((base/'build-manifest.json').read_text())
    archive=base/manifest['sourceArchive']
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==manifest['sourceArchiveSha256'],'source archive digest'
    commit=(base/'source.commit').read_bytes()
    assert git_hash('commit',commit)==manifest['sourceCommit'],'source commit digest'
    assert commit.startswith(('tree '+manifest['sourceTree']+'\nparent '+manifest['sourceParent']+'\n').encode()),'source ancestry'
    tree={}
    with tarfile.open(archive,'r:gz') as tar:
        assert tar.pax_headers.get('comment')==manifest['sourceCommit'],'git archive commit'
        for item in tar:
            parts=PurePosixPath(item.name).parts
            assert parts and parts[0]=='susfs' and '..' not in parts,'unsafe archive path'
            if item.isdir():continue
            assert item.isfile() or item.issym(),'unexpected source entry'
            data=tar.extractfile(item).read() if item.isfile() else item.linkname.encode()
            mode='120000' if item.issym() else ('100755' if item.mode&0o111 else '100644')
            node=tree
            for part in parts[1:-1]:node=node.setdefault(part,{})
            assert parts[-1] not in node,'duplicate archive member'
            node[parts[-1]]=(mode,git_hash('blob',data))
    assert tree_hash(tree)==manifest['sourceTree'],'source tree digest'
    module=manifest['module'];data=(ROOT/module['path']).read_bytes()
    assert len(data)==module['bytes'] and hashlib.sha256(data).hexdigest()==module['sha256'],'module digest'
    profiles=list((ROOT/'app/src/main/assets/susfs').glob('*/profile.json'));assert profiles
    for path in profiles:
        p=json.loads(path.read_text())
        assert p['sourceCommit']==manifest['sourceCommit'] and p['module']['sha256']==module['sha256'],str(path)
    if apk:
        with zipfile.ZipFile(apk) as z:
            assert z.read('assets/susfs/bzig/susfs_guard_lkm.ko')==data,'APK module'
            for path in profiles:
                name='assets/'+path.relative_to(ROOT/'app/src/main/assets').as_posix()
                assert json.loads(z.read(name))==json.loads(path.read_text()),name
    print('PASS SusFS module, all firmware profiles, source archive, Git tree and commit provenance'+(' and APK payload' if apk else ''))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apk',type=Path);a=p.parse_args();verify(a.apk)
