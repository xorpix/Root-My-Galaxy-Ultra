# October 5 backend update kit

**Status: source/build kit; no newly compiled drivers or daemons are included.**
The local build environment blocked the kernel-toolchain download. Native builds,
the end-to-end CI workflow and phone validation have not been completed here.
Installing this kit alone leaves the app's current runtime assets and supported
devices unchanged. Only a successful build produces the separate runtime patch.

Base app commit: `5287e5bb112ef33143ba23457c05255e5498cf27`.
Do not combine this with the earlier incomplete BakaSU 35207 source patch.

| Backend | Bundled now | Build target | UAPI | Action |
|---|---|---|---|---|
| KernelSU | 32657 | 32661 | 5 | Rebuild driver and daemon together |
| KernelSU-Next | 33319 | 33319 | 5 | Retain the verified existing pair |
| ReSukiSU → BakaSU | 35203 | 35212 | 5 | Rebuild both and migrate manager integration |

Revisions and toolchain versions are fixed in `targets.json`. These are the
official branch heads checked on **2026-10-05**, not a moving “latest” download.
The script refuses incompatible bases, missing outputs and mixed driver/daemon
pairs instead of changing runtime version checks ahead of the binaries.

## Build using GitHub Actions

1. Apply this kit to the base above and commit the kit files. Push your commit to
   your repository. The script requires a clean committed checkout.
2. Open **Actions → Build matched backend update → Run workflow**, selecting
   that branch. If this is a new workflow, GitHub may require it on your default
   branch before displaying the manual-run button.
3. Wait for a successful run and download **RMGU-matched-backend-update**.
   On failure, download **RMGU-backend-build-failure** and share its logs.
   There is no usable native update when the workflow fails.
4. Extract the artifact outside the repo. In the same source revision that ran
   the workflow, apply its complete patch (adjust the path):

   ```powershell
   git apply --check ..\RMGU-matched-backend-update\apply-matched-backends.patch
   git apply ..\RMGU-matched-backend-update\apply-matched-backends.patch
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
   ```

5. Review and commit the resulting source/binary changes. Keep `backends/`:
   it records source provenance and is required by the bundle checker.

The workflow has read-only repository permissions. It uploads an artifact;
it does not push commits, publish releases, use signing keys or build an APK.
Your normal local Android SDK/JDK configuration is still needed for step 4.

## Local Linux alternative

Use an x86-64 Linux host (or suitable WSL environment), a clean committed app
checkout, Python 3.12+, Git, make, a C/C++ toolchain, OpenSSL development files,
pkg-config, and network access to GitHub, GHCR, Google, Rust and Cargo sources.
The work directory must be outside the app checkout and have ample free space.
It downloads the existing pinned DDK, NDK and Rust toolchains.

Prepare the DDK link once in an environment without an existing `/opt/ddk`:

```sh
mkdir -p /absolute/work/rmgu-backends/ddk-root/opt/ddk
sudo ln -s /absolute/work/rmgu-backends/ddk-root/opt/ddk /opt/ddk
python3 tools/backend-refresh/refresh.py --work /absolute/work/rmgu-backends
```

Do not overwrite an existing DDK installation. Use a disposable runner instead
if `/opt/ddk` already points elsewhere. Successful outputs are under `result/`;
all build logs are under `logs/`. A failed run should be retried with a new work
directory after the reported problem has been corrected.

## Included changes

- Rebase the existing Samsung compatibility patches onto the pinned official
  KernelSU and BakaSU sources. KernelSU's four new commits do not alter kernel
  source; rebuilding still keeps its reported version aligned with its daemon.
- Carry BakaSU's rename through the manager package, repository and display name.
  Correct two remaining upstream daemon defaults to `org.bakasu.bakasu`.
  Retain the internal `resukisu` id and legacy manager recognition.
- Avoid offering the old UAPI 4 ReSukiSU rc3 APK as BakaSU's default download.
- Verify the exact compiled kernel module embedded inside each ARM64 daemon;
  update catalog hashes, generated helper hashes and runtime expectations together.
- Restore per-backend build provenance that was removed from the public tree.
- Record SM-S948W / S948WVLU4BZID as a candidate, without adding a root profile.

The actual M3Q/GhostLock and DirtyFrag exploit code, timing, retry policy,
supported firmware identities and module reset policy remain unchanged. The
BakaSU DirtyFrag bridge receives only a checked manager-package data-string
replacement; its executable bytes and command logic are preserved.

## Device report

See `docs/device-candidates/SM-S948W-BZID.md`. The report establishes identity,
not backend compatibility. Its kernel is a different vendor build from the
supported SM-S948B/BZIG build. Do not lift the model check based on this report.
