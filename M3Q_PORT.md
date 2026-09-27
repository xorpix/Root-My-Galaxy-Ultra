# M3Q AZHL integration — v6

The corrected wrapper is implemented. It remains hardware-untested. No APK was
built here. The extracted native exploit bytes are unchanged.

## Evidence and cause

Original APK SHA-256:
f6062794f8eedd9a5fed5be58a3c6efaadc33a94247ebdeb7025fc0dfe2be700

The original M3qRootEngine and firmware catalog are in classes.dex. The catalog
maps this exact international AZHL build to the LEGACY/base M3Q payload. The
old Muze wrapper ran identity, tracefs, walk, pipei-single and root-single as
separate LD_PRELOAD shells. That was not the original engine's normal flow.
The walk diagnostic writes boot_id; the later stages used mismatched allocator
flags. M3Q_WORKSPACE_ATTEMPTS=72 did not satisfy the allocator dispatch gate.
The supplied logs fail before a backend load and do not prove a KernelSU conflict.
A native crash record would still be needed to establish the exact panic cause.

## Implemented flow

- Verify installed native payload, selected helper and selected daemon against
  pinned sizes/hashes. APK packaging preserves their bytes (keepDebugSymbols).
- Require shell-mode Shizuku and reject detected existing root.
- Read BOOT_COUNT and boot_id; refuse an unreadable prior loader receipt. Save
  a synced app-private boot-count receipt visible across processes; atomically
  claim both the boot count and the earlier azhl-UUID claim name. No auto retry.
- Shizuku invokes `<helper> --run-payload <payload> <helper>` once, working in
  /data/local/tmp. M3qLaunch contains the recovered tracefs environment and sets
  M3Q_APP_UID to Process.myUid(), never to the shell UID. No diagnostic stages
  precede the launch. Root launch has a 600-second cap within the total limit.
- The APP process invokes helper -c through the temporary root server. The
  original server authenticates SO_PEERCRED against the designated application
  UID; Shizuku management requests would be rejected.
- Verify temporary UID 0, then require the native helper's explicit no-driver
  result before preparing a backend. Unexpected/active drivers stop loading.
- Preserve module contents. Set disable markers on backend change, unknown
  installed daemon or the user's disable option. Hash-check and atomically
  stage both original daemon paths; write a PREPARATION receipt, not success.
- Ask app-UID helper --late-load exactly once, with a 180-second cap within the
  total limit. The native helper removes the bootstrap socket after success.
- Ask app-UID helper --ksu-info directly, freshly. Only the selected version,
  UAPI 4 and required flags pass. Only this result records installation success.
- Timeouts/cancellation preserve the boot guard and native files. Automatic
  and manual app cleanup stand down for the claimed boot. No hot-unload or
  automatic destructive cleanup is attempted.

## Backend helper variants

KernelSU 32636 uses the original helper unchanged. Next 33294 and ReSukiSU
35171 helpers change exactly three ARM64 instructions: two expected/printed
version constants and execl's argument-list terminator before --package-name.
This lets each fork use its compiled manager default. Actual version comparison,
UAPI check, flags check and ioctl remain intact. No success is spoofed.

Reproduce with `python tools/prepare_m3q_helpers.py`. See
app/src/main/assets/m3q/helper-variants.json for every hash and byte edit.
JNI daemon copies match assets/azhl/*/ksud byte-for-byte. No Rust/kernel build
is required to build the Android app. Both fork sources report UAPI 4; that
alone does not guarantee live late-load, manager authorization or module support.

## Validation

`python tools/test_m3q_host.py` checks original DEX environment equivalence,
binary patch boundaries, rejected inputs, daemon hashes and module preparation
in isolated temporary directories. The shell cases require a POSIX shell.

`python tools/check_m3q_kotlin.py <compiler-jars-directory>` compiles the actual
runner, launch and guard with Kotlin 2.4.0 and test-only Android/transport stubs,
then simulates success and refusal paths. It also parses production/test Kotlin
files for syntax errors. This is NOT Android/Compose type checking. The compiler
jars are not included; ordinary users can use the Gradle tasks in BUILD_WINDOWS.

The former incomplete-checkpoint execution guard is removed because the new
flow is now integrated. The old M3qStage implementation is removed. There is no
legacy diagnostic fallback if the new launch fails. The full Gradle suite,
Android native build, APK assembly and phone tests remain unperformed here.
