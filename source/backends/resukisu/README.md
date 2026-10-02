# ReSukiSU 35195 / UAPI 4

This checkpoint updates the ReSukiSU kernel module and daemon together. Both
AZHL and BZIG profiles reference the same new daemon. The app's driver-version
checks, packaged helper constants and checksum catalogs are updated with it.
This directory records the ReSukiSU portion of the combined app 1.1.3 checkpoint.

The original M3Q exploit payload, DirtyFrag implementation and bridge modules
are unchanged. KernelSU and KernelSU-Next updates are documented in the parent
directory. The M3Q ReSukiSU
helper's existing version constants were regenerated for 35195; no exploit
timing, retries, memory layout, or root-launch behavior was changed.

## Source provenance

- Upstream: https://github.com/ReSukiSU/ReSukiSU
- Pinned revision: `34210a4dec297cb48ce4bfa1c5ba50a2f7d22641`.
- Driver: 35195; UAPI: 4. This is a development snapshot after the
  `v4.2.0-rc3` tag, not a new stable release.
- Previous bundled revision: `239e1e8871b8fcd51a6e5b3002e0ba522fdd99fb`
  (35171). Existing Samsung and late-load adaptations were rebased onto the
  new revision; upstream daemon fixes are included.
- The old saved patch omitted four newly added Samsung compatibility files.
  Those files were recovered from the original companion patch at
  `rushiranpise/Root-My-Galaxy-Payloads`, revision
  `7f1490bf0b65e1cfd1ef9f2f2fb0485dc4186dae`, path
  `kernelsu/patches/ReSukiSU-v4.2.0-rc3-samsung-kdp-rkp-defex.patch`.
- The single merge conflict involved the upstream RISC-V header guard and
  the existing Samsung include block; both were retained.

`samsung-compat-35195.patch` is the complete patch against the pinned upstream
revision, including the four compatibility files. `build-manifest.json`
records the toolchains, input revisions and output hashes.

## Build the Android app

The native binaries are already bundled. Build the app with the existing
Android SDK/NDK and JDK setup:

```powershell
.\gradlew.bat :app:assembleDebug
```

No backend rebuild or manual module flashing is needed to assemble the app.
After installing the APK, perform a full reboot and use the app's normal
ReSukiSU activation. Updating the app does not replace a driver that is
already loaded in the current boot. Keep the previous working APK for rollback.

Expected after successful activation:

- Kernel driver: `v4.2.0-rc3-34210a4d-dirty@ReSukiSU (35195/4)`.
- Daemon: `ksud 4.2.0-rc3-24-g34210a4d (uapi: 4)`.
- The manager has its own version; its number need not become 35195 when
  updating this app. This checkpoint does not update the manager APK.

Read the installed daemon version with:

```sh
su -c '/data/adb/ksud --version'
```

## Rebuild the native backend (Linux)

This is optional. The build uses the existing Android 16 / 6.12 DDK, ARM64,
Clang r536225, NDK r28c (API 26), and Rust nightly-2026-09-25. A full-history
Git clone is required because upstream derives 35195 from the commit count.

From the app source root, choose an empty tools directory:

```sh
RMG_TOOLS="$PWD/../resukisu-build-tools"
mkdir -p "$RMG_TOOLS"
git clone https://github.com/ReSukiSU/ReSukiSU.git "$RMG_TOOLS/resukisu"
git -C "$RMG_TOOLS/resukisu" checkout --detach 34210a4dec297cb48ce4bfa1c5ba50a2f7d22641
git -C "$RMG_TOOLS/resukisu" apply "$PWD/backends/resukisu/samsung-compat-35195.patch"
python tools/provision_ddk.py "$RMG_TOOLS"
python tools/provision_android.py "$RMG_TOOLS" --ndk-only
```

The DDK provisioning helper creates `/opt/ddk`; arrange permission for that
path or use a build container. It must point at the pinned DDK, not an
unrelated installation. Ensure extracted Clang/Rust executables retain their
execute permissions. Install rustup into `$RMG_TOOLS/cargo` and
`$RMG_TOOLS/rustup`, then install the pinned Rust toolchain and target:

```sh
CARGO_HOME="$RMG_TOOLS/cargo" RUSTUP_HOME="$RMG_TOOLS/rustup" \
  "$RMG_TOOLS/cargo/bin/rustup" toolchain install nightly-2026-09-25 \
  --profile minimal --target aarch64-linux-android
python tools/build_backend.py "$RMG_TOOLS" resukisu
```

The build copies the freshly built `kernel/kernelsu.ko` into the daemon's
embedded assets before building `userspace/ksud`. Output:
`$RMG_TOOLS/resukisu/userspace/ksud/target/aarch64-linux-android/release/ksud`.
A rebuilt daemon needs its actual size/hash recorded in both catalogs and
identical copies in `assets/azhl/resukisu/ksud` and
`jniLibs/arm64-v8a/libm3qksud_resukisu.so`.

## Validation and limits

The kernel module and daemon compiled successfully. Six focused bundle tests
passed, covering helper edits, checksums, both firmware catalogs and runtime
version expectations. Patch application and unchanged exploit files were
also checked.

The existing Kotlin host harness fails on missing `AppPreferences` and
`PartitionReadOnly` test stubs in both the original source and this update;
it is not a passing validation result. No APK was assembled, no module was
loaded onto a phone, and BZIG/AZHL runtime compatibility is not yet verified.
In particular, the build uses the existing 6.12 DDK rather than a new BZIG
kernel tree. A successful compile is not proof of compatibility with every
Samsung kernel build.
