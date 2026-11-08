#!/usr/bin/env python3
"""Reconstruct, qualify and stage the Samsung modules-hook candidate."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

BASE_APP = '87f3cf3c2f6604a5da9d97a05296334b4eb44688'
BASE_NATIVE = '3a65e96f3694f19abd7feb1f905e4794f48d5aee'
BASE_ARCHIVE_SHA = '19897fca914840014af5162fa2f12bc2c4fad38ad9851a10de0d5a6b7374a098'
BASE_MODULE_SHA = '31e5eb20572c137caf21abc29a08632c8485c3ccd003d298008330b0e7f58a0a'
MODULE_PATH = 'app/src/main/assets/susfs/bzig/susfs_guard_lkm.ko'
DOC = 'docs/susfs-modules-show-20261108'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--prepare-only', action='store_true')
    a = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    work = a.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    source, out = work / 'susfs', work / 'output'
    if source.exists():
        raise SystemExit('Use a fresh --work directory; refusing to reuse a reconstructed source tree')
    out.mkdir(exist_ok=True)
    record = (root / DOC / 'native.commit').read_bytes()
    candidate = hashlib.sha1(b'commit ' + str(len(record)).encode() + b'\0' + record).hexdigest()
    tree = record.splitlines()[0].decode().removeprefix('tree ')
    assert record.splitlines()[1] == ('parent ' + BASE_NATIVE).encode(), 'unexpected native parent'
    manifest = json.loads((root / 'backends/susfs/build-manifest.json').read_text())
    assert manifest['sourceCommit'] in (BASE_NATIVE, candidate), 'unreviewed source baseline'
    subprocess.run(['python3', str(root / 'tools/check_susfs_bundle.py')], check=True)

    def baseline(path):
        if manifest['sourceCommit'] == BASE_NATIVE:
            return (root / path).read_bytes()
        return subprocess.check_output(['git', 'show', BASE_APP + ':' + path], cwd=root)

    archive_data = baseline('backends/susfs/source.tar.gz')
    module_data = baseline(MODULE_PATH)
    assert digest(archive_data) == BASE_ARCHIVE_SHA, 'baseline archive digest'
    assert digest(module_data) == BASE_MODULE_SHA, 'baseline module digest'
    base_archive = out / 'baseline-source.tar.gz'
    base_archive.write_bytes(archive_data)
    with tarfile.open(base_archive) as archive:
        archive.extractall(work, filter='data')
    base_archive.unlink()

    def git(*args, **kwargs):
        return subprocess.check_output(['git', *args], cwd=source, **kwargs)

    git('init', '-q')
    git('config', 'core.autocrlf', 'false')
    git('add', '-f', '--', '.')
    assert git('write-tree').decode().strip() == 'db1648df75d104249d29fc4533912e4ba4722561', 'baseline tree'
    git('hash-object', '-t', 'commit', '-w', '--stdin', input=baseline('backends/susfs/source.commit'))
    patch = root / DOC / 'native.patch'
    git('apply', '--check', str(patch))
    git('apply', str(patch))
    git('add', '-f', '--', '.')
    assert git('write-tree').decode().strip() == tree, 'candidate tree differs from reviewed patch'
    assert git('hash-object', '-t', 'commit', '-w', '--stdin', input=record).decode().strip() == candidate
    git('update-ref', 'refs/heads/candidate', candidate)
    receipt = {'schema': 1, 'baseAppCommit': BASE_APP, 'sourceParent': BASE_NATIVE,
               'sourceCommit': candidate, 'sourceTree': tree,
               'patchSha256': digest(patch.read_bytes()), 'hardwareValidated': False}
    (out / 'source.json').write_text(json.dumps(receipt, indent=2) + '\n')
    if a.prepare_only:
        print('Reconstructed reviewed candidate ' + candidate)
        return

    clang = Path('/opt/ddk/clang/clang-r536225/bin')
    kdir = Path('/opt/ddk/kdir/android16-6.12')
    subprocess.run(['python3', str(source / 'tools/build_review.py'), '--kdir', str(kdir),
                    '--clang-bin', str(clang), '--rust-bin', '/opt/ddk/rust/rust-1.82.0/bin',
                    '--evidence-dir', str(out / 'build'), '--omit-module-btf'], check=True)
    module = out / 'susfs_guard_lkm.ko'
    shutil.copy2(source / 'kernel/susfs_guard_lkm.ko', module)
    subprocess.run([str(clang / 'llvm-strip'), '-d', str(module)], check=True)
    original = out / 'baseline.ko'
    original.write_bytes(module_data)
    subprocess.run(['python3', str(source / 'tools/check_priority_artifact.py'),
                    '--baseline-is-priority', '--candidate', str(module), '--baseline', str(original),
                    '--kdir', str(kdir), '--clang-bin', str(clang), '--output', str(out / 'abi')], check=True)
    original.unlink()
    module_info = {'path': MODULE_PATH, 'bytes': module.stat().st_size, 'sha256': digest(module.read_bytes())}
    receipt['module'] = module_info
    receipt['workflowRun'] = os.environ.get('GITHUB_RUN_ID')
    bundle = out / 'bundle'

    def write(path, data):
        dest = bundle / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)

    source_archive = out / 'source.tar.gz'
    with source_archive.open('wb') as raw:
        with gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as compressed:
            compressed.write(git('archive', '--format=tar', '--prefix=susfs/', candidate))
    archive_sha = digest(source_archive.read_bytes())
    receipt['sourceArchiveSha256'] = archive_sha
    write(MODULE_PATH, module.read_bytes())
    write('backends/susfs/source.tar.gz', source_archive.read_bytes())
    write('backends/susfs/source.commit', record)
    manifest.update(sourceCommit=candidate, sourceTree=tree, sourceParent=BASE_NATIVE,
                    sourceArchiveSha256=archive_sha, module=module_info)
    # Historical phone receipts remain identified as baseline evidence; never
    # inherit their qualification for this newly compiled module.
    manifest['validation'] = {
        'hardwareValidated': False,
        'acceptedHardwareBaseline': {'sourceCommit': BASE_NATIVE, 'moduleSha256': BASE_MODULE_SHA,
                                     'result': 'Maintainer confirmed release 1.3.4 on the phone on 2026-11-03.'},
        'modulesShowHardening': DOC + '/README.md',
        'nativeQualificationRun': os.environ.get('GITHUB_RUN_ID'),
        'kernelImports': 140, 'syscallWrappers': 22,
        'deviceValidation': 'Pending: candidate must be phone-tested before stable publication.'}
    current_validation = json.loads((root / 'backends/susfs/build-manifest.json').read_text())
    if (current_validation['sourceCommit'] == candidate and
            current_validation['module'] == module_info and
            current_validation.get('validation', {}).get('hardwareValidated')):
        assert current_validation['sourceArchiveSha256'] == archive_sha, 'validated archive changed'
        phone = json.loads((root / DOC / 'phone-validation.json').read_text())
        assert phone['result'] == 'passed' and phone['sourceCommit'] == candidate
        assert phone['moduleSha256'] == module_info['sha256'], 'phone-tested module changed'
        manifest['validation'] = dict(current_validation['validation'])
        manifest['validation']['nativeQualificationRun'] = os.environ.get('GITHUB_RUN_ID')
    write('backends/susfs/build-manifest.json', (json.dumps(manifest, indent=2) + '\n').encode())
    for path in sorted((root / 'app/src/main/assets/susfs').glob('*/profile.json')):
        profile = json.loads(path.read_text())
        profile['sourceCommit'] = candidate
        profile['module']['sha256'] = module_info['sha256']
        profile['module']['bytes'] = module_info['bytes']
        write(path.relative_to(root), (json.dumps(profile, indent=2) + '\n').encode())
    notice_path = 'app/src/main/assets/susfs/bzig/NOTICE.txt'
    notice = (root / notice_path).read_text().splitlines()
    notice = [('Current Samsung source ' + candidate + '.')
              if line.startswith('Current Samsung source ') else line
              for line in notice if not line.startswith('Modules callback hardening adapted ')]
    notice.append('Modules callback hardening adapted from upstream PR #200; see ' + DOC + '/README.md.')
    write(notice_path, ('\n'.join(notice) + '\n').encode())
    write(DOC + '/build-receipt.json', (json.dumps(receipt, indent=2) + '\n').encode())
    write(DOC + '/native-build.json', (out / 'build/build.json').read_bytes())
    write(DOC + '/abi.json', (out / 'abi/artifact.json').read_bytes())
    current = json.loads((root / 'backends/susfs/build-manifest.json').read_text())
    if current['sourceCommit'] == candidate:
        assert current['sourceArchiveSha256'] == archive_sha, 'rebuilt archive differs from bundle'
        assert current['module'] == module_info, 'rebuilt module differs from bundle'
    print(json.dumps(receipt, indent=2))

if __name__ == '__main__':
    main()
