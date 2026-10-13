#!/usr/bin/env python3
"""Check the separate diagnostic APK's identity, signature, permissions, and native contents."""
import argparse
import hashlib
import os
import re
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise SystemExit("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    build_tools = Path(sdk) / "build-tools" / "36.0.0"

    def run(tool, *argv):
        result = subprocess.run([str(build_tools / tool), *map(str, argv)],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.returncode:
            raise SystemExit(f"{tool} failed: {result.stdout}")
        return result.stdout

    signature = run("apksigner", "verify", "--verbose", args.apk)
    run("zipalign", "-c", "-P", "16", "4", args.apk)
    badging = run("aapt2", "dump", "badging", args.apk)
    if "package: name='dev.experimental.rmgu.bootprobe'" not in badging:
        raise SystemExit("Unexpected package identity")
    if "versionCode='3'" not in badging or "versionName='0.2.0'" not in badging:
        raise SystemExit("Unexpected diagnostic version")
    if "application-debuggable" in badging:
        raise SystemExit("Diagnostic release APK must not be debuggable")
    permissions = {line.split("name='")[1].split("'")[0]
                   for line in badging.splitlines() if line.startswith("uses-permission:")}
    if permissions != {"android.permission.INTERNET", "android.permission.RECEIVE_BOOT_COMPLETED"}:
        raise SystemExit(f"Unexpected permissions: {sorted(permissions)}")
    manifest = run("aapt2", "dump", "xmltree", args.apk, "--file", "AndroidManifest.xml")
    if "android.uid.system" in manifest or "sharedUserId" in manifest:
        raise SystemExit("Diagnostic probe must not use a shared/system UID")
    for expected in ["ProbeAccessibilityService", "ProbeBootReceiver", "LOCKED_BOOT_COMPLETED", "directBootAware"]:
        if expected not in manifest:
            raise SystemExit(f"Missing manifest component: {expected}")
    if "BIND_ACCESSIBILITY_SERVICE" not in manifest or "android.accessibilityservice" not in manifest:
        raise SystemExit("Missing protected accessibility binding")
    if "BIND_WALLPAPER" in manifest or "ProbeWallpaperService" in manifest:
        raise SystemExit("Wallpaper component must not be present")
    # AAPT2 may shorten res/xml/probe_accessibility.xml to a path such as res/a.xml.
    # Resolve the compiled resource table rather than assuming its archive filename.
    resources = run("aapt2", "dump", "resources", args.apk)
    entries = re.split(r"(?m)(?=^\s*resource 0x[0-9a-fA-F]+ )", resources)
    matches = [entry for entry in entries if re.search(r"\bxml/probe_accessibility\b", entry)]
    if len(matches) != 1:
        raise SystemExit("Cannot uniquely resolve the compiled accessibility metadata resource")
    resource_id = re.search(r"resource (0x[0-9a-fA-F]+)", matches[0])
    resource_path = re.search(r"\(file\)\s+(\S+)", matches[0])
    if not resource_id or not resource_path or "@" + resource_id[1] not in manifest:
        raise SystemExit("Accessibility metadata resource is not referenced by the manifest")
    accessibility = run("aapt2", "dump", "xmltree", args.apk, "--file", resource_path[1])
    if "E: accessibility-service" not in accessibility:
        raise SystemExit("Manifest metadata must reference an accessibility-service definition")
    for capability in ["canRetrieveWindowContent", "canPerformGestures", "canRequestFilterKeyEvents",
                       "canRequestTouchExplorationMode", "canRequestFingerprintGestures", "isAccessibilityTool"]:
        lines = [line for line in accessibility.splitlines() if capability in line]
        if len(lines) != 1 or "0xffffffff" in lines[0] or "0x0" not in lines[0]:
            raise SystemExit(f"Accessibility capability must be false: {capability}")
    if "accessibilityEventTypes" in accessibility or "accessibilityFlags" in accessibility:
        raise SystemExit("Probe must not subscribe to accessibility events or request flags")
    with zipfile.ZipFile(args.apk) as archive:
        names = set(archive.namelist())
        libs = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        if libs != {"lib/arm64-v8a/libbootprobe.so", "lib/x86_64/libbootprobe.so"}:
            raise SystemExit(f"Unexpected native libraries: {sorted(libs)}")
        if any(name.endswith((".ko", ".p12", ".jks", ".apk")) for name in names):
            raise SystemExit("Unexpected payload or signing material in diagnostic APK")
    args.output.mkdir(parents=True, exist_ok=True)
    destination = args.output / "RMGU-Early-Boot-Probe-0.2.0.apk"
    shutil.copyfile(args.apk, destination)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    (args.output / "RMGU-Early-Boot-Probe-0.2.0.apk.sha256").write_text(f"{digest}  {destination.name}\n")
    (args.output / "apk-validation.txt").write_text(signature + "\n" + badging + "\n" + manifest + "\n" + accessibility)
    print(f"Verified {destination.name}: {digest}")


if __name__ == "__main__":
    main()
