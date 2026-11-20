#!/usr/bin/env python3
"""Verify the bundled Samsung module, shared bundle metadata and complete source provenance."""
import argparse
import hashlib
import json
import tarfile
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]

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

def require(condition, reason):
    if not condition:
        raise SystemExit(f'SusFS bundle verification failed ({reason})')


def verify(apk=None):
    base = ROOT / 'backends/susfs'
    manifest = json.loads((base / 'build-manifest.json').read_text())
    archive = base / manifest['sourceArchive']
    require(hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['sourceArchiveSha256'],
            'source archive digest')
    commit = (base / 'source.commit').read_bytes()
    require(git_hash('commit', commit) == manifest['sourceCommit'], 'source commit digest')
    ancestry = f"tree {manifest['sourceTree']}\nparent {manifest['sourceParent']}\n".encode()
    require(commit.startswith(ancestry), 'source ancestry')

    tree = {}
    with tarfile.open(archive, 'r:gz') as tar:
        require(tar.pax_headers.get('comment') == manifest['sourceCommit'], 'git archive commit')
        for item in tar:
            parts = PurePosixPath(item.name).parts
            require(parts and parts[0] == 'susfs' and '..' not in parts, 'unsafe archive path')
            if item.isdir():
                continue
            require(item.isfile() or item.issym(), 'unexpected source entry')
            data = tar.extractfile(item).read() if item.isfile() else item.linkname.encode()
            mode = '120000' if item.issym() else ('100755' if item.mode & 0o111 else '100644')
            node = tree
            for part in parts[1:-1]:
                node = node.setdefault(part, {})
            require(parts[-1] not in node, 'duplicate archive member')
            node[parts[-1]] = (mode, git_hash('blob', data))
    require(tree_hash(tree) == manifest['sourceTree'], 'source tree digest')

    module = manifest['module']
    module_data = (ROOT / module['path']).read_bytes()
    require(len(module_data) == module['bytes'] and hashlib.sha256(module_data).hexdigest() == module['sha256'],
            'module digest')
    helpers = {}
    for key, label in (('priorityControl', 'priority control'), ('loader', 'loader'), ('status', 'status')):
        helper = manifest.get(key)
        if not helper:
            continue
        payload = (ROOT / helper['path']).read_bytes()
        require(len(payload) == helper['bytes'] and hashlib.sha256(payload).hexdigest() == helper['sha256'],
                f'{label} digest')
        require(payload[:4] == b'\x7fELF' and int.from_bytes(payload[18:20], 'little') == 183,
                f'{label} ARM64 ELF')
        helpers[key] = (Path(helper['path']).name, helper['sha256'], payload, label)

    metadata_path = ROOT / 'app/src/main/assets/susfs/bundle.json'
    metadata = json.loads(metadata_path.read_text())
    require(metadata['schema'] == 1 and metadata['sourceCommit'] == manifest['sourceCommit'], 'bundle source commit')
    require(metadata['module'] == {
        'file': Path(module['path']).name, 'sha256': module['sha256'], 'bytes': module['bytes'],
    }, 'bundle module')
    require(not any(key in metadata for key in ('model', 'firmware', 'kernelRelease')),
            'firmware-specific bundle gate')
    for key, (filename, digest, _, label) in helpers.items():
        require(metadata.get(key) == {'file': filename, 'sha256': digest}, f'bundle {label}')

    if apk:
        with zipfile.ZipFile(apk) as package:
            require(package.read('assets/susfs/susfs_guard_lkm.ko') == module_data, 'APK module')
            for filename, _, payload, label in helpers.values():
                require(package.read('lib/arm64-v8a/' + filename) == payload, f'APK {label}')
            require(json.loads(package.read('assets/susfs/bundle.json')) == metadata, 'APK shared bundle metadata')
    print('PASS SusFS module, shared bundle metadata, source archive, Git tree and commit provenance'
          + (' and APK payload' if apk else ''))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    verify(args.apk)

