#!/usr/bin/env python3
"""Exercise the production bounded reader with real children and no Android dependencies."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BoundedProcessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = shutil.which("java")
        if not cls.java or not Path("/bin/sh").is_file():
            raise unittest.SkipTest("JDK and POSIX shell required")
        cls.temp = tempfile.TemporaryDirectory(prefix="rmgu-process-test-")
        cls.addClassCleanup(cls.temp.cleanup)
        javac = shutil.which("javac")
        command = [javac] if javac else [cls.java, "--module", "jdk.compiler/com.sun.tools.javac.Main"]
        subprocess.run(command + ["-d", cls.temp.name,
            str(ROOT / "app/src/main/java/dev/busung/s25uroot/BoundedProcess.java"),
            str(ROOT / "app/src/main/java/dev/busung/s25uroot/EarlyRootTiming.java"),
            str(ROOT / "tools/fixtures/BoundedProcessHarness.java")], check=True, capture_output=True, text=True)

    def check_mode(self, mode):
        result = subprocess.run([self.java, "-cp", self.temp.name,
            "dev.busung.s25uroot.BoundedProcessHarness", mode],
            check=True, capture_output=True, text=True, timeout=8)
        self.assertIn(": PASS", result.stdout)
        print(result.stdout.strip())

    def test_silent_child_deadline(self): self.check_mode("silent")
    def test_partial_root_reply_is_not_success(self): self.check_mode("partial")
    def test_completed_root_reply(self): self.check_mode("root")
    def test_denied_reply(self): self.check_mode("denied")
    def test_large_output_pipe_drain(self): self.check_mode("flood")
    def test_unlock_evidence(self): self.check_mode("timing")


if __name__ == "__main__":
    unittest.main(verbosity=2)
