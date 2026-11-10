# Shared SusFS bundle and recovery-file cleanup

Issue #52 reports working DirtyFrag root on SM-S948N / S948NKSS4BZIG, with
6.12.69-android16-6-pb4d3caf-abogkiS948NKSS4BZIG-4k and 4 KB pages. The app's exact
release allowlist refused SusFS before the loader ran. All existing regional
profiles already referenced the same module binary.

The app now uses one `susfs/bundle.json` descriptor and one module at
`susfs/susfs_guard_lkm.ko`. The per-firmware profiles and exact release allowlist
are removed. Samsung S26 Ultra family, ARM64, 4 KB pages and the Android 16 / Linux
6.12 kernel series remain checked. The native loader resolves the running
kernel's symbols and only adjusts vermagic when the kernel requests its complete
value. This does not establish binary ABI compatibility for every regional kernel.
No native module, root backend or exploit binary is changed by this update.

Checksums still cover the module and all native helpers. An active or partial
SusFS API response prevents a second load. The root shell's kernel series is
checked before an absent module is loaded, and all standard SusFS features must
answer successfully before the app reports active. Root exploit eligibility is
independent of this change. No new regional phone validation is claimed.

The report by sardelisp on page 41, post #812 of the linked XDA thread includes
four leftovers: `rmgnext-soft-reboot-keeper.sh`, `rmgnext-soft-reboot.log`,
`rmgnext-soft-reboot-ksud.log` and `.rmgnext-soft-reboot-accepted`. These are fixed
recovery-helper files. The screenshot alone does not demonstrate unbounded log
growth. The existing helper removed its ownership lock, but left these files.

Every generated recovery helper now owns an EXIT cleanup for its script, log,
acknowledgement and, where applicable, daemon-output log. A refusal remains
readable until the caller consumes it or the existing three-second wait expires.
A consumed wait is not repeated. Cleanup also runs when the caller is gone but
the detached helper finishes. INT/TERM exit through cleanup; a full reboot cleans
before executing the reboot because the kernel can terminate the shell before
its EXIT trap. The private framework verification report is retained. Exact
paths are removed; no wildcard temp-directory wipe, payload or daemon deletion,
or reduction of the existing root-run staging guards is introduced. SIGKILL or a
power loss cannot run a shell trap; existing quiet sweeps remain the fallback.

Tests cover the exact Korean issue, other regional names and new patch revisions,
wrong hardware/kernel series, and the shared descriptor. Recovery tests execute
the generated cleanup with a real POSIX shell: normal completion, refusal with
preserved exit status, lost caller, bounded handoff waiting, quoted filenames and
preservation of unrelated files. The complete Android suite and existing native
provenance/ABI checks gate publication.

The maintainer deleted the unused first 1.3.8 release and explicitly requested one
combined 1.3.8 republication. A one-time workflow replaces only its known old tag
object, only after confirming that the release remains absent, with an exact ref
lease. The branch cleanup also uses the expected merged parent and a ref lease.
These temporary workflow steps are removed after successful publication.

Mountify remains unsupported. No Mountify/ext4 workaround is part of this change.
