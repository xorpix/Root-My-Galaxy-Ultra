# Backend update checkpoint (app 1.1.3)

This checkpoint refreshes all three driver/daemon pairs while keeping the
original M3Q and DirtyFrag exploit implementations and bridge modules intact.

| Backend | Previous driver | Bundled driver / UAPI | Pinned upstream revision |
|---|---:|---:|---|
| KernelSU | 32636 | 32653 / 4 | `08a3b087e49227c8a6731c5f1114998b5e25255b` |
| KernelSU-Next | 33294 | 33313 / 4 | `2b31f7185460e99bc3896a639e9073b1c354ee0d` |
| ReSukiSU | 35171 | 35195 / 4 | `34210a4dec297cb48ce4bfa1c5ba50a2f7d22641` |

These are development revisions after the latest stable/release-candidate
tags. The manager APKs have their own versions and are not rebuilt here.
The ReSukiSU binaries are identical to the preceding 35195 checkpoint.

## What changed

- KernelSU and KernelSU-Next include the upstream soft-reboot service-waiting
  changes, including the embedded waitsys helper, and SELinux policy fixes.
- Each freshly built ARM64 driver is embedded in its matching daemon before
  the daemon is compiled. The native app copies and both firmware catalogs
  refer to exactly those daemon bytes.
- Driver expectations and helper checksums are updated. The KernelSU helper
  changes only two version constants; Next retains its existing manager
  argument adjustment and updates its two version constants.
- App version is 1.1.3. No root-launch timing, retry policy, exploit payload,
  firmware whitelist, boot-image modification, or module-reset policy changed.

## Source provenance

KernelSU uses official `tiann/KernelSU` source and the original companion
`KernelSU-v3.3.0-samsung-kdp-rkp-defex.patch`, pinned to
`rushiranpise/Root-My-Galaxy-Payloads` revision
`7f1490bf0b65e1cfd1ef9f2f2fb0485dc4186dae`. The previous KernelSU backend was an
inherited M3Q binary based on upstream `26a09914`; its exact custom source
tree was not available. This rebuild uses the documented companion Samsung
adaptations, so it is not claimed to reproduce that old binary byte-for-byte.

KernelSU-Next uses the saved custom patch against
`1a879d6a866f80b1fa1c1009a2ffa747873cbb5e`, plus its four Samsung compatibility
files recovered unchanged from the matching original companion patch at the
same pinned repository revision. The include-area conflict in both backends
was resolved by retaining the upstream RISC-V guard and the compatibility
includes. KernelSU Rust files were formatted as required by upstream.

Each backend directory contains a complete patch against its pinned upstream
revision and a build manifest with artifact checksums. ReSukiSU has its own
detailed provenance in `resukisu/README.md`.

## Build the app

The daemon binaries are already included. Use the normal Windows build:

```powershell
.\gradlew.bat :app:assembleDebug
```

Use your existing JDK, SDK/NDK, local.properties and signing configuration.
Install the result, perform a full reboot, then activate the selected backend
through the app. The currently loaded driver is not replaced just by updating
the APK. Keep the previous working APK for rollback.

## Rebuild KernelSU or KernelSU-Next (optional, Linux)

Use a full-history clone of the appropriate official repository. Check out
the exact revision from the table and apply the corresponding patch in this
directory. The build helper verifies both the SHA and upstream commit count;
shallow clones do not produce the intended version numbers.

Example from the app source root:

```sh
RMG_TOOLS="$PWD/../backend-build-tools"
mkdir -p "$RMG_TOOLS"
git clone https://github.com/tiann/KernelSU.git "$RMG_TOOLS/kernelsu"
git -C "$RMG_TOOLS/kernelsu" checkout --detach 08a3b087e49227c8a6731c5f1114998b5e25255b
git -C "$RMG_TOOLS/kernelsu" apply "$PWD/backends/kernelsu/samsung-compat-32653.patch"
python tools/provision_ddk.py "$RMG_TOOLS"
python tools/provision_android.py "$RMG_TOOLS" --ndk-only
```

The DDK helper needs `/opt/ddk` to point at the pinned Android 16 / 6.12 DDK.
Use a build container or arrange permission for that path. Install rustup
with CARGO_HOME set to `$RMG_TOOLS/cargo` and RUSTUP_HOME set to
`$RMG_TOOLS/rustup`. Then install Rust 1.98.1 with the Android target,
Clippy and rustfmt. ReSukiSU instead uses nightly-2026-09-25.

```sh
CARGO_HOME="$RMG_TOOLS/cargo" RUSTUP_HOME="$RMG_TOOLS/rustup" \
  "$RMG_TOOLS/cargo/bin/rustup" toolchain install 1.98.1 --profile minimal \
  --component clippy,rustfmt --target aarch64-linux-android
python tools/build_backend.py "$RMG_TOOLS" kernelsu
```

For Next, clone `https://github.com/KernelSU-Next/KernelSU-Next.git` into
`$RMG_TOOLS/kernelsu-next`, use the pinned Next SHA and
`kernelsu-next/samsung-compat-33313.patch`, and pass `kernelsu-next` to the
build helper. The helper prints the daemon output path and embeds the built
module automatically. Record any rebuilt daemon's actual size/hash in both
catalogs, then run `python tools/prepare_m3q_helpers.py` to refresh native
copies and helper metadata.

## Validation scope

Native driver and daemon builds are checked. KernelSU also requires Android
target `cargo ndk` check, Clippy and rustfmt; this checkpoint ran those with
cargo-ndk 4.1.2, Rust 1.98.1 and NDK r28c at API 26. See the checkpoint's
validation logs for results and bundle/patch checks.

No APK was assembled here and no driver was loaded on a phone. The kernel
builds use the existing Android 16 / 6.12 DDK; a successful compile does not
establish BZIG/AZHL runtime compatibility. Test each backend separately and
export the app log if activation fails. An upstream soft-reboot fix is not
proof that a particular module issue is resolved.

The pre-existing broad Kotlin host fixture has missing AppPreferences and
PartitionReadOnly stubs; the preceding checkpoint reproduced that failure
on the unmodified app baseline. It is not counted as a passing test.
