#!/usr/bin/env python3
"""Reproduce the documented 2026-11-03 findings; MATCH does not mean fixed."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

EXPECTED_ARCHIVE = "953216c328b35ab9bf5ebb7599d0f9a5627565276a0ea340d2be1372a1fddd46"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sanitizers", action="store_true")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    archive = args.archive or repo / "backends/susfs/source.tar.gz"
    if hashlib.sha256(archive.read_bytes()).hexdigest() != EXPECTED_ARCHIVE:
        parser.error("Source differs from the audited snapshot; use the pinned archive, not an updated bundle.")
    fixtures = repo / "tools/susfs_audit_20261103"
    with tempfile.TemporaryDirectory(prefix="rmgu-susfs-audit-") as temporary:
        source = Path(temporary)
        with tarfile.open(archive, "r:gz") as bundle:
            bundle.extractall(source, filter="data")
        command = [sys.executable, str(fixtures / "run.py"), "--source-dir", str(source / "susfs"),
                   "--output", str(args.output.resolve())]
        if args.sanitizers:
            command.append("--sanitizers")
        return subprocess.run(command, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
