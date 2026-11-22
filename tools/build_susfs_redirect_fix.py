#!/usr/bin/env python3
"""Reconstruct and qualify the reviewed Samsung SusFS maintenance source."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

BASE_APP = '29fdc93d3b2516a1f4bbe73b3a0576fc492c8657'
BASE_NATIVE = '3ebe7cf0cb08108856e79fdcdbfe4b3c2bd3a3a1'
BASE_ARCHIVE_SHA = '9e6b1088cec1423c5968f53b5f4788f00951f4102f98ad962b32166aee9c1f2b'
BASE_MODULE_SHA = '421283dcb5376dd8fb594479fa0b876f4f7b419b35bb0a722108da05081c5e5e'
BASE_MODULE_PATH = 'app/src/main/assets/susfs/susfs_guard_lkm.ko'
MODULE_PATH = 'app/src/main/assets/susfs/susfs_guard_lkm.ko'
DOC = 'docs/susfs-maintenance-20261122'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def warn_missing_module_versions(report):
    sections = json.loads(report.read_text())['version_section_sizes']
    has_basic_versions = sections.get('__versions', 0) > 0
    has_extended_versions = sections.get('__version_ext_crcs', 0) > 0 and sections.get('__version_ext_names', 0) > 0
    if not (has_basic_versions or has_extended_versions):
        print('::warning::SusFS module has no populated symbol version table.')

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
    assert git('write-tree').decode().strip() == 'a1a9862d946cbb7ba4537c4dee020ba7f488ee85', 'baseline tree'
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
               'patchSha256': digest(patch.read_bytes()), 'hardwareValidated': False, 'maintenanceReview': DOC + '/README.md'}
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
    abi = out / 'abi'
    subprocess.run(['python3', str(source / 'tools/check_priority_artifact.py'),
                    '--baseline-is-priority', '--candidate', str(module), '--baseline', str(original),
                    '--kdir', str(kdir), '--clang-bin', str(clang), '--output', str(abi)], check=True)
    warn_missing_module_versions(abi / 'artifact.json')
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
        'maintenanceReview': DOC + '/README.md',
        'kernelImports': 140, 'syscallWrappers': 27,
        'nativeQualificationRun': os.environ.get('GITHUB_RUN_ID'),
        'deviceValidation': 'Host regressions and ARM64 compilation qualify the maintenance changes. This module has no separate phone or long-uptime validation.'}
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
    (out / 'source.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))

if __name__ == '__main__':
    main()
