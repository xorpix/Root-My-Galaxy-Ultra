#!/usr/bin/env python3
"""Isolated settings presentation and resource checks. Does not build or run the app."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class PresentationBehavior(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = shutil.which("java")
        if cls.java is None:
            raise RuntimeError("A JDK with the jdk.compiler module is required")
        cls.directory = tempfile.TemporaryDirectory(prefix="rmgu-settings-test-")
        cls.addClassCleanup(cls.directory.cleanup)
        source = ROOT / "app/src/main/java/dev/busung/s25uroot/SettingsPresentation.java"
        harness = ROOT / "tools/fixtures/settings_presentation/SettingsPresentationHarness.java"
        result = subprocess.run(
            [cls.java, "--module", "jdk.compiler/com.sun.tools.javac.Main",
             "--release", "11", "-d", cls.directory.name, str(source), str(harness)],
            capture_output=True, text=True,
        )
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)

    def run_case(self, case):
        result = subprocess.run(
            [self.java, "-cp", self.directory.name,
             "dev.busung.s25uroot.SettingsPresentationHarness", case],
            capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_status_preserves_active_state_after_policy_is_disabled(self):
        self.run_case("status")

    def test_incompatible_devices_can_withdraw_existing_preferences(self):
        self.run_case("availability")

    def test_manual_choices_respect_the_effective_minimum(self):
        self.run_case("settle")


class DefaultResources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.strings = {}
        for path in (ROOT / "app/src/main/res/values").glob("*.xml"):
            for entry in ET.parse(path).getroot():
                if entry.tag != "string":
                    continue
                name = entry.attrib["name"]
                if name in cls.strings:
                    raise AssertionError("Duplicate default string: " + name)
                cls.strings[name] = "".join(entry.itertext())

    def test_reviewed_kotlin_strings_have_default_definitions(self):
        references = set()
        for path in (ROOT / "app/src/main/java").rglob("*.kt"):
            references.update(re.findall(r"(?<!android\.)\bR\.string\.([A-Za-z0-9_]+)", path.read_text()))
        self.assertFalse(references - self.strings.keys(), sorted(references - self.strings.keys()))

    def test_new_format_strings_have_consistent_placeholders(self):
        expected = {
            "settings_manual_profile_help": {"%1$s"},
            "settings_susfs_active": {"%1$s"},
            "settings_susfs_active_until_reboot": {"%1$s"},
        }
        for name, placeholders in expected.items():
            with self.subTest(resource=name):
                self.assertEqual(placeholders, set(re.findall(r"%\d+\$s", self.strings[name])))


if __name__ == "__main__":
    unittest.main(verbosity=2)
