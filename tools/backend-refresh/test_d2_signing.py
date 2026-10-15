"""The weekly signing job may change exactly two nested-helper ZIP entries."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_d2_helper import embed_signed_helper


class D2SigningTests(unittest.TestCase):
    def test_repack_preserves_every_other_entry_and_helper_source_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            old, new, helper = d / 'old.apk', d / 'new.apk', d / 'helper.apk'
            metadata = dict(api=1, package='dev.experimental.azhlroot.d2helper', version=1,
                            source='a' * 64, sha256='old', size=3)
            contents = {'classes.dex': b'controller bytecode', 'lib/arm64-v8a/guard.so': b'native',
                        'AndroidManifest.xml': b'manifest', 'assets/d2/helper.apk': b'old',
                        'assets/d2/helper.json': json.dumps(metadata).encode()}
            with zipfile.ZipFile(old, 'w') as z:
                for name, data in contents.items():
                    z.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
            helper.write_bytes(b'freshly rebuilt and production-signed companion')
            embed_signed_helper(old, new, helper)
            with zipfile.ZipFile(old) as original, zipfile.ZipFile(new) as rebuilt:
                self.assertEqual(original.namelist(), rebuilt.namelist())
                for name, data in contents.items():
                    if not name.startswith('assets/d2/'):
                        self.assertEqual(data, rebuilt.read(name))
                    self.assertEqual(original.getinfo(name).compress_type, rebuilt.getinfo(name).compress_type)
                after = json.loads(rebuilt.read('assets/d2/helper.json'))
                self.assertEqual(helper.read_bytes(), rebuilt.read('assets/d2/helper.apk'))
                self.assertEqual(hashlib.sha256(helper.read_bytes()).hexdigest(), after.pop('sha256'))
                self.assertEqual(helper.stat().st_size, after.pop('size'))
                self.assertEqual({k: v for k, v in metadata.items() if k not in ('sha256', 'size')}, after)
