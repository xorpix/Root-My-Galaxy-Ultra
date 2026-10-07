#!/usr/bin/env python3
"""Exercise real Git merges, conflict rejection and recorded build provenance."""
from pathlib import Path
import subprocess
import tempfile
import unittest

import patching
import stage


BASE = '''int setup(void)
{
    prepare();
    check();
    configure();
    int value = current_cred();
    return value;
}
'''
REVIEWED = BASE.replace('return value;', 'return samsung_cred(value);')


class PatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'upstream'
        self.repo.mkdir()
        self.git('init', '--quiet')
        self.git('config', 'user.name', 'Patch Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.source = self.repo / 'kernel/core/init.c'
        self.source.parent.mkdir(parents=True)
        self.source.write_text(BASE)
        self.commit('Base upstream')
        self.source.write_text(REVIEWED)
        self.patch = self.root / 'reviewed.patch'
        self.patch.write_bytes(self.git('diff', '--binary', '--full-index'))
        self.git('reset', '--hard', '--quiet', 'HEAD')
        self.log = self.root / 'patch.log'

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, stderr=subprocess.PIPE)

    def commit(self, message):
        self.git('add', '.')
        self.git('commit', '--quiet', '-m', message)

    def upstream_edit(self, text):
        self.source.write_text(text)
        self.commit('New upstream')

    def test_direct_check_is_read_only_and_application_is_exact(self):
        self.assertEqual('direct', patching.check(self.repo, self.patch, self.log))
        self.assertEqual(BASE, self.source.read_text())
        self.assertEqual(b'', self.git('status', '--porcelain'))
        self.assertEqual('direct', patching.apply(self.repo, self.patch, self.log))
        self.assertEqual(REVIEWED, self.source.read_text())

    def test_unrelated_logging_inside_hunk_context_uses_clean_merge(self):
        drift = BASE.replace('    configure();', '    log_version();\n    configure();')
        self.upstream_edit(drift)
        direct = subprocess.run(['git', 'apply', '--check', self.patch], cwd=self.repo,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(0, direct.returncode)
        self.assertEqual('three-way', patching.check(self.repo, self.patch, self.log))
        self.assertEqual(drift, self.source.read_text())
        self.assertEqual(b'', self.git('status', '--porcelain'))
        self.assertEqual('three-way', patching.apply(self.repo, self.patch, self.log))
        self.assertEqual(drift.replace('return value;', 'return samsung_cred(value);'),
                         self.source.read_text())

    def test_changed_credential_operation_is_rejected_without_partial_edits(self):
        upstream = BASE.replace('return value;', 'return new_credential_api(value);')
        self.upstream_edit(upstream)
        with self.assertRaisesRegex(RuntimeError, 'needs review'):
            patching.apply(self.repo, self.patch, self.log)
        self.assertEqual(upstream, self.source.read_text())
        self.assertEqual(b'', self.git('status', '--porcelain'))
        self.assertEqual(b'', self.git('ls-files', '--unmerged'))

    def test_missing_merge_base_blob_is_rejected(self):
        self.upstream_edit(BASE.replace('    configure();', '    log_version();\n    configure();'))
        blob = self.git('rev-parse', 'HEAD~1:kernel/core/init.c').decode().strip()
        self.patch.write_text(self.patch.read_text().replace(blob, 'f' * 40))
        with self.assertRaisesRegex(RuntimeError, 'needs review'):
            patching.check(self.repo, self.patch, self.log)
        self.assertEqual(b'', self.git('status', '--porcelain'))

    def test_dirty_source_is_never_overwritten(self):
        self.source.write_text(BASE + '// Local edit\n')
        with self.assertRaisesRegex(ValueError, 'clean upstream'):
            patching.apply(self.repo, self.patch, self.log)
        self.assertTrue(self.source.read_text().endswith('// Local edit\n'))

    def test_untracked_source_is_rejected(self):
        (self.repo / 'local.txt').write_text('keep me')
        with self.assertRaisesRegex(ValueError, 'clean upstream'):
            patching.check(self.repo, self.patch, self.log)

    def test_provenance_contains_staged_merge_and_later_unstaged_edits(self):
        self.upstream_edit(BASE.replace('    configure();', '    log_version();\n    configure();'))
        patching.apply(self.repo, self.patch, self.log)
        self.assertTrue(self.git('diff', '--cached'))
        self.source.write_text(self.source.read_text() + '// Build formatting\n')
        data, hashes = stage.source_patch(self.repo, self.patch.read_bytes())
        self.assertIn(b'+    return samsung_cred(value);', data)
        self.assertIn(b'+// Build formatting', data)
        self.assertEqual(stage.record(self.source.read_bytes())['sha256'],
                         hashes['kernel/core/init.c'])
        clean = self.root / 'replay'
        subprocess.run(['git', 'clone', '--quiet', '--shared', self.repo, clean], check=True)
        subprocess.run(['git', 'apply', '-'], input=data, cwd=clean, check=True)
        self.assertEqual(self.source.read_bytes(), (clean / 'kernel/core/init.c').read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
