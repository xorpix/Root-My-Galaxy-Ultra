# AZHL development checkpoint

This is unfinished source code, not an installable APK and not a claim of S26 Ultra root compatibility.
Execution on SM-S948B / m3q is deliberately blocked by `AzhlPort.developmentRefusal()` until the native port is complete.

Base: https://github.com/rushiranpise/Root-My-Galaxy-Next

Base commit: `81b82502e3dd223bfa0a1a6c448e1c1339ff7d7b`

## Changes included

- A first-launch choice of KernelSU, KernelSU-Next or ReSukiSU. Saving the choice does not run a payload.
- Settings changes retain the chosen backend and explain that an active backend requires a reboot.
- Payload resolution requires the selected backend. Online, manual, offline and retry runs cannot silently substitute a different backend.
- Each run freezes the requested backend and checks the resolved profile against it before staging.
- An active-root probe and the existing per-boot backend record refuse another load in the same boot. Native enforcement still needs integration.
- Exact firmware requirements cover model, device, incremental firmware, complete kernel release, SDK, ABI and page size. A generic S26 model row cannot enable the port.
- Firmware requirements survive known-good cache and attempted-payload serialization. Unknown cached backend names fail closed.
- The attempted-payload record now retains the declared backend version.
- The development application ID is `dev.experimental.azhlroot`, separate from upstream and M3Q.

No firmware payload was added to the catalog. The upstream native helper is unchanged and is not an AZHL helper.

## Verification completed

37 focused JUnit tests passed using Kotlin 2.4.0, JUnit 4.13.2 and the Android 37 API jar:

- `AzhlPortTest` — exact identity, changed firmware, all backend choices, manual/cache mismatches, active-root refusal, cache round trips, malformed requirements, and the development execution block.
- `KernelSuFlavorTest` — existing backend parsing/release behavior plus strict selection.
- `SupportManifestTest` — existing catalog parser coverage.

The standalone JVM runner generated resource-ID constants only; it used the actual policy, catalog, cache metadata and snapshot source files. It did not exercise Android UI, native execution, Shizuku, an APK build or real hardware. Changed Android XML parses and `git diff --check` passed.

## Inputs needed to resume

The temporary execution workspace was reset while work was paused. The original upload bytes, native adapter edits and downloaded build tools from that attempt are no longer present. Restore these exact inputs:

1. `M3Q-1.0.9-KSU 32636 (1).apk`
2. `Root-S26U-FIXED4.zip`

The following facts were retained in inspection notes and must be rechecked against the restored files:

- Model `SM-S948B`, device `m3q`, firmware `S948BXXS4AZHL`, Android SDK 36, `arm64-v8a`, 4096-byte pages.
- Kernel release `6.12.30-android16-5-pd30ff70-abogkiS948BXXS4AZHL-4k`.
- The ZIP contains `source/ghostlock-azhl/exploit/src/`, with firmware-specific offsets and an Apache license.
- Its loader performs KernelSU late-load before returning. The app must verify that load rather than run a second late-load.
- The working APK carries driver version 32636. Its extracted module SHA-256 was `3a44bc41959ab9ffb94bf202b81bbd5aa5c6d04a335739bb751b69a1770817b6`.
- That reference module reported vermagic `6.12.76-4k SMP preempt mod_unload modversions aarch64`. A release-name match alone is therefore not sufficient evidence of compatibility; inspect the actual loader and ABI.

## Remaining implementation

1. Restore and review the AZHL source and working APK. Recreate the native helper integration with exact identity checks, a persistent one-attempt-per-boot claim, an existing-driver check, and shell UID authentication.
2. Build KernelSU-Next and ReSukiSU modules plus matching userspace daemons for Android 16 / 6.12, using the Samsung KDP/RKP/DEFEX adaptations. Audit imports against the working module; build success does not establish hardware compatibility.
3. Integrate checksum-verified bundled payloads. Freeze backend and expected driver version throughout staging, helper invocation and result verification. Handle module disabling before late-load, including backend transitions.
4. Adapt the app's load and maintenance paths to the AZHL helper protocol. Do not remove the execution block merely to make the model appear supported.
5. Build the complete Android application, check package identity/signing and manager pairing, and test first-launch/settings flows. Disable or adapt upstream self-updates for the separate application ID before distribution.
6. Validate on the actual phone after a fresh reboot, first with the original KernelSU backend and then each alternative. No physical phone is connected to this workspace.

Useful upstream build references from the earlier investigation:

- https://github.com/rushiranpise/Root-My-Galaxy-Payloads at `cc19db9d9779ff577b163c7eb10d8a8a9e7af39f`.
- `kernelsu/patches/KernelSU-Next-v3.4.0-samsung-kdp-rkp-defex.patch`.
- `kernelsu/patches/ReSukiSU-v4.2.0-rc3-samsung-kdp-rkp-defex.patch`.
- `.github/workflows/ksu-build.yml` and the module/daemon version checks in `tools/`.
- Candidate DDK: `ghcr.io/ylarod/ddk-min:android16-6.12-20260828`. Its presence was verified previously; this checkpoint contains no DDK-built modules.

With a complete Android toolchain, the normal focused Gradle test command is:

```sh
./gradlew :app:testDebugUnitTest --tests dev.busung.s25uroot.AzhlPortTest --tests dev.busung.s25uroot.KernelSuFlavorTest --tests dev.busung.s25uroot.SupportManifestTest
```

That Gradle command has not been run in the restored workspace. The successful standalone JVM run is recorded in the archive's `validation/policy-tests.log`.
