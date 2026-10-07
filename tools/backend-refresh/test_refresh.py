#!/usr/bin/env python3
"""Host-only checks for recurring builds and release gates. Never execute ARM64 payloads."""
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import common
import probe
import refresh
import release
import stage

PROJECT = common.PROJECT


def clone_source(directory):
    source = Path(directory) / 'source'
    subprocess.run(['git', 'clone', '--quiet', '--shared', str(PROJECT), str(source)], check=True)
    # Source-only release fixtures use the patches that produced their bundled
    # binaries. A pending reviewed template update needs a real native rebuild.
    config = common.load_config(source / 'tools/backend-refresh/targets.json')
    manifests = common.baseline(source)
    for name, target in config['backends'].items():
        provenance = manifests[name]['rebased_patch']
        (source / 'tools/backend-refresh' / f'{name}-compat.patch').write_bytes(
            (source / 'backends' / name / provenance['path']).read_bytes())
        target['compat_sha256'] = provenance['sha256']
    common.write_json(source / 'tools/backend-refresh/targets.json', config)
    if common.capture(['git', 'status', '--porcelain'], source):
        subprocess.run(['git', 'config', 'user.email', 'validation@example.invalid'], cwd=source, check=True)
        subprocess.run(['git', 'config', 'user.name', 'Local Validation'], cwd=source, check=True)
        subprocess.run(['git', 'add', 'tools/backend-refresh'], cwd=source, check=True)
        subprocess.run(['git', 'commit', '--quiet', '-m', 'Align source-only fixture with built patches'],
                       cwd=source, check=True)
    return source


def plan_for(source):
    config = common.load_config(source / 'tools/backend-refresh/targets.json')
    config['app_base'] = common.capture(['git', 'rev-parse', 'HEAD'], source)
    return config


