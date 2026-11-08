# Samsung modules callback safety fix

This change adapts upstream SusFS PR #200 to the existing Samsung source at
`3a65e96f3694f19abd7feb1f905e4794f48d5aee`. The maintainer's root module-listing check passed on 2026-11-08;
release 1.3.4 remains the previous hardware baseline.

The name-based `m_show` registration path is preserved, but filtering stays
inactive until its actual resolved entry matches the independently verified
`modules_op.show` callback. CFI entry stubs are decoded using the existing
validator. A vendor adjustment past just BTI is permitted; PAC or other
adjustments are rejected. Failed validation unregisters the inactive probe.

The handler does not restore the live `seq_file.op` pointer gate that caused
the documented S948B/BZIG root-visibility regression. Instead, callback
ownership is established during initialization. Module names are read through
a bounded nofault snapshot; invalid pointers and unterminated names leave the
kernel's original callback unchanged. Teardown closes admission before
unregistering the probe.

## Reproduction and checks

`native.commit` contains the exact Git commit object. `native.patch` applies
to the accepted baseline. `tools/build_susfs_modules_show.py` verifies the
baseline archive and tree, reconstructs the reviewed candidate tree, and
compiles with the pinned Android 16/6.12 DDK. The ABI check compares all 22
syscall wrappers and existing callback types against 1.3.4, checks landing
instructions, and requires the existing imports and module metadata.

All 66 local host runner executions passed, including ASan/UBSan. The new
modules callback suite exercises 23 cases and rejects three deliberately
broken variants. The older module-filter suite still compares valid inputs
with the frozen phone-qualified handler and catches restoration of the
Samsung pointer-gate regression.

The qualification workflow builds the candidate module, its source bundle,
and a release-key-signed APK with the suffix `modules-show-test1`. The APK job
overlays the exact native artifact and verifies its packaged bytes. This
workflow uploads review artifacts; it does not publish a stable release.

## Phone validation

The maintainer supplied the requested root `/proc/modules` name listing on
2026-11-08. It is readable, `kernelsu` and `susfs_guard_lkm` are absent, and
ordinary driver rows remain. `phone-validation.json` pins the tested APK,
module and source, and records the scope of this result.

This confirms the module-listing path through a root reader, rather than an
app view which Samsung SELinux may deny independently. No separate device
unload/restoration, kallsyms or long-uptime test was reported. Host build
receipts remain historical host-only evidence; the current bundle manifest
records this scoped hardware result. Reproduction carries it forward only
when the source archive and compiled module match the accepted bytes.

The upstream mount refactor, Kallrecon replacement, version-only 2.4.0 change
and optional mkdir behavior change are outside this fix.
