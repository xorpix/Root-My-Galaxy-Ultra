#!/usr/bin/env python3
"""Validate the pinned upstream mount patch, its reproducers and a locking negative control."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

UPSTREAM_COMMIT = "6c4ab849a0b6fd9b039552da8664cea77cd7743d"
UPSTREAM_MOUNT_BLOB = "9242627f627ff58ccea72c18e1711c4df29aaeaf"
EXPECTED_REGRESSIONS = {"bind-parent", "shared-source", "different-device", "long-source",
                        "null-root", "drop-reuse", "concurrent-reuse"}


def run_suite(runner, source, output, sanitizers=False, expected_failures=frozenset()):
    output.unlink(missing_ok=True)
    command = ["python3", str(runner), "--source-dir", str(source), "--json", str(output)]
    if sanitizers:
        command.append("--sanitizers")
    result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    if not output.is_file():
        raise RuntimeError(result.stdout + "\n" + result.stderr)
    report = json.loads(output.read_text())
    failures = {item["case"] for item in report["results"] if item["exit_code"]}
    if failures != set(expected_failures) or result.returncode != bool(expected_failures):
        raise RuntimeError(str(command) + "\n" + result.stdout + "\n" + result.stderr)
    print("PASS " + output.stem + ": " +
          (", ".join(sorted(failures)) + " reproduced" if failures else "10 cases passed"))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, type=Path,
                        help="Checkout of upstream " + UPSTREAM_COMMIT)
    parser.add_argument("--patch", type=Path,
                        default=Path(__file__).resolve().parents[1] /
                        "docs/upstream-patches/susfs-167-mount-identity.patch")
    parser.add_argument("--evidence-dir", required=True, type=Path)
    args = parser.parse_args()
    original = (args.upstream / "kernel/sus_mount.c").read_bytes()
    blob = hashlib.sha1(b"blob " + str(len(original)).encode() + b"\0" + original).hexdigest()
    if blob != UPSTREAM_MOUNT_BLOB:
        raise RuntimeError("Upstream mount source differs from the reviewed revision")
    evidence = args.evidence_dir.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="rmgu-upstream-mount-") as temporary:
        temporary = Path(temporary)
        before, fixed, unlocked = [temporary / name for name in ("before", "fixed", "unlocked")]
        for directory in (before, fixed, unlocked):
            (directory / "kernel").mkdir(parents=True)
            (directory / "kernel/sus_mount.c").write_bytes(original)
        subprocess.run(["git", "init", "-q", str(fixed)], check=True)
        subprocess.run(["git", "apply", "--check", str(args.patch.resolve())], cwd=fixed, check=True)
        subprocess.run(["git", "apply", str(args.patch.resolve())], cwd=fixed, check=True)
        runner = fixed / "tests/mount_identity/run.py"
        run_suite(runner, before, evidence / "upstream-before.json",
                  expected_failures=EXPECTED_REGRESSIONS)
        run_suite(runner, fixed, evidence / "upstream-fixed.json")
        run_suite(runner, fixed, evidence / "upstream-fixed-sanitizers.json", sanitizers=True)
        text = (fixed / "kernel/sus_mount.c").read_text()
        start = text.index("static bool sus_mount_ident_match(struct mount *r)\n")
        end = text.index("\n/**", start)
        fragment = text[start:end]
        lock = "    spin_lock_irqsave(&ident_lock, flags);"
        unlock = "    spin_unlock_irqrestore(&ident_lock, flags);"
        if fragment.count(lock) != 1 or fragment.count(unlock) != 1:
            raise RuntimeError("Locking mutation no longer identifies the reviewed reader")
        fragment = fragment.replace(lock, "    (void)flags;").replace(unlock, "")
        (unlocked / "kernel/sus_mount.c").write_text(text[:start] + fragment + text[end:])
        run_suite(runner, unlocked, evidence / "unlocked-control.json",
                  expected_failures={"concurrent-reuse"})
    print("PASS patch application, before/after regressions and reader-lock negative control")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
