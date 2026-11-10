#!/usr/bin/env python3
"""Reconstruct and qualify the production protected-table redirect fallback."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

BASE_APP = 'c6bb0d48ab4665f2cc61387fcfdb3069a85cf6ea'
BASE_NATIVE = '177aa8907a8ee7d7c156c1330cbab1d55515c5e4'
BASE_ARCHIVE_SHA = '86bcacc3dfa0e6095b362307332b0d6b202d6d97575b5a49f545442f1186fb61'
BASE_MODULE_SHA = '876042df9c6b2450f87a67b7f25323957a82c5acd89427aa53c0821545aa7c36'
BASE_MODULE_PATH = 'app/src/main/assets/susfs/bzig/susfs_guard_lkm.ko'
MODULE_PATH = 'app/src/main/assets/susfs/susfs_guard_lkm.ko'
DOC = 'docs/susfs-redirect-fix-20261110'

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
    module_data = baseline(BASE_MODULE_PATH)
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
    assert git('write-tree').decode().strip() == 'd45f899fbe41b37678ba5b7dbf36f07c5d0af2fb', 'baseline tree'
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
               'patchSha256': digest(patch.read_bytes()), 'hardwareValidated': False, 'productionHotfix': True}
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
        **manifest.get('validation', {}),
        'hardwareValidated': False,
        'redirectEntryFallback': DOC + '/README.md',
        'acceptedHardwareCandidate': json.loads((root / DOC / 'phone-validation.json').read_text()),
        'kernelImports': 140, 'syscallWrappers': 27,
        'nativeQualificationRun': os.environ.get('GITHUB_RUN_ID'),
        'deviceValidation': 'Maintainer confirmed the signed fallback candidate starts SusFS with all five redirect entries. This production binary removes only the temporary startup receipt and its state; it has no separate phone test. Redirect rule execution and long-uptime behavior were not independently measured.'}
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
    bundle_path = 'app/src/main/assets/susfs/bundle.json'
    metadata = json.loads((root / bundle_path).read_text())
    metadata['sourceCommit'] = candidate
    metadata['module']['sha256'] = module_info['sha256']
    metadata['module']['bytes'] = module_info['bytes']
    write(bundle_path, (json.dumps(metadata, indent=2) + '\n').encode())
    notice_path = 'app/src/main/assets/susfs/NOTICE.txt'
    notice = (root / notice_path).read_text().splitlines()
    notice = [('Current Samsung source ' + candidate + '.')
              if line.startswith('Current Samsung source ') else line
              for line in notice]
    write(notice_path, ('\n'.join(notice) + '\n').encode())
    current = json.loads((root / 'backends/susfs/build-manifest.json').read_text())
    if current['sourceCommit'] == candidate:
        assert current['sourceArchiveSha256'] == archive_sha, 'rebuilt archive differs from bundle'
        assert current['module'] == module_info, 'rebuilt module differs from bundle'
    print(json.dumps(receipt, indent=2))

if __name__ == '__main__':
    main()
