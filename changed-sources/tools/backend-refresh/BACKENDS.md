# Bundled backends

This file is installed in `backends/README.md` only after the matching native
builds and staging checks succeed. In the build kit it describes the target
bundle, not binaries already delivered.

| Backend | Driver / UAPI | Upstream revision | Daemon version |
|---|---|---|---|
| KernelSU | 32661 / 5 | [08b2e9e4](https://github.com/tiann/KernelSU/commit/08b2e9e451325ebe506c273cfb0fde17d18f592f) | 3.3.0-60-g08b2e9e4 |
| KernelSU-Next | 33319 / 5 | [9ba1a51e](https://github.com/KernelSU-Next/KernelSU-Next/commit/9ba1a51e46d0e4a88ba502a80eda1351a6ce1cd8) | 3.4.0-25-g9ba1a51e |
| BakaSU (formerly ReSukiSU) | 35212 / 5 | [e5423590](https://github.com/Baka-SU/BakaSU/commit/e5423590bec3e24daffa4e9555c9592071319c68) | 4.2.0-rc3-41-ge5423590 |

KernelSU-Next has no new upstream commits in the October 5 check. Its binaries
and build provenance are retained from the previous bundle. Each backend folder
contains a `build-manifest.json` and its complete Samsung compatibility patch.
Manifests record actual output hashes and the exact patched source hashes.
The `resukisu` internal identifier is retained for settings and file paths.

## Manager compatibility

Use a manager built for **UAPI 5**. Older UAPI 4 APKs can lack working control
operations even if their release tag resembles the daemon's tag. BakaSU's
published package is `org.bakasu.bakasu`; legacy ReSukiSU packages remain
recognizable, but recognition does not certify their protocol compatibility.

At the October 5 review, the old ReSukiSU rc3 release APK was still UAPI 4.
BakaSU's default download opens its [official builds](https://github.com/Baka-SU/BakaSU/actions)
instead of linking that old APK. Obtain a matching official UAPI 5 manager.
The version picker can still expose older release assets. KernelSU and Next's
existing release links also require checking the manager's reported UAPI.

## Reproduction and limits

See `tools/backend-refresh/README.md` for the pinned build workflow. The kernel
uses the existing Android 16 / 6.12 DDK and Samsung compatibility changes;
this update does not introduce a new rooting method or add SUSFS.
The generated patch includes both rebuilt daemons and their embedded drivers,
helper metadata, runtime version checks, manager naming and source provenance.
It does not include an APK.

The host checks recover the exact driver from each daemon, compare hashes,
check helper/catalog consistency, and confirm firmware and exploit source
preservation. They do not constitute phone testing, Android type checking,
or proof that every manager/module feature works.

After building and installing the APK, fully reboot before activation so the
new driver is loaded. Test the firmware/backend combination on matching hardware
before describing it as validated. SM-S948W/BZID remains an unverified candidate.
