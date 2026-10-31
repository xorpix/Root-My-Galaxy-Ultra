#!/usr/bin/env python3
"""Host-only checks for recurring builds and release gates. Never execute ARM64 payloads."""
import copy
import ast
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
import managers
from test_managers import FakeGitHub, NOW

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
        target['rebuild'] = False
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


class HistoryTests(unittest.TestCase):
    """Exercise the release lineage gate with actual advertised/unadvertised Git history."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.remote = self.root / 'remote'
        self.logs = self.root / 'logs'
        self.logs.mkdir()
        subprocess.run(['git', 'init', '--quiet', '-b', 'old', self.remote], check=True)
        for key, value in [('user.name', 'History test'), ('user.email', 'test@example.invalid')]:
            self.git('config', key, value)
        self.old = self.commit('old source')
        self.git('checkout', '--quiet', '--orphan', 'reviewed')
        self.anchor = self.commit('reviewed source')
        self.tip = self.commit('next source')
        self.git('checkout', '--quiet', '--orphan', 'unrelated')
        self.other = self.commit('next source')  # Identical tree, unrelated ancestry.
        self.git('checkout', '--quiet', 'reviewed')
        self.git('branch', '-D', 'old')
        self.repo = self.root / 'clone'
        subprocess.run(['git', 'clone', '--quiet', '--no-local', self.remote, self.repo], check=True)
        self.assertFalse(self.present(self.old))
        self.bridge = patch.object(refresh, 'REVIEWED_HISTORY_BRIDGES', {('kowsu', self.old): self.anchor})
        self.bridge.start()
        self.addCleanup(self.bridge.stop)

    def git(self, *args, repo=None):
        return common.capture(['git', *args], repo or self.remote)

    def commit(self, text):
        (self.remote / 'source.c').write_text(text + '\n')
        self.git('add', 'source.c')
        self.git('commit', '--quiet', '-m', text)
        return self.git('rev-parse', 'HEAD')

    def present(self, sha):
        return subprocess.run(['git', 'cat-file', '-e', sha + '^{commit}'], cwd=self.repo,
                              stderr=subprocess.DEVNULL).returncode == 0

    def test_reviewed_replacement_and_later_commits_retrieve_the_exact_old_pin(self):
        for current in (self.anchor, self.tip):
            with self.subTest(current=current):
                refresh.verify_history(self.repo, 'kowsu', self.old, current, self.logs)
                self.assertTrue(self.present(self.old))
                self.assertEqual(self.tip, self.git('rev-parse', 'HEAD', repo=self.repo))
                self.assertEqual('', self.git('status', '--porcelain', repo=self.repo))
        self.assertTrue((self.logs / 'kowsu-reviewed-base-fetch.log').is_file())

    def test_normal_descendants_still_work_without_a_history_exception(self):
        refresh.verify_history(self.repo, 'kowsu', self.anchor, self.tip, self.logs)
        self.assertEqual([], list(self.logs.iterdir()))

    def test_unreviewed_missing_base_is_not_fetched(self):
        with patch.object(refresh, 'REVIEWED_HISTORY_BRIDGES', {}):
            with self.assertRaisesRegex(ValueError, 'no longer reachable'):
                refresh.verify_history(self.repo, 'kowsu', self.old, self.tip, self.logs)
        self.assertFalse(self.present(self.old))
        self.assertEqual([], list(self.logs.iterdir()))

    def test_another_rewrite_is_rejected_even_with_identical_source(self):
        refresh.fetch_snapshot_commit(self.repo, self.old, self.logs / 'fixture-fetch.log')
        self.assertEqual(self.git('rev-parse', self.tip + '^{tree}'),
                         self.git('rev-parse', self.other + '^{tree}'))
        with self.assertRaisesRegex(ValueError, 'not a descendant'):
            refresh.verify_history(self.repo, 'kowsu', self.old, self.other, self.logs)

    def test_bridge_is_scoped_to_backend_old_pin_and_proven_ancestry(self):
        refresh.fetch_snapshot_commit(self.repo, self.old, self.logs / 'fixture-fetch.log')
        for backend, previous in [('kernelsu', self.old), ('kowsu', self.other)]:
            with self.subTest(backend=backend, previous=previous):
                with self.assertRaisesRegex(ValueError, 'not a descendant'):
                    refresh.verify_history(self.repo, backend, previous, self.tip, self.logs)
        self.assertTrue(refresh.reviewed_history_transition('kowsu', self.old, self.anchor))
        self.assertFalse(refresh.reviewed_history_transition('kowsu', self.old, self.tip))
        with patch.object(refresh, 'REVIEWED_HISTORY_BRIDGES', {('kowsu', self.old): 'b' * 40}):
            with self.assertRaisesRegex(ValueError, 'not a descendant'):
                refresh.verify_history(self.repo, 'kowsu', self.old, self.tip, self.logs)

    def test_broken_git_lookup_stops_instead_of_approving_a_transition(self):
        with self.assertRaisesRegex(RuntimeError, 'could not verify reviewed history anchor'):
            refresh.verify_history(self.repo, 'kowsu', self.old, 'a' * 40, self.logs)
        self.assertFalse(self.present(self.old))


class RefreshTests(unittest.TestCase):
    def test_forks_with_overlapping_driver_numbers_keep_their_own_versions(self):
        previous = {n: t['version'] for n, t in common.load_config()['backends'].items()}
        paths = ['README.md', 'tools/prepare_m3q_helpers.py', str(common.JAVA / 'AzhlCatalog.kt'),
                 'native/azhl/src/azhl_backend.h']
        for overlapping_old in (False, True):
            old = dict(previous)
            if overlapping_old:
                old['kernelsu'] = old['kowsu']
            current = dict(old, kernelsu=old['kowsu'], kowsu=old['kowsu'] + 1)
            for relative in paths:
                path = PROJECT / relative
                source = path.read_text()
                if overlapping_old:
                    source = common.driver_versions_edit(source, path.name, previous, old)
                updated = common.driver_versions_edit(source, path.name, old, current)
                with self.subTest(source=path.name, overlapping_old=overlapping_old):
                    if path.name == 'prepare_m3q_helpers.py':
                        versions = next(line for line in updated.splitlines() if line.startswith('VERSIONS = '))
                        self.assertEqual(current, ast.literal_eval(versions.split(' = ', 1)[1]))
                    elif path.name == 'AzhlCatalog.kt':
                        for name, version in current.items():
                            self.assertIn(f'KernelSuFlavor.{common.FLAVOR_ENUMS[name]} -> {version}\n', updated)
                    elif path.name == 'README.md':
                        for name, version in current.items():
                            row = next(line for line in updated.splitlines()
                                       if line.startswith(f'| {common.BACKEND_LABELS[name]} |'))
                            self.assertIn(f'| `{version} / ', row)
                    else:
                        for name, version in current.items():
                            row = next(line for line in updated.splitlines() if f'{{"{name}",' in line)
                            self.assertIn(f'"{version}"', row)
                            self.assertTrue(row.endswith(f', {version}}},'))

    def test_release_labels_follow_the_actual_pinned_daemon_tag(self):
        for full, expected in [('3.3.1-12-g0faec0cc', '3.3.1'),
                               ('3.4.2-35-g204f0071', '3.4.2'),
                               ('4.2.0-rc3-58-gd7260ca1', '4.2.0-rc3'),
                               ('3.3.1', '3.3.1')]:
            self.assertEqual(expected, common.daemon_release({'daemon_version': full}))
        with self.assertRaisesRegex(ValueError, 'release name'):
            common.daemon_release({'daemon_version': 'unrecognized-tag'})

    def test_new_upstream_tags_cannot_rename_a_patch_only_rebuild(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / 'source'
            subprocess.run(['git', 'init', '--quiet', '-b', 'main', repo], check=True)
            for key, value in [('user.name', 'Test'), ('user.email', 'test@example.invalid')]:
                subprocess.run(['git', 'config', key, value], cwd=repo, check=True)
            for text in ('first', 'pinned'):
                (repo / 'file').write_text(text)
                subprocess.run(['git', 'add', '.'], cwd=repo, check=True)
                subprocess.run(['git', 'commit', '--quiet', '-m', text], cwd=repo, check=True)
                if text == 'first':
                    subprocess.run(['git', 'tag', 'v1.0.0'], cwd=repo, check=True)
            pin = common.capture(['git', 'rev-parse', 'HEAD'], repo)
            count = common.capture(['git', 'rev-list', '--count', 'HEAD'], repo)
            expected = common.capture(['git', 'describe', '--tags', '--always'], repo).removeprefix('v')
            subprocess.run(['git', 'tag', 'v1.0.1'], cwd=repo, check=True)
            with self.assertRaisesRegex(ValueError, 'Cannot reproduce'):
                refresh.restore_pinned_version_tags(repo, '9.9.9', root / 'invalid.log')
            self.assertIn('v1.0.1', common.capture(['git', 'tag', '--list'], repo))
            refresh.restore_pinned_version_tags(repo, expected, root / 'tags.log')
            self.assertEqual(common.capture(['git', 'describe', '--tags', '--always'], repo), 'v' + expected)
            self.assertEqual(common.capture(['git', 'rev-parse', 'HEAD'], repo), pin)
            self.assertEqual(common.capture(['git', 'rev-list', '--count', 'HEAD'], repo), count)
            self.assertEqual((repo / 'file').read_text(), 'pinned')
            self.assertEqual(common.capture(['git', 'status', '--porcelain'], repo), '')
            refresh.restore_pinned_version_tags(repo, expected, root / 'unused.log')
            self.assertFalse((root / 'unused.log').exists())

    def test_exact_snapshot_pin_can_be_rebuilt_after_upstream_moves_refs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            remote = root / 'remote'
            subprocess.run(['git', 'init', '--quiet', '-b', 'main', remote], check=True)
            for key, value in [('user.name', 'Test'), ('user.email', 'test@example.invalid')]:
                subprocess.run(['git', 'config', key, value], cwd=remote, check=True)
            (remote / 'file').write_text('pinned source')
            subprocess.run(['git', 'add', '.'], cwd=remote, check=True)
            subprocess.run(['git', 'commit', '--quiet', '-m', 'Pinned source'], cwd=remote, check=True)
            pin = common.capture(['git', 'rev-parse', 'HEAD'], remote)
            subprocess.run(['git', 'checkout', '--quiet', '--orphan', 'rewritten'], cwd=remote, check=True)
            (remote / 'file').write_text('unrelated new source')
            subprocess.run(['git', 'add', '.'], cwd=remote, check=True)
            subprocess.run(['git', 'commit', '--quiet', '-m', 'New history'], cwd=remote, check=True)
            subprocess.run(['git', 'branch', '-D', 'main'], cwd=remote, check=True,
                           stdout=subprocess.DEVNULL)
            source = root / 'source'
            subprocess.run(['git', 'clone', '--quiet', '--no-local', remote, source], check=True)
            missing = subprocess.run(['git', 'cat-file', '-e', pin + '^{commit}'], cwd=source,
                                     stderr=subprocess.DEVNULL).returncode
            self.assertNotEqual(missing, 0)
            refresh.fetch_snapshot_commit(source, pin, root / 'fetch.log')
            subprocess.run(['git', 'checkout', '--quiet', '--detach', pin], cwd=source, check=True)
            self.assertEqual((source / 'file').read_text(), 'pinned source')
            self.assertEqual(common.capture(['git', 'rev-list', '--count', 'HEAD'], source), '1')
            self.assertEqual(common.capture(['git', 'rev-parse', '--is-shallow-repository'], source), 'false')
            refresh.fetch_snapshot_commit(source, pin, root / 'unused.log')
            self.assertFalse((root / 'unused.log').exists())

    def test_snapshot_fetch_rejects_refs_instead_of_exact_pins(self):
        with self.assertRaisesRegex(ValueError, 'full commit SHA'):
            refresh.fetch_snapshot_commit(PROJECT, 'main', Path('/unused.log'))

    def test_reviewed_history_transition_is_bounded_to_the_exact_backend_and_pair(self):
        old_baka = 'b8754b203edfc03342d0f58adfc582719b31363c'
        new_baka = '2489b38582a08fee7b586d05185da5e5d12c6a5c'
        self.assertTrue(refresh.reviewed_history_transition('resukisu', old_baka, new_baka))
        self.assertFalse(refresh.reviewed_history_transition('kernelsu-next', old_baka, new_baka))
        self.assertFalse(refresh.reviewed_history_transition('resukisu', old_baka, 'a' * 40))
        old = '6200478dd76fc63093a8226d360f5072c46aea26'
        new = 'd7260ca1e5c128061122924d0d48754e361e135c'
        self.assertTrue(refresh.reviewed_history_transition('resukisu', old, new))
        self.assertFalse(refresh.reviewed_history_transition('kernelsu-next', old, new))
        self.assertFalse(refresh.reviewed_history_transition('resukisu', 'a' * 40, new))
        self.assertFalse(refresh.reviewed_history_transition('resukisu', old, 'b' * 40))
        previous_next = '3157e7822190eba628ca6796242db970405e440a'
        current_next = '204f007164905395e280e9290cc7774f57af8c61'
        self.assertTrue(refresh.reviewed_history_transition('kernelsu-next', previous_next, current_next))
        self.assertFalse(refresh.reviewed_history_transition('kernelsu-next', previous_next, 'b' * 40))
        self.assertFalse(refresh.reviewed_history_transition('kernelsu', previous_next, current_next))
        rebased_next = 'd65c9db1b07edcdc3ad531ad2108fc66227e502c'
        reviewed_next = 'b427a3f9c2421a443f961007bfce66b25486643f'
        self.assertTrue(refresh.reviewed_history_transition('kernelsu-next', rebased_next, reviewed_next))
        self.assertFalse(refresh.reviewed_history_transition('kernelsu', rebased_next, reviewed_next))
        self.assertFalse(refresh.reviewed_history_transition('kernelsu-next', rebased_next, 'b' * 40))
        self.assertFalse(refresh.reviewed_history_transition('kernelsu-next', 'a' * 40, reviewed_next))

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
        self.assertEqual('1.2.0', common.next_version('1.2.0', ['1.1.99']))

    def test_version_preserves_pending_release_despite_ci_tags(self):
        tags = ['1.2.23', 'ci-1.2.24-31', 'ci-1.2.24-32']
        self.assertEqual('1.2.24', common.next_version('1.2.24', tags))
        self.assertEqual('1.2.24', common.next_version('1.2.24', []))

    def test_version_respects_existing_bare_and_prefixed_tags(self):
        for tag in ('1.2.24', 'v1.2.24'):
            with self.subTest(tag=tag):
                self.assertEqual('1.2.25', common.next_version('1.2.24', [tag]))
        self.assertEqual('1.2.27', common.next_version('1.2.24', ['1.2.26', '1.2.25']))

    def test_pending_release_keeps_curated_notes_and_renders_backend_table(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            work = root / 'work'
            (source / 'app').mkdir(parents=True)
            (source / 'docs/release-notes').mkdir(parents=True)
            (work / 'result').mkdir(parents=True)
            (source / 'app/build.gradle.kts').write_text('val appVersionBase = "1.2.25"\n')
            template = (
                '## What is new\n\n'
                'The pending release keeps its curated notes, which must reach the published\n'
                'release with the backend table rendered from the built targets.\n\n'
                '<!-- RMGU_BACKEND_TABLE -->\n\n'
                '## Updating\n\n'
                'Install the release APK over the existing app and fully reboot afterwards.\n'
            )
            notes = source / 'docs/release-notes/1.2.25.md'
            notes.write_text(template)
            config = {'app_base': 'a' * 40, 'backends': {'resukisu': {
                'rebuild': True, 'version': 12345, 'uapi': 5,
                'commit': 'b' * 40, 'repository': 'example/backend',
                'daemon_version': 'fixture-version'}}}
            tags = '1.2.23\n1.2.24\nci-1.2.24-33'
            with patch.object(refresh, 'capture', return_value=tags):
                refresh.prepare_release(source, work, config)
            self.assertEqual('1.2.25', common.app_version(source))
            self.assertEqual('1.2.25', json.loads((work / 'result/release.json').read_text())['version'])
            rendered = notes.read_text()
            self.assertTrue(rendered.startswith(template.split('<!-- RMGU_BACKEND_TABLE -->')[0]))
            self.assertIn('| BakaSU (updated) | 12345 / 5 |', rendered)
            self.assertNotIn('<!-- RMGU_BACKEND_TABLE -->', rendered)
            self.assertEqual(rendered, (work / 'result/release-notes.md').read_text())

            # An unpublished release may be refreshed again after the first
            # build filled its table. Keep the prose and replace only that table.
            config['backends']['resukisu']['version'] = 12346
            rerendered = refresh.render_curated_notes(rendered, config)
            self.assertIn('| BakaSU (updated) | 12346 / 5 |', rerendered)
            self.assertNotIn('| BakaSU (updated) | 12345 / 5 |', rerendered)
            self.assertTrue(rerendered.startswith(template.split('<!-- RMGU_BACKEND_TABLE -->')[0]))
            self.assertEqual(1, rerendered.count('<!-- RMGU_BACKEND_TABLE_BEGIN -->'))
            self.assertEqual(rerendered, refresh.render_curated_notes(rerendered, config))

    def test_curated_backend_notes_reject_ambiguous_managed_blocks(self):
        config = {'backends': {}}
        begin = '<!-- RMGU_BACKEND_TABLE_BEGIN -->'
        end = '<!-- RMGU_BACKEND_TABLE_END -->'
        for text in ('No table marker', begin, end + begin,
                     begin + end + begin + end,
                     '<!-- RMGU_BACKEND_TABLE -->' + begin + end):
            with self.subTest(template=text), self.assertRaises(ValueError):
                refresh.render_curated_notes(text, config)

    def test_unreviewed_uapi_stops_before_native_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'uapi').mkdir()
            (root / 'uapi/supercall.h').write_text('static const __u32 KERNEL_SU_UAPI_VERSION = 7;')
            for name in common.REPOSITORIES:
                with self.subTest(backend=name), patch.object(refresh, 'capture', return_value='false'):
                    with self.assertRaisesRegex(ValueError, 'new UAPI'):
                        refresh.verify_upstream(root, name, common.load_config()['backends'][name])

    def test_reviewed_uapi_is_backend_specific_and_requires_unchanged_get_info(self):
        header = '''static const __u32 KERNEL_SU_UAPI_VERSION = 6;
struct ksu_get_info_cmd {
    __u32 version; /* output */
    __u32 flags;
    __u32 features;
    __u32 uapi_version;
};
static const __u32 KSU_IOCTL_GET_INFO = _IOR('K', 2, struct ksu_get_info_cmd);
'''
        for name in ('kernelsu', 'kernelsu-next', 'resukisu', 'kowsu'):
            self.assertEqual(6, common.reviewed_uapi(header, name))
            with self.assertRaisesRegex(ValueError, 'control layout'):
                common.reviewed_uapi(header.replace('__u32 flags;', '__u64 flags;'), name)
            with self.assertRaisesRegex(ValueError, 'ioctl'):
                common.reviewed_uapi(header.replace("_IOR('K', 2", "_IOR('K', 3"), name)
        for name in common.REPOSITORIES:
            with self.assertRaisesRegex(ValueError, 'new UAPI'):
                common.reviewed_uapi(header.replace('= 6;', '= 7;'), name)

    def test_force_rebuild_refreshes_every_pair_without_changed_upstream_pins(self):
        config = common.load_config()
        manifests = common.baseline(PROJECT)
        for name, target in config['backends'].items():
            target['compat_sha256'] = manifests[name]['rebased_patch']['sha256']
        replies = {'https://github.com/' + t['repository'] + '.git': t['commit']
                   for t in config['backends'].values()}
        with patch.object(probe, 'load_config', return_value=config), \
                patch.object(probe, 'baseline', return_value=manifests):
            snapshot = probe.probe(PROJECT, lambda url, ref: replies[url] + '\t' + ref, force_rebuild=True)
        self.assertTrue(snapshot['force_rebuild'])
        self.assertEqual(list(config['backends']), snapshot['changed'])

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
            # The workflow refreshes manager links before finish-build repackages the source.
            manager_config = common.load_config(source / 'tools/backend-refresh/targets.json')
            manager_report = managers.update(source, work / 'logs/manager-downloads.json',
                FakeGitHub(manager_config, (source / 'README.md').read_text()), NOW)
            self.assertTrue(all(r['status'] == 'matched' for r in manager_report['managers'].values()))
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
            self.assertEqual((source / 'README.md').read_text(), (publisher / 'README.md').read_text())
            self.assertTrue((result / 'validation/manager-downloads.json').is_file())

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

    def test_release_assets_are_verified_by_name_and_size(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            apk = work / 'RootMyGalaxyUltra-1.2.24.apk'
            compat = work / 'apply-matched-backends.patch'
            apk.write_bytes(b'apk')
            compat.write_bytes(b'patchbytes')
            expected = [apk, compat]
            verified = json.dumps({'assets': [{'name': apk.name, 'size': apk.stat().st_size},
                                              {'name': compat.name, 'size': compat.stat().st_size}]})
            partial = json.dumps({'assets': [{'name': apk.name, 'size': apk.stat().st_size}]})
            with patch.object(release, 'capture', return_value=verified):
                release.check_release_assets('1.2.24', expected)
            with patch.object(release, 'capture', return_value=partial):
                with self.assertRaisesRegex(ValueError, 'apply-matched-backends.patch'):
                    release.check_release_assets('1.2.24', expected)
            differs = json.dumps({'assets': [{'name': apk.name, 'size': apk.stat().st_size + 1}]})
            with patch.object(release, 'capture', return_value=differs):
                with self.assertRaisesRegex(ValueError, 'differs from the validated copy'):
                    release.check_release_assets('1.2.24', expected)


if __name__ == '__main__':
    unittest.main(verbosity=2)
