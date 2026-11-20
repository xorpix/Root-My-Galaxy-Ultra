#!/usr/bin/env python3
import contextlib
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

from build_susfs_redirect_fix import warn_missing_module_versions
from check_susfs_bundle import git_hash, tree_hash


class BundleVerificationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.script = self.root / 'tools/check_susfs_bundle.py'
        self.script.parent.mkdir()
        shutil.copyfile(Path(__file__).with_name(self.script.name), self.script)

        base = self.root / 'backends/susfs'
        base.mkdir(parents=True)
        source = b'Synthetic source fixture\n'
        tree = tree_hash({'fixture.txt': ('100644', git_hash('blob', source))})
        parent = '1' * 40
        commit = f'tree {tree}\nparent {parent}\n\nFixture\n'.encode()
        source_commit = git_hash('commit', commit)
        archive = base / 'source.tar.gz'
        with tarfile.open(archive, 'w:gz', format=tarfile.PAX_FORMAT,
                          pax_headers={'comment': source_commit}) as source_tar:
            entry = tarfile.TarInfo('susfs/fixture.txt')
            entry.size = len(source)
            source_tar.addfile(entry, io.BytesIO(source))
        (base / 'source.commit').write_bytes(commit)

        module_path = 'app/src/main/assets/susfs/susfs_guard_lkm.ko'
        self.module = self.root / module_path
        self.module.parent.mkdir(parents=True)
        self.module.write_bytes(b'fixture module')
        module = {'bytes': self.module.stat().st_size,
                  'sha256': hashlib.sha256(self.module.read_bytes()).hexdigest()}
        manifest = {
            'sourceArchive': archive.name,
            'sourceArchiveSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
            'sourceCommit': source_commit, 'sourceParent': parent, 'sourceTree': tree,
            'module': {'path': module_path, **module},
        }
        (base / 'build-manifest.json').write_text(json.dumps(manifest))
        self.metadata = self.module.with_name('bundle.json')
        self.metadata.write_text(json.dumps({
            'schema': 1, 'sourceCommit': source_commit, 'module': {'file': self.module.name, **module},
        }))

    def verify(self, *args):
        return subprocess.run([sys.executable, '-O', str(self.script), *args],
                              capture_output=True, text=True)

    def test_optimized_verifier_checks_module_digest(self):
        valid = self.verify()
        self.assertEqual(valid.returncode, 0, valid.stderr)
        self.module.write_bytes(b'changed module')
        rejected = self.verify()
        self.assertEqual(rejected.returncode, 1, rejected.stdout)
        self.assertIn('module digest', rejected.stderr)

    def test_optimized_verifier_checks_packaged_module(self):
        package = self.root / 'fixture.zip'
        with zipfile.ZipFile(package, 'w') as bundle:
            bundle.writestr('assets/susfs/susfs_guard_lkm.ko', b'changed module')
            bundle.write(self.metadata, 'assets/susfs/bundle.json')
        rejected = self.verify('--apk', str(package))
        self.assertEqual(rejected.returncode, 1, rejected.stdout)
        self.assertIn('APK module', rejected.stderr)


class ModuleVersionWarningTest(unittest.TestCase):
    def test_requires_a_populated_basic_or_complete_extended_table(self):
        cases = [
            ({}, True),
            ({'__versions': 0, '__version_ext_crcs': 0, '__version_ext_names': 0}, True),
            ({'__version_ext_crcs': 4}, True),
            ({'__versions': 64}, False),
            ({'__versions': 0, '__version_ext_crcs': 4, '__version_ext_names': 12}, False),
        ]
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'artifact.json'
            for sections, should_warn in cases:
                with self.subTest(sections=sections):
                    report.write_text(json.dumps({'version_section_sizes': sections}))
                    with contextlib.redirect_stdout(io.StringIO()) as output:
                        warn_missing_module_versions(report)
                    self.assertEqual(output.getvalue().startswith('::warning::'), should_warn)


if __name__ == '__main__':
    unittest.main()
