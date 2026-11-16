# Backend refresh and SusFS review — 15 November 2026

## Refresh failure and repair

The daily [compatibility check](https://github.com/xorpix/Root-My-Galaxy-Ultra/actions/runs/43701380299)
stopped before building anything. KernelSU added an `inode_setxattr` SELinux hook
and retained-policy lifetime tracking in `64833c00`; its include, enable and
cleanup changes conflict with our Samsung read-only-slot fallback. Its preceding
Rust cleanup also supersedes the profile argument-borrow fixes in our template.
Next and KowSU carry the same SELinux update and need the same rebase.

The rebased patches retain upstream's new SELinux hook, superblock lookup,
policy lifetime protection and profile-name validation. The new hook uses the
existing guarded Samsung kprobe route when its LSM slot cannot be written.
Its trampoline preserves all arguments and return codes, suppresses recursive
entry and releases the per-task guard. Cleanup follows the installed route and
retains the immutable original kernel handler for callbacks which may finish
after the probe has been unregistered.
The old redundant Rust hunks are removed where upstream already supplies them.

KowSU rewrote its history again. The reviewed endpoint delta from `21787ce1`
to `d4873310` includes the same SELinux/profile fixes, preinit segment alignment,
x86-only syscall changes and manager changes. UAPI remains 6 and its driver
version increases from 32750 to 32761. A bridge admits this exact old pin and
reviewed replacement plus ordinary descendants; unrelated rewrites still fail.

The new host fixture compiles the actual added route functions from all three
patches for the 5.10, 5.15 and 6.12 function signatures with UBSan. It exercises
argument forwarding, errors, recursion, disabled state, both hook routes and
their cleanup, including removal during an active diverted call. Full matched
native builds and bundle checks remain mandatory.

## SusFS decision

Compared the current bundled source `3ebe7cf0cb08108856e79fdcdbfe4b3c2bd3a3a1`
with upstream main `761b93083afd846858257221453bf306465042a3`, and inspected
the recently updated pull requests and issue reports. The prior BTI and Open
Redirect adaptations from 10 November remain the production baseline.

| Upstream change | Decision for the Samsung 6.12 bundle |
| --- | --- |
| [#201](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/201), [#213](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/213): unsigned kstat ring index | Already fixed: cast before subtraction, unsigned slot, `WRITE_ONCE` stores. The retained unlisted counter is not entered by any supported syscall. |
| [#214](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/214), [#215](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/215): broader landing-pad and signed-LR changes | Do not copy a partial fix. Our resolver preserves BTI C/JC and deliberately does not skip PAC; its modules callback rejects unexpected kernel address adjustment. Skipping PAC changes the LR assumptions of the direct-return handler, as #215 itself demonstrates on 6.18. These closed, unmerged alternatives do not establish a fault on the supported Samsung path. |
| [#216](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/216): HooKern inline experiment | Retain the existing Samsung kprobe implementation. The new engine adds direct instruction patching, trampolines and CFI build exceptions. Its reported device test is a different 5.15 platform, with 6.18 explicitly untested; no Samsung 6.12 evidence justifies replacing the working path. |
| [#212](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/212), phases 2–5 | Reviewed create-gate, path, open and kstat view integration. This is a broad architectural change rather than a demonstrated Samsung repair. Our visible create operations still delegate to the native kernel; Open Redirect retains its caller gates and tested Samsung fallback. Preserve these implementations and the requested NoMount policy/lock behavior. |
| [#198](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/198): version 2.4.0 | A version-label change is not a runtime repair for this separately pinned fork. |
| [#217](https://github.com/inforcqb/susfs4ksu-for-lkm/issues/217): Zircon 5.15 bootloop | The report contains no crash trace or reproduction on Samsung 6.12. Do not infer its cause or import the inline engine as a remedy. |
| [#197](https://github.com/inforcqb/susfs4ksu-for-lkm/issues/197): modules-show panic | Our earlier callback ownership, nofault reads, registration validation and teardown protections remain present. The report concerns a different vendor 5.10 kernel and the older upstream module. |

No SusFS native binary change is required by this review. Its source archive,
module, loader and control clients remain identical to 1.4.1. No diagnostic
probes, logging or detector-policy changes are introduced.

## One scheduled release skipped

The manually prepared release replaces the weekly run on **16 November 2026,
03:17 UTC / 04:17 Oslo**. The date gate skips only that UTC date for `schedule`
events. Manual builds and PR validation still run; the original Monday cron
remains, with the next eligible run on **23 November**. Tests cover both date
boundaries, timezone conversion and manual/PR events. The separate daily
compatibility check remains enabled.

The user's previously reported extra full reboot after a soft reboot is still
unconfirmed. Neither this refresh nor the upstream reports establish its cause.

## Qualification

The corrected [matched-backend build](https://github.com/xorpix/Root-My-Galaxy-Ultra/actions/runs/43763258405)
passed all four native builds, driver/daemon pairing, host boot checks, Android
unit tests, release APK compilation, embedded companion/signature checks and
bundle provenance validation. All four manager downloads match the selected
upstream revisions and UAPI 6, with official artifact digests verified.

The downloaded source update was hash-checked and applied using the trusted
release validator at its exact CI base `52595ea13813c8c659c47f81600c991f87bc943a`.
Its source tree equals reviewed PR head `69776264050a3c426d5f6322ddd81da5c2ec2259`.
The validator rechecked allowed changes, generated helpers, catalogs, native
pairs, packaged APK bytes and companion identity/signature. Source-only final
edits add immediate default-branch compatibility checking and guarded cleanup
of the merged release branch. Hardware testing of the updated backends has
not been performed during this refresh.
