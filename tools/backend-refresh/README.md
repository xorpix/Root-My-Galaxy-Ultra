# Weekly backend builds and releases

[Weekly backend release](../../.github/workflows/backend-refresh.yml) checks the
three official source branches every **Monday at 03:17 UTC**:

| Backend | Official source branch | Internal id |
|---|---|---|
| KernelSU | `tiann/KernelSU`, `main` | `kernelsu` |
| KernelSU-Next | `KernelSU-Next/KernelSU-Next`, `dev` | `kernelsu-next` |
| BakaSU | `Baka-SU/BakaSU`, `main` | `resukisu` |

When there are new commits, it snapshots those revisions, builds each changed
kernel driver and daemon as a matched pair, updates the bundled files and helper
version/hash records, runs the bundle checks and Android unit tests, and builds
the release APK. Unchanged backends retain their verified binaries. An unchanged
week uses no native build, creates no commit, and publishes no release.

A fresh job checks the resulting source patch and APK contents, signs the APK
with your existing release key, and verifies its certificate. It then commits
the source update to the default branch and creates the next numeric patch tag
and GitHub release. For example, `1.1.5` becomes `1.1.6`. Existing tags are never
moved or overwritten. The branch and tag are pushed together without force; if
the branch advanced during the build, the run stops so your changes are retained.

The release includes `RootMyGalaxyUltra-VERSION.apk`, `SHA256SUMS`, release notes
with the upstream revisions, and `apply-matched-backends.patch`. The tag points
to the actual updated source, including its bundled binaries and provenance.
These are regular releases. Build results do not claim hardware validation.

## One-time setup

Merge the workflow into the default branch. GitHub only runs scheduled workflows
from that branch. In **Settings → Secrets and variables → Actions**, configure
these repository secrets (the existing release workflow uses these same names):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | Base64 of the existing release keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias for the existing release signing key |
| `KEY_PASSWORD` | Signing key password |

Use the key that signed your previous releases so users can upgrade without
uninstalling. Do not generate a replacement key for this workflow or commit it.
The build job uses a disposable key; the real key is used only in the fresh
signing job. It is not available to upstream kernel/Cargo build commands.

PowerShell, to copy the keystore's base64 to the clipboard for the secret editor:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\release.p12")) | Set-Clipboard
```

Actions must be enabled and allowed to push the default branch and release tags.
The publishing job requests `contents: write`. If branch/tag rules require a PR,
the workflow stops at the push; it does not bypass those rules. No personal
access token or automatic approval of pull requests is required.

Open **Actions → Weekly backend release → Run workflow**, select the default
branch, and leave `publish` checked for a manual run. Uncheck `publish` to build
an update artifact without the signing secrets, git push, or release. That
artifact's `apk-input.apk` has a disposable signature and is not the release APK.
Build-only mode also accepts a non-default branch, so you can test the workflow
on a pull request branch before merging it. Publishing requires the default branch.
GitHub may delay scheduled runs; public-repository schedules can be disabled
after 60 days without repository activity.

## Files to retain

Keep `tools/backend-refresh/`, `tools/build_backend.py`, the provisioner scripts,
the workflow, and **`backends/`** in git. `backends/*/build-manifest.json` and its
referenced patch identify the binaries currently bundled in the app. They are
build inputs, not disposable validation output. The three old `*-previous.json`
files were replaced by these current records; the old fixed `app_base` guard is
no longer used as a recurring baseline. Each run instead binds to its checked-out
source commit and verifies its current records and asset/helper hashes.

The restored 32661/33319/35212 records came from successful Actions run
[37351291263](https://github.com/xorpix/Root-My-Galaxy-Ultra/actions/runs/37351291263).
Their daemon/helper hashes match the binaries committed in version 1.1.5.
Download wrapper patches, old `changed-sources/`, and downloaded validation
folders can be removed after application. Do not remove the retained kit or
its checksum-pinned compatibility patches.

## What stops an automatic release

The existing Samsung compatibility patch must apply to the selected upstream
revision. The pipeline does not invent a rebase if it stops applying. UAPI 5,
upstream version calculation, complete source history, and the helper's existing
version encoding must also remain compatible. A new UAPI requires review of the
app, helper, and control-channel contracts before automation can accept it.

All changed native builds must succeed before any app bundle is staged. The
exact built driver must be recoverable from its daemon. Catalogs, JNI copies,
helper hashes and runtime versions must agree; tests and the APK build must pass.
Firmware/routing, exploit files and bridge binaries remain unchanged. This does
not add firmware/device support, port an exploit, add SUSFS, or retune root attempts.

Failed build logs and test reports are uploaded to the workflow run. If signing
or publication fails, the source patch and any verified signed APK are preserved
as an unpublished artifact. Nothing becomes a published release before the APK
and signature checks pass. If the branch/tag push succeeds but GitHub release
publication then fails, finish the draft release or run the existing **Release
Build** workflow at that version; do not move the tag or force-reset the branch.
A bot push intentionally does not trigger the separate CI/back-merge workflows.
Dispatch **Back-merge** separately if you want to bring this update into `dev`.

After installing an updated APK, fully reboot and activate root to load its
bundled driver. Updating a manager APK alone does not replace the running driver.

## Local inspection

From a clean, committed checkout:

```sh
python3 tools/test_m3q_host.py BundleTests
python3 tools/test_backend_bundle.py
python3 tools/backend-refresh/test_refresh.py
python3 tools/backend-refresh/probe.py --output /tmp/upstream-heads.json
python3 tools/backend-refresh/refresh.py --work /tmp/rmgu-build --snapshot /tmp/upstream-heads.json --discover-only
```

`--discover-only` clones changed sources and checks the UAPI/version rules and
patch application without provisioning compilers or building. Use a fresh work
directory for each run. Omit that flag to build the matched update on a Linux
machine with the pinned DDK/NDK prerequisites; add `--release` to prepare a patch
version bump and release metadata. The workflow provisions these dependencies.
