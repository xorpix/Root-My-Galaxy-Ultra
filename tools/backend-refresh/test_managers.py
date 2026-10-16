#!/usr/bin/env python3
"""Checks for manager provenance, incompatible APKs and upstream availability failures."""
import copy
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import tempfile
import unittest
import zipfile

import common
import managers

NOW = datetime(2026, 10, 12, tzinfo=timezone.utc)


def library(package, uapi=5):
    data = bytearray(1024)
    data[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, 183, 1, 0, 64, 768, 0,
                     64, 56, 1, 64, 4, 0)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0x1000, 0x1000, len(data), len(data), 4096)
    struct.pack_into('<II', data, 128, 0x52800000 | uapi << 5, 0xd65f03c0)
    names = b'\0Java_' + package.encode() + b'_Natives_getManagerUAPIVersion\0'
    data[256:256 + len(names)] = names
    struct.pack_into('<IBBHQQ', data, 184, 1, 0x12, 0, 1, 0x1080, 8)
    struct.pack_into('<IIQQQQIIQQ', data, 832, 0, 1, 6, 0x1080, 128, 8, 0, 0, 4, 0)
    struct.pack_into('<IIQQQQIIQQ', data, 896, 0, 11, 2, 0x10a0, 160, 48, 3, 1, 8, 24)
    struct.pack_into('<IIQQQQIIQQ', data, 960, 0, 3, 2, 0x1100, 256, len(names), 0, 0, 1, 0)
    return bytes(data)


def bundle(name, version, uapi=5, debug=False, package=None):
    inner, outer = io.BytesIO(), io.BytesIO()
    with zipfile.ZipFile(inner, 'w') as apk:
        apk.writestr('lib/arm64-v8a/libkernelsu.so', library(package or managers.MANAGERS[name][3], uapi))
    prefix = {'kernelsu': 'KernelSU', 'kernelsu-next': 'KernelSU_Next', 'resukisu': 'BakaSU'}[name]
    abi = '-arm64-v8a' if name == 'resukisu' else ''
    filename = f'{prefix}_v1.0.0_{version}{abi}-' + ('debug' if debug else 'release') + '.apk'
    with zipfile.ZipFile(outer, 'w') as archive:
        archive.writestr(filename, inner.getvalue())
    return outer.getvalue()


class FakeGitHub:
    def __init__(self, config, readme):
        self.targets = config['backends']
        self.failed = set()
        self.runs, self.artifacts, self.archives = {}, {}, {}
        self.downloads = []
        self.latest_available = True
        for number, (name, target) in enumerate(self.targets.items(), 100):
            repo = target['repository']
            old = re.search(re.escape(repo) + r'/actions/runs/(\d+)', readme)
            for run_id, commit, version in [(number, target['commit'], target['version']),
                                            (int(old[1]) if old else number + 10000, 'b' * 40, target['version'] - 1),
                                            (60000000000 + number, 'c' * 40, target['version'] + 1)]:
                run = {'id': run_id, 'status': 'completed', 'conclusion': 'success',
                       'event': 'push', 'head_branch': target['branch'], 'head_sha': commit,
                       'repository': {'full_name': repo}, 'head_repository': {'full_name': repo},
                       'path': '.github/workflows/' + managers.MANAGERS[name][1]}
                self.runs[run_id] = run
                data = bundle(name, version, uapi=target['uapi'])
                artifact_name = managers.MANAGERS[name][2]
                self.artifacts[run_id] = {'id': run_id + 1000, 'name': artifact_name,
                                         'expired': False, 'size_in_bytes': len(data),
                                         'expires_at': '2027-01-10T12:00:00Z',
                                         'digest': 'sha256:' + hashlib.sha256(data).hexdigest()}
                self.archives[run_id] = data

    def json(self, path):
        name = next(n for n, t in self.targets.items() if t['repository'] in path)
        if name in self.failed:
            raise OSError('Temporary upstream outage')
        if '/workflows/' in path:
            target = self.targets[name]
            if 'head_sha=' not in path:
                return {'workflow_runs': [copy.deepcopy(r) for r in self.runs.values()
                                         if self.latest_available and r['repository']['full_name'] == target['repository']]}
            return {'workflow_runs': [copy.deepcopy(r) for r in self.runs.values()
                                     if r['head_sha'] == target['commit']
                                     and r['repository']['full_name'] == target['repository']]}
        run_id = int(re.search(r'/runs/(\d+)', path)[1])
        if '/artifacts?' in path:
            return {'artifacts': [copy.deepcopy(self.artifacts[run_id])]}
        return copy.deepcopy(self.runs[run_id])

    def archive(self, url):
        run_id = int(re.search(r'/runs/(\d+)', url)[1])
        self.downloads.append(run_id)
        return self.archives[run_id]


class ManagerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.config = common.load_config()
        common.write_json(self.project / 'tools/backend-refresh/targets.json', self.config)
        self.readme = (common.PROJECT / 'README.md').read_text()
        (self.project / 'README.md').write_text(self.readme)
        self.client = FakeGitHub(self.config, self.readme)

    def update(self):
        return managers.update(self.project, self.project / 'receipt.json', self.client, NOW)

    def test_exact_official_release_builds_update_all_links_and_leave_other_sections_alone(self):
        report = self.update()
        after = (self.project / 'README.md').read_text()
        for name, record in report['managers'].items():
            self.assertEqual('matched', record['status'])
            self.assertEqual(self.config['backends'][name]['version'], record['manager_version'])
            self.assertEqual(self.config['backends'][name]['uapi'], record['uapi'])
            self.assertIn(record['download_url'], after)
        self.assertEqual([], report['warnings'])
        self.assertEqual(self.readme.split('## Backends and manager apps')[0],
                         after.split('## Backends and manager apps')[0])
        self.assertEqual(self.readme.split('These are pinned upstream development revisions')[1],
                         after.split('These are pinned upstream development revisions')[1])
        self.assertEqual(report, json.loads((self.project / 'receipt.json').read_text()))

    def test_missing_exact_build_rechecks_and_retains_previous_manager(self):
        self.client.runs[100]['conclusion'] = 'failure'
        self.client.latest_available = False
        report = self.update()
        record = report['managers']['kernelsu']
        self.assertEqual('retained', record['status'])
        self.assertEqual(self.config['backends']['kernelsu']['version'] - 1, record['manager_version'])
        self.assertTrue(report['warnings'])

    def test_pr_and_wrong_branch_builds_cannot_be_selected(self):
        self.client.latest_available = False
        for change in [{'event': 'pull_request'}, {'head_branch': 'dev-susfs'},
                       {'head_repository': {'full_name': 'somebody/fork'}},
                       {'head_repository': None}, {'head_sha': None}]:
            with self.subTest(change=change):
                original = copy.deepcopy(self.client.runs[100])
                self.client.runs[100].update(change)
                self.assertEqual('retained', self.update()['managers']['kernelsu']['status'])
                self.client.runs[100] = original

    def test_download_digest_mismatch_cannot_replace_a_verified_link(self):
        self.client.archives[100] += b'tampered'
        report = self.update()
        self.assertEqual('compatible', report['managers']['kernelsu']['status'])
        self.assertNotEqual(100, report['managers']['kernelsu']['run_id'])
        self.assertTrue(any('digest' in message for message in report['warnings']))

    def test_removed_exact_build_uses_a_new_verified_official_manager(self):
        self.client.runs[100]['conclusion'] = 'failure'
        report = self.update()
        result = report['managers']['kernelsu']
        self.assertEqual('compatible', result['status'])
        self.assertEqual(self.config['backends']['kernelsu']['version'] + 1, result['manager_version'])
        self.assertEqual(self.config['backends']['kernelsu']['uapi'], result['uapi'])
        self.assertEqual('c' * 40, result['source_commit'])

    def test_mixed_api_bundle_verifies_each_manager_against_its_backend(self):
        self.config['backends']['kernelsu']['uapi'] = 6
        common.write_json(self.project / 'tools/backend-refresh/targets.json', self.config)
        self.client = FakeGitHub(self.config, self.readme)
        report = self.update()
        self.assertEqual({'kernelsu': 6, 'kernelsu-next': 5, 'resukisu': 5}, report['required_uapi'])
        self.assertTrue(all(r['status'] == 'matched' for r in report['managers'].values()))
        for name, record in report['managers'].items():
            self.assertEqual(self.config['backends'][name]['uapi'], record['uapi'])
        with self.assertRaisesRegex(ValueError, 'UAPI 5'):
            managers.verify_archive(bundle('kernelsu', 123, uapi=5), 'kernelsu', 6)

    def test_expired_current_and_previous_artifacts_remove_broken_download(self):
        for run_id, run in self.client.runs.items():
            if run['repository']['full_name'] == 'tiann/KernelSU':
                self.client.artifacts[run_id]['expired'] = True
        report = self.update()
        self.assertEqual('unavailable', report['managers']['kernelsu']['status'])
        row = next(line for line in (self.project / 'README.md').read_text().splitlines()
                   if line.startswith('| KernelSU |'))
        self.assertNotIn('nightly.link', row)
        self.assertIn('Official builds', row)

    def test_api_outage_preserves_previous_link_and_does_not_block_other_backends(self):
        self.client.failed.add('kernelsu-next')
        report = self.update()
        self.assertEqual('unchanged', report['managers']['kernelsu-next']['status'])
        self.assertEqual('matched', report['managers']['kernelsu']['status'])
        row = next(line for line in (self.project / 'README.md').read_text().splitlines()
                   if line.startswith('| KernelSU-Next |'))
        original = next(line for line in self.readme.splitlines() if line.startswith('| KernelSU-Next |'))
        self.assertEqual(original, row)

    def test_wrong_uapi_and_debug_apks_are_rejected(self):
        for name, target in self.config['backends'].items():
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, 'UAPI 6'):
                    managers.verify_archive(bundle(name, target['version'], uapi=6), name, 5)
                with self.assertRaisesRegex(ValueError, 'release manager APK'):
                    managers.verify_archive(bundle(name, target['version'], debug=True), name, 5)

    def test_wrong_version_and_wrong_manager_package_are_rejected(self):
        version = self.config['backends']['kernelsu']['version']
        with self.assertRaisesRegex(ValueError, 'build number'):
            managers.verify_archive(bundle('kernelsu', version - 1), 'kernelsu', 5, version)
        with self.assertRaisesRegex(ValueError, 'JNI getter'):
            managers.verify_archive(bundle('kernelsu', version, package='unrelated_app'), 'kernelsu', 5)

    def test_truncated_elf_and_unrecognized_getter_cannot_claim_uapi_5(self):
        package = managers.MANAGERS['kernelsu'][3]
        data = library(package)
        with self.assertRaises(ValueError):
            managers.manager_uapi(data[:800], package)
        changed = bytearray(data)
        struct.pack_into('<I', changed, 128, 0x14000001)  # branch, not a proven constant getter
        with self.assertRaisesRegex(ValueError, 'Unrecognized'):
            managers.manager_uapi(bytes(changed), package)

    def test_duplicate_table_rows_refuse_to_rewrite_readme(self):
        row = next(line for line in self.readme.splitlines() if line.startswith('| KernelSU |'))
        modified = self.readme.replace(row, row + '\n' + row)
        (self.project / 'README.md').write_text(modified)
        with self.assertRaisesRegex(ValueError, 'exactly one README manager row'):
            self.update()
        self.assertEqual(modified, (self.project / 'README.md').read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
