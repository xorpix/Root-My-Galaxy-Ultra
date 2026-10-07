# S26 Ultra family support

Regional and carrier variants share the bundled DirtyFrag route when their
detected device and kernel match its build requirements. Each backend keeps its
own verified daemon and bridge. Family recognition enables experimental root
attempts; it does not claim that every firmware or vendor kernel has been tested.

## Eligibility

| Field | Requirement |
|---|---|
| Manufacturer | Samsung |
| Model | SM-S948 plus a one- or two-character regional suffix, optionally /DS; or SC-53G / SCG37 |
| Device codename | m3q |
| Architecture / ABI | aarch64 / arm64-v8a |
| Page size | 4096 bytes |
| Android SDK | API 36 or newer |
| Kernel | Full GKI release in the android16 / Linux 6.12 family |
| Firmware incremental | Non-empty, captured from the running phone |

This recognizes B, U, U1, W, N, 0, 0V and Japanese SM variants through the same
family rule. Samsung's [Japanese device manuals](https://www.samsung.com/jp/support/mobile-devices/how-to-check-galaxy-online-manual/)
list SC-53G and SCG37 as S26 Ultra carrier models.
Samsung's [Knox device list](https://www.samsungknox.com/en/knox-platform/supported-devices/knox-device-attestation)
documents additional regional names.

The Android generation in the **kernel release** selects the native bridge.
Android 17 user space can still run an android16 / Linux 6.12 kernel. The SDK
number alone must not select an android17 / Linux 6.18 blob. Other kernel
families and 16 KB pages need matching bridges and backend builds before use.

The exact SM-S948B / S948BXXS4AZHL identity continues to select M3Q/GhostLock
with Shizuku. All other eligible identities select DirtyFrag directly.

## Profiles and payload integrity

- The recorded BZIG and BZID entries retain their existing IDs and inventory facts.
- Other eligible devices receive one generated profile per backend. Each records
  the actual model, device, incremental, full kernel release, SDK, ABI and pages.
- Profile IDs hash an ordered representation of those fields. They are stable
  across launches and change when the firmware or kernel identity changes.
- Generated profiles reuse the closed APK catalog's backend paths, versions,
  checksums, native marker and execution policy. Remote feeds cannot replace them.
- Staging rebuilds the current profile and compares every field before writing
  the daemon. Cached selections from another model, firmware or kernel are refused;
  a fresh run resolves the new device identity.
- Runs log the actual model, incremental and full kernel release before staging.
  Settings and recovery use the same family eligibility decision as installation.

The weekly refresh updates the shared backend hashes and retains the recorded
catalog profiles. New regional firmware does not require a catalog edit; a new
kernel family still requires matching native builds.

## Validation

Host tests cover regional/carrier names, all three backend choices, immutable
payload staging, generated profile/cache round trips, changed-firmware refusal,
unsupported hardware/kernel rejection and the known AZHL/recorded DF routes.
These checks do not run the exploit or establish hardware compatibility.

On each new model/build, begin from a full reboot, choose one backend and run
from Home with modules disabled. Export the run history, confirm the selected
backend and UAPI 5 in its matching manager, and check a superuser shell. Test
ordinary root first, then modules and root-on-boot. Fully reboot before another
backend or a retry. Report the complete model/firmware/kernel identity with
the logs if bootstrap or backend loading fails.

A manufacturer mitigation can block DirtyFrag even when the family matches.
DFRoot success is useful device evidence, but RMGU's particular backend driver
and daemon still need verification on that vendor kernel.
