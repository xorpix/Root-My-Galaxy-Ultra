#!/usr/bin/env python3
"""Host-only migration/guard checks. Never build or execute ARM64 payloads."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import refresh
import stage

PROJECT = Path(__file__).resolve().parents[2]


class RefreshTests(unittest.TestCase):
    def test_compatibility_patch_checksums_and_no_uapi_override(self):
        for name, target in stage.CONFIG['backends'].items():
            patch = (stage.HERE / f'{name}-compat.patch').read_bytes()
            self.assertEqual(target['compat_sha256'], hashlib.sha256(patch).hexdigest())
            self.assertNotIn(b'diff --git a/uapi/', patch)

    def test_missing_native_build_cannot_replace_runtime_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            subprocess.run(['git', 'clone', '--quiet', '--shared', str(PROJECT), str(source)], check=True)
            before = (source / stage.ASSETS / 'azhl/kernelsu/ksud').read_bytes()
            with self.assertRaises(subprocess.CalledProcessError):
                stage.main(source, root / 'no-build')
            self.assertEqual(before, (source / stage.ASSETS / 'azhl/kernelsu/ksud').read_bytes())
            self.assertEqual(b'', stage.git(source, 'status', '--porcelain'))

    def test_old_daemon_is_not_accepted_as_new_revision(self):
        name = 'kernelsu'
        previous = json.loads((stage.HERE / f'{name}-previous.json').read_text())
        daemon = (PROJECT / stage.ASSETS / 'azhl' / name / 'ksud').read_bytes()
        module = stage.extract_driver(daemon, previous['outputs']['kernelsu.ko'])
        # Positive control uses the existing, known pair. It is not a new build.
        old_target = dict(stage.CONFIG['backends'][name], daemon_version=previous['daemon_version'])
        stage.verify_pair(daemon, module, old_target)
        with self.assertRaisesRegex(ValueError, 'Daemon version'):
            stage.verify_pair(daemon, module, stage.CONFIG['backends'][name])
        with self.assertRaises(ValueError):
            stage.verify_pair(daemon, module[:-1], old_target)

    def test_bridge_edit_preserves_all_other_bytes(self):
        original = (PROJECT / stage.BRIDGE).read_bytes()
        result = stage.bridge_with_baka_package(original)
        old = b'com.resukisu.resukisu'
        offset = original.index(old)
        self.assertEqual(len(original), len(result))
        self.assertEqual(original[:offset], result[:offset])
        self.assertEqual(original[offset + len(old):], result[offset + len(old):])
        self.assertEqual(b'org.bakasu.bakasu'.ljust(len(old), b' '), result[offset:offset + len(old)])
        with self.assertRaises(ValueError):
            stage.bridge_with_baka_package(result)

    def test_manager_migration_on_source_fixture(self):
        paths = [stage.JAVA / 'KernelSuFlavor.kt', stage.JAVA / 'KernelSuManager.kt',
                 Path('app/src/main/AndroidManifest.xml'), Path('native/azhl/src/azhl_backend.h'),
                 Path('build-df-variants.sh'), Path('app/src/main/cpp/dirtyfrag/DF_ORIGIN.md'),
                 Path('app/src/main/res/values/strings.xml'), stage.BRIDGE,
                 Path('app/src/test/java/dev/busung/s25uroot/KernelSuFlavorTest.kt'),
                 Path('app/src/test/java/dev/busung/s25uroot/KernelSuManagerTest.kt')]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            for path in paths:
                (source / path).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(PROJECT / path, source / path)
            stage.apply_branding(source)
            flavor = (source / stage.JAVA / 'KernelSuFlavor.kt').read_text()
            self.assertIn('id = "resukisu"', flavor)
            self.assertIn('managerPackage = "org.bakasu.bakasu"', flavor)
            self.assertIn('repository = "Baka-SU/BakaSU"', flavor)
            self.assertNotIn('35171-universal-release.apk', flavor)
            manager = (source / stage.JAVA / 'KernelSuManager.kt').read_text()
            self.assertIn('"com.resukisu.resukisu"', manager)
            self.assertIn('"bakasu" in words', manager)


if __name__ == '__main__':
    unittest.main(verbosity=2)
