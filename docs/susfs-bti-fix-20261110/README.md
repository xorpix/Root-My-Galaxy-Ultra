# Samsung show-probe BTI safety fix

Adapt upstream SusFS PR #206 to the exact source shipped in RMGU 1.3.6.
The validated resolver preserves a leading BTI C or BTI JC instruction by
setting the kernel kprobe offset to one instruction. Both address-based
kallsyms registration and the tested Samsung name-based module registration
use this offset. The modules callback is admitted only after the kernel-selected
address matches the validated body plus offset. No PAC instruction is skipped.

The change retains the module-status lock fix, NoMount root-installer policy,
callback ownership checks, nofault module-name reads and failure cleanup.
It adds no runtime logging, polling, user settings or diagnostic controls.

The host fixture compiles the actual production functions. It models kernel
address-plus-offset registration and checks the instruction while the probe is
armed, CFI entry stubs, vendor adjustment, BTI followed by PAC, restarts,
unreadable code and unchanged Samsung/NoMount behavior. Broken variants which
overwrite BTI or lose the by-name offset must fail. All 66 production runner
executions passed locally, including supported ASan/UBSan runs.

The qualification workflow reconstructs the pinned source from native.patch
and native.commit, compiles against the pinned Android 16/6.12 DDK, compares
imports and all 22 syscall-wrapper types and landing instructions, and checks
the exact module packaged into the signed candidate APK.

The 1.3.6 phone receipt remains historical baseline evidence. The changed binary
has not been phone-tested during this update; its manifest records that fact.
The change prevents a reported upstream BTI fault under the affected conditions;
no matching crash has been established on the maintainer's current firmware.
