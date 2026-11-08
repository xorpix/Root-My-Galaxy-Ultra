# Samsung modules callback safety candidate

This change adapts upstream SusFS PR #200 to the existing Samsung source at
`3a65e96f3694f19abd7feb1f905e4794f48d5aee`. It is a candidate for phone testing;
the currently published 1.3.4 module remains the accepted hardware baseline.

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

## Phone validation pending

Install the signed candidate over RMGU, perform a full reboot, then activate
root and SusFS normally. A soft reboot does not replace the loaded module.

Check as root that `/proc/modules` is readable, the configured hidden modules
are absent, and ordinary module rows remain present. App-side emptiness alone
is insufficient: Samsung SELinux may deny the app access regardless of the
hook. Check that normal mount listings and kallsyms behavior remain intact.
If the module is unloaded in a controlled test, verify restoration and the
absence of a kernel warning or reboot. Candidate receipts deliberately set
`hardwareValidated` to false until there is device evidence for these bytes.

The upstream mount refactor, Kallrecon replacement, version-only 2.4.0 change
and optional mkdir behavior change are outside this fix.
