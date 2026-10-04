#!/usr/bin/env python3
"""Check packaged ARM64 driver/daemon pairs without executing them."""
import hashlib
import json
from pathlib import Path
import unittest
import zlib

from prepare_m3q_helpers import VERSIONS, UAPI_VERSION, LIBRARIES

PROJECT = Path(__file__).resolve().parents[1]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def extract_driver(daemon, expected):
    """Recover a hash-pinned rust-embed raw-deflate asset without executing it."""
    view = memoryview(daemon)
    limit = expected["size"] + 1
    for offset in range(len(view) - 64):
        if (view[offset] & 6) == 6:  # reserved deflate block type
            continue
        try:
            header = zlib.decompressobj(-15).decompress(view[offset:offset + 1024], 64)
        except zlib.error:
            continue
        if not (header.startswith(b"\x7fELF\x02\x01\x01")
                and header[16:20] == b"\x01\x00\xb7\x00"):
            continue
        stream = zlib.decompressobj(-15)
        try:
            data = stream.decompress(view[offset:], limit)
        except zlib.error:
            continue
        if (stream.eof and len(data) == expected["size"]
                and sha256(data) == expected["sha256"]):
            return data
    raise ValueError("The exact ARM64 driver was not found in the daemon")



class BackendBundleTests(unittest.TestCase):
    def test_driver_and_daemon_provenance(self):
        for flavor, version in VERSIONS.items():
            with self.subTest(flavor=flavor):
                folder = PROJECT / "backends" / flavor
                manifest = json.loads((folder / "build-manifest.json").read_text())
                self.assertEqual(manifest["driver_version"], version)
                self.assertEqual(manifest["uapi_version"], UAPI_VERSION)
                self.assertEqual(manifest["manager_required_uapi"], UAPI_VERSION)
                data = (PROJECT / "app/src/main/assets/azhl" / flavor / "ksud").read_bytes()
                self.assertEqual(manifest["outputs"]["ksud"], {"size": len(data), "sha256": sha256(data)})
                self.assertEqual(data[:6], b"\x7fELF\x02\x01")
                self.assertEqual(data[18:20], b"\xb7\x00")
                self.assertIn(manifest["daemon_version"].encode(), data)
                helper = (PROJECT / "app/src/main/jniLibs/arm64-v8a" / LIBRARIES[flavor]).read_bytes()
                self.assertEqual(manifest["outputs"]["helper"], {"size": len(helper), "sha256": sha256(helper)})
                record = manifest["rebased_patch"]
                patch = (folder / record["path"]).read_bytes()
                self.assertEqual(record["size"], len(patch))
                self.assertEqual(record["sha256"], sha256(patch))
                # The UAPI comes from upstream, not a local header-only override.
                self.assertNotIn(b"diff --git a/uapi/", patch)

    def test_matching_built_driver_is_embedded_in_each_daemon(self):
        for flavor in VERSIONS:
            with self.subTest(flavor=flavor):
                manifest = json.loads((PROJECT / "backends" / flavor / "build-manifest.json").read_text())
                data = (PROJECT / "app/src/main/assets/azhl" / flavor / "ksud").read_bytes()
                expected = manifest["outputs"]["kernelsu.ko"]
                driver = extract_driver(data, expected)
                self.assertEqual(len(driver), expected["size"])
                self.assertEqual(sha256(driver), expected["sha256"])
                self.assertIn(b"vermagic=", driver)
                self.assertIn(b"name=kernelsu", driver)


if __name__ == "__main__":
    unittest.main(verbosity=2)
