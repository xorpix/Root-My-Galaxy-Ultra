#!/usr/bin/env python3
"""Exercise production Odin scheduling, UID probes and native wait policy without a phone."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class EarlyRootSpeedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = shutil.which('java')
        cls.cc = shutil.which('cc')
        if not cls.java or not cls.cc:
            raise unittest.SkipTest('JDK and C compiler required')
        cls.temp = tempfile.TemporaryDirectory(prefix='rmgu-early-speed-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.work = Path(cls.temp.name)
        javac = shutil.which('javac')
        compiler = [javac] if javac else [cls.java, '--module', 'jdk.compiler/com.sun.tools.javac.Main']
        fixtures = ROOT / 'tools/fixtures'
        sources = list((fixtures / 'd2_runtime').rglob('*.java')) + [
            ROOT / 'd2helper/src/main/java/dev/experimental/rmgu/d2helper/D2Runtime.java',
            ROOT / 'd2-shared/src/main/java/dev/experimental/rmgu/d2/D2BootCycle.java',
            ROOT / 'd2-shared/src/main/java/dev/experimental/rmgu/d2/VaultOperation.java',
            ROOT / 'd2-shared/src/main/java/dev/experimental/rmgu/d2/VaultRecord.java',
            ROOT / 'app/src/main/java/dev/busung/s25uroot/RootReadyProbe.java',
            fixtures / 'ReadyProbeHarness.java',
        ]
        subprocess.run(compiler + ['-d', str(cls.work)] + list(map(str, sources)), check=True, text=True)
        subprocess.run([cls.cc, '-std=c11', '-Wall', '-Wextra', '-Werror',
                        '-I', str(ROOT / 'app/src/main/cpp/dirtyfrag'),
                        str(fixtures / 'ready_wait_harness.c'), '-o', str(cls.work / 'ready-wait')],
                       check=True, text=True)
        subprocess.run([cls.cc, '-std=c11', '-Wall', '-Wextra', '-Werror',
                        '-I', str(ROOT / 'app/src/main/cpp/dirtyfrag'),
                        str(fixtures / 'orphan_wait_harness.c'), '-o', str(cls.work / 'orphan-wait')],
                       check=True, text=True)

    def java_check(self, name, *args):
        result = subprocess.run([self.java, '-cp', str(self.work), name, *args],
                                check=True, capture_output=True, text=True, timeout=14)
        self.assertIn('PASS', result.stdout)
        print(result.stdout.strip(), flush=True)

    def test_uid_probe_uses_completed_commands_and_bounded_pipe_reads(self):
        self.java_check('dev.busung.s25uroot.ReadyProbeHarness')

    def test_native_wait_requires_root_proof_and_keeps_fallback_deadline(self):
        result = subprocess.run([str(self.work / 'ready-wait')], check=True, capture_output=True, text=True, timeout=2)
        self.assertIn('PASS', result.stdout)
        print(result.stdout.strip(), flush=True)

    def test_native_trigger_observes_real_reparenting_and_keeps_fallback(self):
        result = subprocess.run([str(self.work / 'orphan-wait')], check=True, capture_output=True, text=True, timeout=4)
        self.assertIn('PASS', result.stdout)
        print(result.stdout.strip(), flush=True)

    def test_concurrent_subscribers_are_notified_once(self):
        self.java_check('dev.experimental.rmgu.d2helper.D2RuntimeHarness', 'cycle')

    def test_root_starts_early_but_all_four_odin_checks_still_run(self):
        self.java_check('dev.experimental.rmgu.d2helper.D2RuntimeHarness', 'busy')

    def test_delayed_vault_releases_root_only_after_verification(self):
        self.java_check('dev.experimental.rmgu.d2helper.D2RuntimeHarness', 'delayed')

    def test_unavailable_vault_keeps_completion_fallback(self):
        self.java_check('dev.experimental.rmgu.d2helper.D2RuntimeHarness', 'fallback')

    def test_watchdog_closes_cycle_and_ignores_late_success(self):
        self.java_check('dev.experimental.rmgu.d2helper.D2RuntimeHarness', 'watchdog')


if __name__ == '__main__':
    unittest.main(verbosity=2)
