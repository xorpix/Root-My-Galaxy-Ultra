# Samsung SusFS audit repairs

RMGU 1.2.27 bundles Samsung SusFS source `cc2b27a3f66f11cd9b6bf511a60b8e47c128eae4`, based on the readlink fix `870bf952aab096789a0f4153bc5ea5bf6fcfba1e`.

The update repairs all five findings from the 27 October audit: verified native/compat hook restoration and rollback, callback lifetime across sleeping work and return tails, consistent directory caller policy, atomic padded uname snapshots, and coherent mount identity reuse. It also indexes path rules and bypasses mapping-rule work when the table is empty.

The AArch32 create hooks now use the correct syscall numbers and their separate compat wrappers/original functions. All numbers are checked against the prepared kernel header. The uname insmod parameters are read-only at runtime; normal manager/supercall updates continue to work.

If restoration permanently fails or another hook still chains into SusFS, teardown waits instead of freeing live module code. Reboot is the recovery path in that exceptional case.

## Module and source

- Stripped module: **548,680 bytes**.
- SHA-256: `df033a463d5457acdc5f16d543979b49f23f0885a41f6c31910cad373f846dd1`.
- Both BZIG and BZID profiles pin that digest and source commit.
- [Complete source archive](../backends/susfs/source.tar.gz), [patch from the previous source](../backends/susfs/audit-fixes.patch), and [build provenance](../backends/susfs/build-manifest.json) are committed with the module.
- The userspace loader, status helper and control ABI retain their existing interfaces.

## Validation

All 26 host suites passed in 50 runner executions, including normal and ASan/UBSan modes where supported. Tests cover patch failures, rollback retry, independent native/compat ownership, sleeping bodies, late entry and return tails, all 13 wrappers, directory credential overrides, concurrent uname updates, mount reuse, and index lifecycle through 8192 rules.

The Android16/6.12 DDK build passed with CFI and Tasks RCU. The 13 syscall wrappers match a real-header CFI fixture and have valid ARM64 landing instructions. All 136 kernel imports, module metadata and version-table layout are unchanged. Thirteen real-header identity callback cases and three path-entry layout cases passed, including negative controls. LeakSanitizer process inspection is unavailable on the host; ASan/UBSan and explicit allocation assertions were used.

The source archive includes the detailed results. The APK integration workflow separately verifies the source tree/commit hashes, reruns host regressions, builds a signed APK, runs Android unit tests, and checks packaged module/profile/backend bytes and the embedded D2 companion.

These checks do not establish phone load/unload or stress-test coverage. The index benchmark is a host scaling check, not a phone microfreeze measurement.

**Fully reboot after installing the updated APK**, then activate root and enable SusFS. A soft reboot may retain the previous loaded module.