class RefreshTests(unittest.TestCase):
    def snapshot(self, changed_heads=(), changed_patches=()):
        config = common.load_config()
        manifests = common.baseline(PROJECT)
        for name, target in config['backends'].items():
            target['compat_sha256'] = manifests[name]['rebased_patch']['sha256']
            if name in changed_patches:
                target['compat_sha256'] = 'f' * 64
        replies = {'https://github.com/' + t['repository'] + '.git':
                   ('a' * 40 if name in changed_heads else t['commit'])
                   for name, t in config['backends'].items()}
        # Isolate rebuild policy; the independent provenance tests validate the
        # actual bundle and checksummed template files.
        with patch.object(probe, 'load_config', return_value=config), \
                patch.object(probe, 'baseline', return_value=manifests):
            return probe.probe(PROJECT, lambda url, ref: replies[url] + '\t' + ref)

    def test_complete_current_provenance_matches_runtime(self):
        manifests = common.baseline(PROJECT)
        for name, target in common.load_config()['backends'].items():
            daemon = (PROJECT / common.ASSETS / 'azhl' / name / 'ksud').read_bytes()
            module = stage.extract_driver(daemon, manifests[name]['outputs']['kernelsu.ko'])
            stage.verify_pair(daemon, module, target)

    def test_no_changes_skips_all_backends(self):
        snapshot = self.snapshot()
        self.assertEqual([], snapshot['changed'])

    def test_one_changed_backend_is_detected(self):
        snapshot = self.snapshot(changed_heads=['kernelsu-next'])
        self.assertEqual(['kernelsu-next'], snapshot['changed'])

    def test_reviewed_patch_update_rebuilds_without_an_upstream_change(self):
        snapshot = self.snapshot(changed_patches=['kernelsu'])
        self.assertEqual(['kernelsu'], snapshot['changed'])
        self.assertEqual(common.load_config()['backends']['kernelsu']['commit'],
                         snapshot['heads']['kernelsu'])

    def test_changed_patch_cannot_reuse_old_native_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            source = clone_source(directory)
            config = plan_for(source)
            path = source / 'tools/backend-refresh/kernelsu-compat.patch'
            path.write_bytes(path.read_bytes() + b'\n')
            config['backends']['kernelsu']['compat_sha256'] = common.record(path.read_bytes())['sha256']
            common.write_json(source / 'tools/backend-refresh/targets.json', config)
            subprocess.run(['git', 'config', 'user.email', 'validation@example.invalid'], cwd=source, check=True)
            subprocess.run(['git', 'config', 'user.name', 'Local Validation'], cwd=source, check=True)
            subprocess.run(['git', 'add', 'tools/backend-refresh'], cwd=source, check=True)
            subprocess.run(['git', 'commit', '--quiet', '-m', 'Reviewed patch update fixture'], cwd=source, check=True)
            config = plan_for(source)
            before = (source / common.ASSETS / 'azhl/kernelsu/ksud').read_bytes()
            with self.assertRaisesRegex(ValueError, 'requires a native rebuild'):
                stage.main(source, Path(directory), config)
            self.assertEqual(before, (source / common.ASSETS / 'azhl/kernelsu/ksud').read_bytes())

    def test_version_skips_existing_and_later_numeric_tags(self):
        self.assertEqual('1.1.9', common.next_version('1.1.5', ['1.1.6', 'v1.1.8', 'ci-1.1.99']))
        self.assertEqual('1.2.1', common.next_version('1.2.0', ['1.1.99']))

    def test_new_uapi_stops_before_native_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'uapi').mkdir()
            (root / 'uapi/supercall.h').write_text('static const __u32 KERNEL_SU_UAPI_VERSION = 6;')
            with patch.object(refresh, 'capture', return_value='false'):
                with self.assertRaisesRegex(ValueError, 'new UAPI'):
                    refresh.verify_upstream(root, 'kernelsu', common.load_config()['backends']['kernelsu'])

    def test_incomplete_native_build_cannot_replace_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            source = clone_source(directory)
            config = plan_for(source)
            config['backends']['kernelsu']['rebuild'] = True
            before = (source / common.ASSETS / 'azhl/kernelsu/ksud').read_bytes()
            with self.assertRaises(subprocess.CalledProcessError):
                stage.main(source, Path(directory) / 'missing-build', config)
            self.assertEqual(before, (source / common.ASSETS / 'azhl/kernelsu/ksud').read_bytes())
            self.assertEqual('', common.capture(['git', 'status', '--porcelain'], source))

    def test_wrong_daemon_revision_is_rejected(self):
        manifest = common.baseline(PROJECT)['kernelsu']
        target = common.load_config()['backends']['kernelsu']
        daemon = (PROJECT / common.ASSETS / 'azhl/kernelsu/ksud').read_bytes()
        module = stage.extract_driver(daemon, manifest['outputs']['kernelsu.ko'])
        with self.assertRaisesRegex(ValueError, 'Daemon version'):
            stage.verify_pair(daemon, module, dict(target, daemon_version='unbuilt-revision'))
        with self.assertRaises(ValueError):
            stage.verify_pair(daemon, module[:-1], target)

    def test_modified_baseline_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = clone_source(directory)
            path = source / common.ASSETS / 'azhl/kernelsu/ksud'
            path.write_bytes(path.read_bytes() + b'changed')
            with self.assertRaisesRegex(ValueError, 'Bundled daemon differs'):
                common.baseline(source, clean=False)

    def test_unchanged_staging_is_repeatable_without_branding_or_exploit_edits(self):
        with tempfile.TemporaryDirectory() as directory:
            source = clone_source(directory)
            protected = source / 'app/src/main/cpp/dirtyfrag/ko/dirtyfrag-android16-6.12-resukisu.ko'
            before = protected.read_bytes()
            config = plan_for(source)
            stage.main(source, Path(directory), config)
            common.baseline(source, clean=False)
            subprocess.run(['git', 'config', 'user.email', 'validation@example.invalid'], cwd=source, check=True)
            subprocess.run(['git', 'config', 'user.name', 'Local Validation'], cwd=source, check=True)
            subprocess.run(['git', 'add', '.'], cwd=source, check=True)
            subprocess.run(['git', 'commit', '--quiet', '--allow-empty', '-m', 'Validation fixture'], cwd=source, check=True)
            config = plan_for(source)
            stage.main(source, Path(directory), config)
            self.assertEqual(before, protected.read_bytes())
            common.baseline(source, clean=False)

    def test_artifact_cannot_modify_release_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = clone_source(root)
            result = root / 'result'
            result.mkdir()
            info = {'schema': 1, 'base_commit': common.capture(['git', 'rev-parse', 'HEAD'], source),
                    'version': '1.1.999', 'targets': common.load_config()}
            common.write_json(result / 'release.json', info)
            path = source / 'tools/backend-refresh/release.py'
            original = path.read_bytes()
            path.write_bytes(original + b'\n# Unexpected artifact edit\n')
            patch_data = subprocess.check_output(['git', 'diff', '--binary'], cwd=source)
            path.write_bytes(original)
            (result / 'apply-matched-backends.patch').write_bytes(patch_data)
            with patch.object(release, 'PROJECT', source):
                with self.assertRaisesRegex(ValueError, 'unexpected files'):
                    release.apply_checked_update(result)
            self.assertEqual('', common.capture(['git', 'status', '--porcelain'], source))

    def test_valid_staged_source_update_passes_the_signing_gate(self):
        # Source/bundle gate only: this fixture is not an Android APK/build result.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            work = root / 'work'
            work.mkdir()
            (work / 'logs').mkdir()
            source = clone_source(work)
            config = plan_for(source)
            stage.main(source, work, config)
            refresh.prepare_release(source, work, config)
            publisher = root / 'publisher'
            subprocess.run(['git', 'clone', '--quiet', '--shared', str(source), str(publisher)], check=True)
            with patch.object(refresh, 'PROJECT', publisher):
                refresh.package_result(source, work)
            result = work / 'result'
            (result / 'apk-input.apk').write_bytes(b'APK validation is mocked for this source-only fixture')
            info = release.metadata(result)
            info.update(apk_input=common.record((result / 'apk-input.apk').read_bytes()), apk_version_code=123)
            common.write_json(result / 'release.json', info)
            with patch.object(release, 'PROJECT', publisher), patch.object(release, 'verify_apk', return_value=123):
                checked = release.apply_checked_update(result)
            self.assertEqual(info['version'], checked['version'])
            self.assertEqual(info['version'], common.app_version(publisher))

    def test_artifact_cannot_weaken_control_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = clone_source(root)
            result = root / 'result'
            result.mkdir()
            info = {'schema': 1, 'base_commit': common.capture(['git', 'rev-parse', 'HEAD'], source),
                    'version': '1.1.999', 'targets': common.load_config()}
            common.write_json(result / 'release.json', info)
            path = source / common.JAVA / 'M3qLaunch.kt'
            original = path.read_bytes()
            path.write_bytes(original + b'\n// Unexpected control edit\n')
            patch_data = subprocess.check_output(['git', 'diff', '--binary'], cwd=source)
            path.write_bytes(original)
            (result / 'apply-matched-backends.patch').write_bytes(patch_data)
            with patch.object(release, 'PROJECT', source):
                with self.assertRaisesRegex(ValueError, 'control/build logic change'):
                    release.apply_checked_update(result)


if __name__ == '__main__':
    unittest.main(verbosity=2)
