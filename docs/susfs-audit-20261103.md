# Audit of the shipping Samsung SusFS code — 3 November 2026

The review found **ten defects** in the current source, including four high-priority access-control/lifecycle problems. The existing test suite does not exercise several of these combinations. This is a source and host-model audit, not a claim that a new kernel module has passed phone testing.

The report, reproductions and test records are review material. This change does not replace the bundled module, change the app, merge a fix, or publish a release.

## Scope and provenance

Reviewed RMGU main `3c1394edcbc49e40cc46b0c31eb4d09f0be4c42b`, whose [SusFS source bundle](https://github.com/xorpix/Root-My-Galaxy-Ultra/blob/3c1394edcbc49e40cc46b0c31eb4d09f0be4c42b/backends/susfs/source.tar.gz) contains native commit `8b9d19b3b348439b519260706896247e25633ec9` and tree `5eed14dedfcfe5b6cda92d53b29896c1f2784a56`. The archive SHA-256 is `953216c328b35ab9bf5ebb7599d0f9a5627565276a0ea340d2be1372a1fddd46`. Reconstructing the native Git tree reproduced that tree and commit; the audited production checkout has no source changes.

Coverage includes module initialization/rollback/unload, syscall-pointer restoration and callback draining, LSM insertion/pass-through, symbol resolution, supercall admission/task work, path matching/listing/pending resolution, mount identities and namespace work, maps/map_files and remote-page filtering, stat/statx/compat rewriting, open redirection and reverse reporting, uname, bootconfig, AVC/log controls, priority policy, the native loader/control clients, and the Kotlin activation/status/automatic-package paths. Previously reviewed identity/lifetime implementations were checked against the same unchanged shipping source. This does not independently rebuild or verify every root-backend implementation.

The relevant shipping target is Samsung Android 16 / ARM64 / Linux 6.12 / 4 KB pages. Host tests cannot establish vendor structure layouts, probe reachability under LTO, BTI/CFI behavior, or stability on other firmware. Source line references below are **inside the archive**, under `susfs/kernel/`.

## Findings, in repair priority order

“High” means a meaningful access/scope or kernel lifecycle hazard under the stated trigger. It does not mean an arbitrary unprivileged app has been demonstrated to gain root. Configuration and unload races require an authorized root controller.

| ID | Priority | Defect | Trigger / consequence |
|---|---|---|---|
| A01 | High | Ordinary path rules change the real inode mode to `0777` | A caller exempt from hiding can gain DAC permissions the original file denied. |
| A02 | High | Mount control transitions are not serialized | Overlapping enable/disable leaves hiding hooks active without their invalidators. |
| A03 | High | Some sysfs setters remain operational after layer teardown | A late root parameter write can add path rules after hooks and cleanup have finished. |
| A04 | High | Redirected destinations lose `openat2` resolution constraints | A constrained source lookup is followed by an unscoped absolute destination open. |
| A05 | Medium | Redirected `openat2` drops `O_CLOEXEC` | Native and compat descriptors can survive an exec despite the caller requesting closure. |
| A06 | Medium | Path cleanup overwrites subsequent mode changes | Deleting a rule can undo chmod or restore a cleared set-ID bit. |
| A07 | Medium | Remote-page filters inspect only the starting VMA | A request beginning in a visible mapping can include a later hidden mapping. |
| A08 | Medium | Compat `stat64` matches a truncated inode key | Rules for inode numbers above `UINT32_MAX` miss on these syscalls. |
| A09 | Low | `openat2` omits the maximum structure-size check | An oversized zero-filled `open_how` can succeed where the native syscall returns `E2BIG`. |
| A10 | Low | Initialization discards `hide_mounts=` | A successfully parsed load-time prefix list is silently replaced by the default. |

### A01 — Global permission relaxation

`sus_path.c:138` changes the actual inode's permission bits to `0777`; `sus_path_gate_ok()` at line 272 hides ordinary rules only from selected app callers, with an owner exemption. For example, uid 2000 is outside that policy. The reproduction shows an ordinary root-owned file acquiring other-write permission while that shell caller is not denied by the path gate. The `0777` proc control nodes are a separate case: their handlers also check uid, and their self-protection policy covers every non-root caller.

SELinux, parent permissions and filesystem checks can still deny access, so this is **demonstrated DAC weakening**, not demonstrated universal file access or root escalation. There is also a persistence risk: F2FS and ext4 serialize the current inode mode when updating an inode. Consequently “only an in-memory change” is not a reliable guarantee if another operation writes the inode during the rule's lifetime. That persistence consequence is an inference from the filesystem implementations, not a phone reproduction.

**Repair:** stop changing ordinary inode permissions globally; intercept the denial before DAC, or preserve the original DAC denial when pre-DAC hiding is unavailable. A cleanup-only comparison does not fix this architectural problem. Test shell/system callers, exempt app profiles, owners, ACLs, ordinary metadata writeback and reboot recovery.

### A02 — Mount lifecycle race

`sus_mount.c:2163` sets `mount_registered` before all show probes have been installed. Its supercall at line 2250 has no control mutex. In a controlled schedule, enable pauses after the first show probe registers but before its armed flag is set; disable unregisters the invalidators and clears state; enable then finishes and reports success. The final state has show hooks and `mount_registered=true`, with both invalidators absent. A serialized control passes the same invariant test.

This breaks the lifecycle assumptions that protect recyclable mount/superblock identities and can produce stale hiding/ID state. The test does not demonstrate a live-kernel use-after-free or crash. Prefix add/delete also uses separate snapshot and commit sections, so the same missing transaction boundary permits lost updates.

**Repair:** one process-context mutex around enable/disable, refresh and prefix transactions; an exit gate under that mutex; retain short reader spinlocks. Do not hold the control mutex across `proc_remove()` if the drained handlers need it.

### A03 — Late parameter writers during unload

`sus_path_store_list()` (`sus_path.c:1742`) and `hide_modules_command()` (`susfs_hide_syms.c:196`) have no permanent teardown admission gate. `susfs_hide_syms_exit()` cleans its list, but a later parameter store can recreate it, including calling the path layer after `sus_path_exit()` has run.

Linux 6.12 calls module exit before `free_module()` removes parameter attributes. The module parameter store wrappers serialize parameter access but do not reject `MODULE_STATE_GOING` or acquire a module reference. Stopping/draining the supercall route and removing proc nodes therefore does not close this other entry point. The reproduction executes the actual hide-modules teardown/controller sequence and detects a path addition after the modeled path teardown. Kernel ordering is established from Linux source; no live unload race was run.

**Repair:** close admission before the first dependent layer is removed, serialize each setter against teardown, and reject late commands with `ENODEV`. Cover direct `hide_list` stores as well as indirect hide-modules path additions. Prevent resolver/work rearming after the last cancellation. Existing `map_ready`, bootconfig and priority gates demonstrate useful local patterns.

### A04 — Destination scope is weaker than the requested open

`susfs_or_entry.c` applies resolve flags to the source lookup, then opens the replacement using `filp_open(target, flags, mode)` at line 394. That call receives neither the original dirfd nor the resolve flags. A valid source beneath a directory can therefore redirect to an absolute path outside that directory. `RESOLVE_IN_ROOT`, `NO_XDEV`, `NO_SYMLINKS`, `NO_MAGICLINKS` and cached-only lookup also cannot be preserved merely by checking the source.

The production wrapper reproduces successful unscoped destination installation for `BENEATH` and `IN_ROOT`. The root-configured redirection and normal credentials still apply; this is a broken caller scope guarantee, not a bypass of destination DAC/SELinux.

**Repair:** perform the destination open through a kernel API retaining the original open/resolution context. Until that is implemented, explicitly refuse a matching redirect whose constraints cannot be honored. Silently forwarding such a match to open the real source is also a hiding-policy bypass. The test's `EOPNOTSUPP` refusal is a detached mitigation control, not a complete replacement implementation.

### A05 — Close-on-exec is lost

The `openat2` branch removes `O_CLOEXEC` at line 276 and assigns the stripped value to `flags` at line 314. Descriptor allocation later reads that stripped variable at line 390. Both native and compat `openat2` thus allocate a descriptor without close-on-exec. The ordinary `openat` control preserves it. Existing tests recorded descriptor flags but asserted only that `filp_open` did not receive the bit; they missed whether the descriptor received it.

**Repair:** retain the original descriptor flags separately from the flags used to validate/open the file. Tests must inspect `FD_CLOEXEC` and eventually verify behavior across exec, for native and compat callers.

### A06 — Mode restoration has no ownership check

`sus_path_restore_mode()` (`sus_path.c:153`) unconditionally restores the saved mode. The tests relax a file, perform a subsequent chmod or set-ID removal, and remove the rule; cleanup overwrites the later legitimate change. There is a related source-level ownership race: deletion unlinks a rule under the spinlock and restores outside it, while another rule can acquire the same inode before restoration. Two pending paths resolving to one inode also need shared ownership handling.

**Repair:** prefer removing ordinary mode relaxation (A01). If any temporary mode lease remains, coordinate ownership across rules and restore only the state still owned by that lease. A compare-and-swap alone cannot handle delete/re-add ownership or indistinguishable external writes.

### A07 — Multi-VMA remote requests bypass a hidden later mapping

`sus_map_vm_access_pre()` (`sus_map.c:547`) checks `nr_pages` for zero, but matches only `find_vma(mm, addr)`. Both GUP and pin APIs can process more than one page. The reproduction uses adjacent visible/hidden VMAs: a single-page hidden request is rejected, while a two-page request beginning in the visible VMA is admitted. Normal ptrace/access checks remain; this is a hiding-coverage bypass.

**Repair:** validate the complete requested range under the caller's existing mmap lock, with overflow-safe page arithmetic and correct partial-return/output ownership. Also examine `pagemap` walk chunks: the walk-page-range hook likewise bases its decision on the initial VMA. Test visible→hidden, anonymous→hidden, gaps, boundaries, huge requests and visible pages after hidden ones.

### A08 — The legacy inode field is not the `stat64` key

`susfs_kstat.c:895` reads the 32-bit legacy `__st_ino` at offset 12. Linux and Android common 6.12 fill both that field and the full 64-bit `st_ino` at offset 96. The source comment claiming the latter is left untouched is incorrect. A rule for inode `0x10000002a` consequently attempts lookup with `42` and misses. Reading the full field makes the reproduction pass; the ordinary low-inode control already passes.

**Repair:** match on the full 64-bit field and continue writing both fields when spoofing. Add high-inode and equal-low-bits/different-high-bits cases. This does not establish how often a particular phone's filesystems allocate such numbers.

### A09 — Oversized `open_how`

`susfs_or_entry.c:261` rejects structures smaller than the known size, then checks a larger tail for zeroes, but omits native `openat2`'s `usize > PAGE_SIZE` refusal. The production wrapper accepts a modeled 4097-byte zero-filled structure and installs a redirected descriptor. The native 4 KB-page target rejects it before lookup.

**Repair:** apply the target's native size limit before copying/checking the tail. Preserve the existing acceptance of legal larger zero-filled structures within that limit.

### A10 — Load-time prefix setting is overwritten

The `hide_mounts` setter (`sus_mount.c:1842`) commits a supplied list before module init. Init then unconditionally reseeds `/data/adb/` around line 2049. The reproduction calls the actual parser/committer and the exact initialization block; the accepted custom prefix disappears.

**Repair:** track whether the parameter was explicitly supplied, including an intentionally empty/cleared list. Seed defaults only when no configuration was supplied. Testing `n_mount_keep != 0` alone would lose the explicit-clear case.

## Conditional risks and existing limits

These are separate from the ten reproduced defect findings.

| Area | Assessment / follow-up |
|---|---|
| Unknown Samsung firmware | Kotlin `FamilyFallback` accepts unknown matching 6.12 Android16 releases, while the loader bypasses CRC/KMI checks with absolute symbol relocation and can replace vermagic. Successful symbol resolution/status cannot prove structure or calling ABI compatibility. The source comment that a refused load is simply an ordinary failed run is too reassuring for failures after initialization begins. Qualify profiles or add concrete ABI/config checks. No crash on an unknown firmware was reproduced. |
| Path unmount safety admission | Path init accepts failure of `generic_shutdown_super` registration despite its own warning that pinned inodes may then BUG during unmount. Make a safety dependency required, or refuse rules that need it. Also examine references temporarily removed from the published list before their deferred `iput`. This audit did not inject a real unmount. |
| Feature reporting | A layer's zero init return becomes an advertised feature. Open-redirect init is best effort, and several per-surface hooks are optional. The app can therefore report core “Active” with incomplete operational coverage. Distinguish capability, configured state and live coverage; inspect counters when validating a firmware. |
| Return-probe exhaustion / LTO | Several disguises depend on `maxactive=16/64` return probes and can expose original results if instances are missed or call sites are inlined. Counters help diagnose this; registration is not proof of universal coverage. |
| LSM / symbol fallback | Static-call insertion ignores the return of its update helper; failure/foreign-owner handling should be consistent with the replace path. The kallsyms by-name fallback also directly reads a private iterator mirror: bounded string lengths do not prove those addresses readable. Prefer validated table identity and nofault snapshots, especially before expanding firmware support. |
| Loader hardening | The `.modinfo` section-name comparison reads nine bytes without checking how many remain in the section-name string table. A malformed root-supplied ELF can overread; the app's verified bundled input narrows exposure. Bound the name before comparing. Temporary `kptr_restrict` relaxation also cannot promise restoration after process death. |
| Runtime parameters | Several writable parameters are only consumed during initialization (for example `or_entry`, `map_ino`, `bootconfig`). Writes can succeed without changing live behavior. Make load-only values read-only or implement a serialized runtime setter. |
| Performance | The map walk's VMA search starts at the beginning rather than using the available indexed lookup. This is unnecessary linear work with many mappings. No benchmark here attributes phone microfreezes to it. |

## Validation and test reliability

The untouched source's full host deck ran **56 configurations across 29 suites: 55 completed successfully**. The remaining failure was the sanitizer runner's deliberate `publication-uaf` negative control: its `sched_yield()` stress schedule failed to expose the seeded bug. All production priority-filter cases in that runner completed successfully before that control failed. This is a flaky sensitivity check, **not a reproduced shipping priority UAF**. The [baseline records](susfs-audit-20261103/baseline-results.json) and [failure log](susfs-audit-20261103/priority-filter-baseline.log) preserve the result.

The added [audit reproducer](../tools/check_susfs_audit.py) compiles extracted production functions with instrumented kernel-API models. Its normal and ASan/UBSan runs each matched **30 observations**: eight ordinary controls, fourteen precise expected defect assertions, seven detached mitigation controls, and one deterministic unsafe-publication control. The latter pauses the first writer at unlock, lets a second retire/free the published object, then resumes the first. Shipping publication passes; deliberately moving its read after unlock reliably produces an ASan heap-use-after-free. This provides a deterministic replacement approach for the old probabilistic control.

An audit checker result marked `MATCH` means the recorded behavior was reproduced, including known failures. It **does not mean those defects are fixed**. The mitigation variants change detached test fragments only. LeakSanitizer is disabled because this execution environment cannot support its process/thread inspection; the records do not claim leak-sanitizer coverage. ASan/UBSan are enabled where stated.

No new kernel/device execution, kernel build, stress-unmount test, lockdep/KCSAN/KASAN run, exec test, or adversarial firmware test occurred. The previous successful HMA/phone baseline remains useful evidence for ordinary operation, not evidence that these newly tested edge cases cannot happen.

## Safeguards that held up

The supercall worker retains a module reference through sleeping dispatch; native/compat syscall hooks retain distinct originals and drain active callbacks; the KernelSU policy module is retained through consumer teardown; RCU-protected redirect slots are reclaimed after readers; the priority publisher avoids accessing a published object after unlock; native directory filtering occurs before user-buffer emission. These should be preserved during repair.

The audit did not classify intentional conservative bootconfig retention after foreign ownership, or blocking unload while another hook still chains through module text, as newly discovered leaks. `putname(ERR_PTR)` is safe on the inspected 6.12 implementation. A suspected uname sleeping fault was not established: atomic context already suppresses fault handling; explicit nofault bracketing remains defensive/debug hardening.

## Recommended repair sequence

1. Close teardown admission and serialize mount controls. Require the path unmount safety mechanism.
2. Remove ordinary global mode relaxation, then settle rule ownership/cleanup behavior.
3. Preserve `openat2` destination constraints, descriptor flags and structure bounds.
4. Fix full-range map filtering, the compat inode key and load-time parameter preservation.
5. Replace the probabilistic publication control, improve live coverage reporting, and qualify kernel/firmware behavior with phone tests before updating shipped bytes.

## Primary references

- Linux 6.12 [module removal](https://github.com/torvalds/linux/blob/v6.12/kernel/module/main.c), [module sysfs teardown](https://github.com/torvalds/linux/blob/v6.12/kernel/module/sysfs.c), and [parameter store dispatch](https://github.com/torvalds/linux/blob/v6.12/kernel/params.c).
- Linux 6.12 [open flag/descriptor handling](https://github.com/torvalds/linux/blob/v6.12/fs/open.c) and [scoped pathname lookup](https://github.com/torvalds/linux/blob/v6.12/fs/namei.c).
- Android common 6.12 [stat conversion](https://github.com/aosp-mirror/kernel_common/blob/android16-6.12/fs/stat.c) and Linux 6.12 [ARM64 compat stat layout](https://github.com/torvalds/linux/blob/v6.12/arch/arm64/include/asm/stat.h).
- Linux 6.12 [F2FS inode writeback](https://github.com/torvalds/linux/blob/v6.12/fs/f2fs/inode.c) and [ext4 inode serialization](https://github.com/torvalds/linux/blob/v6.12/fs/ext4/inode.c).
- RMGU [activation](https://github.com/xorpix/Root-My-Galaxy-Ultra/blob/3c1394edcbc49e40cc46b0c31eb4d09f0be4c42b/app/src/main/java/dev/busung/s25uroot/SusfsRuntime.kt), [status/target selection](https://github.com/xorpix/Root-My-Galaxy-Ultra/blob/3c1394edcbc49e40cc46b0c31eb4d09f0be4c42b/app/src/main/java/dev/busung/s25uroot/SusfsStatus.kt), and [automatic app selection](https://github.com/xorpix/Root-My-Galaxy-Ultra/blob/3c1394edcbc49e40cc46b0c31eb4d09f0be4c42b/app/src/main/java/dev/busung/s25uroot/SusfsAutoApps.kt).
