# SusFS audit repairs

Repairs the ten reproducible shipping defects from [audit PR #42](https://github.com/xorpix/Root-My-Galaxy-Ultra/pull/42). The candidate includes updated native source, the rebuilt ARM64 module and loader, all three firmware profiles, and an app guard requiring an exact supported kernel release.

| Audit | Repair | Regression evidence |
| --- | --- | --- |
| A01 | Path hiding never changes inode permissions. DAC can return EACCES before an LSM hide returns ENOENT. | Ordinary and exempt-caller mode preservation |
| A02 | One process mutex serializes mount enable, disable and prefix changes. | Controlled two-writer registration/unregistration schedule |
| A03 | Configuration admission closes and drains before layer teardown. Late sysfs mutations return ENODEV. | Nested in-flight controls, shutdown drain, post-teardown commands |
| A04 | A matching openat2 redirect with resolve constraints returns EOPNOTSUPP rather than opening an unscoped absolute destination. | BENEATH, IN_ROOT and CACHED requests |
| A05 | Descriptor allocation retains openat2 O_CLOEXEC independently of file-open flags. | Native and compat descriptor flags |
| A06 | Removal never restores an old mode over chmod or setid-bit clearing. | Foreign chmod and setid-clear preservation |
| A07 | GUP/pin inspect the complete page-aligned, untagged range and reject hidden mappings before acquiring page outputs. | Cross-VMA, gaps, tagged addresses, overflow, lock/output preservation |
| A08 | Compat stat64 matches the full 64-bit inode at offset 96 and updates both inode fields. | High inode and equal-low-bits collision cases |
| A09 | Oversized open_how is passed back to the native syscall for its E2BIG response. | PAGE_SIZE boundary and oversized inputs |
| A10 | Mount-prefix defaults are initialized before parameter parsing; init does not replace custom or cleared lists. | Actual initializer/setter and init phase guard |

Additional hardening makes the path unmount probe mandatory and covers inode references that are being published or detached. Removal pins an active superblock until iput finishes; shutdown releases the rule's reference without sleeping in the probe. Tests cover both active and shutting-down superblocks.

Pagemap walks scan the complete chunk. A chunk containing a hidden mapping returns EFAULT before native output is acquired: a successful skip would leave the kernel's private pagemap output cursor inconsistent. Smaps retains zero-contribution skipping. This is a deliberate refusal policy, not indistinguishable pagemap output.

New redirect rules require statfs snapshots, every reverse-disguise hook, and native plus CONFIG_COMPAT forward coverage. Partial fdinfo registration rolls back. Map rules require the six observation probes and supported smaps/pagemap operations. Missing coverage returns an error. A forward layer that cannot initialize is absent from the advertised feature list.

LSM static-call updates verify readback and propagate installation errors into tracked rollback. Teardown does not overwrite a foreign owner. The kallsyms fallback reads a nofault snapshot, loader section-name comparisons respect the string-table boundary, load-only parameters are read-only, and uname probe copies explicitly suppress page faults. The priority-publication negative control now uses a deterministic two-writer schedule.

## Build and validation

Native source: `3a65e96f3694f19abd7feb1f905e4794f48d5aee`, tree `db1648df75d104249d29fc4533912e4ba4722561`. The cumulative `native.patch` applies to audited commit `8b9d19b3b348439b519260706896247e25633ec9`; `native.commit` records the exact new Git object. The bundled archive reconstructs that tree and commit.

- 33 host suites, 64 normal/sanitizer runner executions: all passed. These compile actual production C with explicit kernel API models.
- The pinned Android 16 / 6.12 DDK compiled the module and loader. It enables CFI, Tasks RCU, modversions and 4 KB pages. The DDK release is 6.12.76; supported Samsung profiles remain the exact previously qualified 6.12.69 firmware releases.
- CI rebuilds the packaged archive, verifies 22 syscall-wrapper CFI types against real headers, checks callback types and the explicit import delta, then compares rebuilt module/loader bytes with the bundled bytes.
- Android CI runs unit tests, builds a debug APK and verifies its module, helpers, profiles and source provenance.

`host-results.json`, `native-build.json` and `build-receipt.json` distinguish host/compiler evidence from device evidence. No phone result is claimed for this candidate. Older accepted device results in the manifest describe the previous hardware baseline only.

## Remaining limits

Return probes retain finite maxactive capacity. Registration and host tests cannot establish that every Samsung LTO call site reaches a probe; runtime hit/missed counters and device checks remain necessary. Scoped redirects and mixed pagemap chunks explicitly refuse operations as described above. A failed hook restoration or foreign hook chaining through module text can keep unload blocked to preserve live callback memory. Unknown firmware requires a new exact profile and validation.

This PR does not publish a stable release or claim that every future compatibility problem is eliminated.
