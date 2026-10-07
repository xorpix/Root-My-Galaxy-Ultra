#!/usr/bin/env python3
"""Host checks only. Never executes the ARM64 payload/helper or builds an APK."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import unittest

from prepare_m3q_helpers import variant, file_offset, VERSIONS, LIBRARIES, UAPI_VERSION

PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / 'app/src/main/assets'
JNI = PROJECT / 'app/src/main/jniLibs/arm64-v8a'
JAVA = PROJECT / 'app/src/main/java/dev/busung/s25uroot'


class BundleTests(unittest.TestCase):
    def test_helpers_only_change_documented_instructions(self):
        original = (ASSETS / 'm3q/libm3qroot.so').read_bytes()
        for backend, version in VERSIONS.items():
            data, changes = variant(original, backend)
            self.assertEqual(data, (JNI / LIBRARIES[backend]).read_bytes())
            self.assertEqual(4 if backend == 'kernelsu' else 5, len(changes))
            allowed = {i for va in (0x6c94, 0x6cc8, 0x6ca0, 0x6cd0, 0x7234)
                       for i in range(file_offset(original, va), file_offset(original, va) + 4)}
            self.assertTrue(all(a == b or i in allowed for i, (a, b) in enumerate(zip(original, data))))
            for va, reg in ((0x6c94, 9), (0x6cc8, 2)):
                instruction = struct.unpack_from('<I', data, file_offset(original, va))[0]
                self.assertEqual(0x52800000 | version << 5 | reg, instruction)
            self.assertEqual(0x710014DF, struct.unpack_from('<I', data, file_offset(original, 0x6ca0))[0])
            self.assertEqual(0x528000A4, struct.unpack_from('<I', data, file_offset(original, 0x6cd0))[0])
            if backend != 'kernelsu':
                self.assertEqual(0xAA1F03E5, struct.unpack_from('<I', data, file_offset(original, 0x7234))[0])

    def test_generator_rejects_changed_and_truncated_originals(self):
        original = (ASSETS / 'm3q/libm3qroot.so').read_bytes()
        for bad in (original[:-1], bytes([original[0] ^ 1]) + original[1:]):
            with self.assertRaises(ValueError):
                variant(bad, 'kernelsu-next')

    def test_catalog_and_installed_daemons_agree(self):
        catalog = json.loads((ASSETS / 'azhl/catalog.json').read_text())
        self.assertEqual(set(VERSIONS), {p['flavor'] for p in catalog['payloads']})
        for profile in catalog['payloads']:
            for key in ('exploit', 'kernelsu'):
                artifact = profile[key]
                data = (ASSETS / artifact['url'].removeprefix('asset://')).read_bytes()
                self.assertEqual(artifact['size'], len(data))
                self.assertEqual(artifact['sha256'], hashlib.sha256(data).hexdigest())
            daemon = JNI / ('libm3qksud_' + profile['flavor'].replace('-', '_') + '.so')
            self.assertEqual((ASSETS / 'azhl' / profile['flavor'] / 'ksud').read_bytes(), daemon.read_bytes())
        self.assertEqual((ASSETS / 'm3q/libm3qpayload.so').read_bytes(), (JNI / 'libm3qpayload.so').read_bytes())

    def test_dirtyfrag_variants_and_azhl_share_the_same_verified_backends(self):
        azhl = json.loads((ASSETS / 'azhl/catalog.json').read_text())['payloads']
        dirtyfrag = json.loads((ASSETS / 'df/catalog.json').read_text())['payloads']
        expected = {profile['flavor']: profile['kernelsu'] for profile in azhl}
        firmwares = {'S948BXXS4BZIG', 'S948WVLU4BZID'}
        self.assertEqual(len(VERSIONS) * len(firmwares), len(dirtyfrag))
        self.assertEqual({(firmware, flavor) for firmware in firmwares for flavor in VERSIONS},
                         {(p['firmware']['incremental'], p['flavor']) for p in dirtyfrag})
        for profile in dirtyfrag:
            self.assertEqual(expected[profile['flavor']], profile['kernelsu'])

    def test_runtime_expectations_match_packaged_helpers(self):
        launch = (JAVA / 'M3qLaunch.kt').read_text()
        self.assertIn(f'const val UAPI_VERSION = {UAPI_VERSION}', launch)
        catalog = (JAVA / 'AzhlCatalog.kt').read_text().split('internal fun azhlDriverVersion', 1)[1]
        enum_names = {'kernelsu': 'KernelSu', 'kernelsu-next': 'KernelSuNext', 'resukisu': 'ReSukiSU'}
        native = (PROJECT / 'native/azhl/src/azhl_backend.h').read_text()
        for backend, version in VERSIONS.items():
            helper = (JNI / LIBRARIES[backend]).read_bytes()
            self.assertIn(f'"{backend}" -> Backend(id, {version}, "{hashlib.sha256(helper).hexdigest()}")', launch)
            self.assertIn(f'KernelSuFlavor.{enum_names[backend]} -> {version}', catalog)
            self.assertRegex(native, r'\{"' + re.escape(backend) + r'", "' + str(version) + r'", "[^"]+", ' + str(version) + r'\}')

    def test_environment_matches_original_dex_tracefs_branch(self):
        text = (PROJECT / 'tools/host_validation/original-engine-bytecode.txt').read_text()
        method = text.split('->configureRootEnvironment(Ljava/util/Map; Z Ljava/lang/String;)V\n', 1)[1].split('\nMETHOD ', 1)[0]
        instructions = {}
        for line in method.splitlines():
            m = re.match(r'([0-9a-f]{6})\s+(\S+)\s*(.*)', line)
            if m:
                instructions[int(m[1], 16)] = (m[2], m[3])
        addresses = sorted(instructions)
        registers = {'v4': {}, 'v5': 1, 'v6': None}
        result = None
        pc = addresses[0]
        while True:
            op, args = instructions[pc]
            operands = args.split(', ')
            following = addresses[addresses.index(pc) + 1] if op != 'return-void' else None
            if op == 'const-string':
                registers[operands[0]] = json.loads(args.split(', ', 1)[1])
            elif op == 'move-object':
                registers[operands[0]] = registers[operands[1]]
            elif op == 'move-result' or op == 'move-result-object':
                registers[operands[0]] = result
            elif op == 'invoke-static':
                if 'myUid()I' in args:
                    result = 10345
                elif 'toString(I)' in args:
                    result = str(registers[operands[0]])
                else:
                    self.fail('Unexpected DEX call ' + args)
            elif op == 'invoke-interface':
                self.assertIn('->put(', args)
                registers[operands[0]][registers[operands[1]]] = registers[operands[2]]
            elif op in ('if-eqz', 'if-nez'):
                take = (registers[operands[0]] == 0) == (op == 'if-eqz')
                if take:
                    following = pc + int(operands[1].removesuffix('h'), 16) * 2
            elif op == 'goto':
                following = pc + int(args.removesuffix('h'), 16) * 2
            elif op == 'return-void':
                break
            else:
                self.fail('Unexpected DEX opcode ' + op)
            pc = following
        expected = registers['v4']
        expected.pop('M3Q_APP_UID')
        expected.update(HOME='/data/local/tmp', TMPDIR='/data/local/tmp', PATH='/system/bin:/system/xbin')
        source = (JAVA / 'M3qLaunch.kt').read_text().split('fun fixedEnvironment()', 1)[1].split('fun command(', 1)[0]
        actual = {k: '/data/local/tmp' if v == 'DIRECTORY' else json.loads(v)
                  for k, v in re.findall(r'"([A-Z0-9_]+)" to ("[^"]*"|DIRECTORY)', source)}
        self.assertEqual(expected, actual)


@unittest.skipUnless(shutil.which('sh'), 'POSIX shell needed for isolated staging tests')
class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='m3q-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.adb = self.root / 'adb'
        self.tmp = self.root / 'tmp'
        self.adb.mkdir()
        self.tmp.mkdir()
        self.daemon = self.root / 'daemon'
        self.daemon.write_bytes(b'host-test-daemon-not-executable\n')
        self.sha = hashlib.sha256(self.daemon.read_bytes()).hexdigest()
        source = (ASSETS / 'm3q/prepare-backend.sh').read_text()
        source = source.replace('/data/adb', self.adb.as_posix()).replace('/data/local/tmp', self.tmp.as_posix())
        # Only this temporary copy bypasses the Android/root identity check.
        source = source.replace('[ "$(id -u)" = 0 ] || exit 65', ':')
        self.script = self.root / 'prepare.sh'
        self.script.write_text(source)
        self.module = self.adb / 'modules/keep-me'
        self.module.mkdir(parents=True)
        (self.module / 'data').write_text('preserve this')

    def run_script(self, backend='kernelsu-next', sha=None, disable='0'):
        return subprocess.run(['sh', str(self.script), backend, str(self.daemon), sha or self.sha, disable],
                              capture_output=True, text=True, timeout=5)

    def test_first_switch_disables_but_preserves_modules_and_stages_both_paths(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.module / 'disable').is_file())
        self.assertEqual('preserve this', (self.module / 'data').read_text())
        for name in ('.ksud-stage', 'ksud-m3q-S948NKSS4AZG3-kdp'):
            self.assertEqual(self.daemon.read_bytes(), (self.tmp / name).read_bytes())
        self.assertEqual('kernelsu-next', (self.adb / 'azhl-last-prepared-backend').read_text().strip())
        self.assertIn('M3Q_STAGE_OK:kernelsu-next:' + self.sha, result.stdout)

    def test_same_verified_installed_daemon_can_keep_module_state(self):
        (self.adb / 'azhl-last-prepared-backend').write_text('kernelsu-next\n')
        shutil.copyfile(self.daemon, self.adb / 'ksud')
        self.assertEqual(0, self.run_script().returncode)
        self.assertFalse((self.module / 'disable').exists())
        self.assertEqual(0, self.run_script(disable='1').returncode)
        self.assertTrue((self.module / 'disable').exists())

    def test_external_daemon_change_overrides_old_preparation_receipt(self):
        (self.adb / 'azhl-last-prepared-backend').write_text('kernelsu-next\n')
        (self.adb / 'ksud').write_text('different backend')
        self.assertEqual(0, self.run_script().returncode)
        self.assertTrue((self.module / 'disable').exists())

    def test_bad_hash_and_backend_are_rejected_before_module_changes(self):
        self.assertEqual(67, self.run_script(sha='0' * 64).returncode)
        self.assertFalse((self.module / 'disable').exists())
        self.assertEqual(64, self.run_script(backend='unknown').returncode)

    def test_symlink_module_and_destination_directory_are_refused(self):
        link = self.adb / 'modules/link'
        link.symlink_to(self.module, target_is_directory=True)
        self.assertEqual(66, self.run_script().returncode)
        link.unlink()
        (self.tmp / '.ksud-stage').mkdir()
        self.assertEqual(66, self.run_script().returncode)
        self.assertNotIn('M3Q_STAGE_OK', self.run_script().stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
