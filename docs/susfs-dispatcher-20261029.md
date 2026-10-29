# Samsung SusFS dispatcher — source review

This draft carries the focused source patch and its validation records. The active APK bundle remains on source `cc2b27a3f66f11cd9b6bf511a60b8e47c128eae4` until full bundle integration is approved.

## Candidate behavior

- Prefer the existing Samsung syscall-table hook helper for the reboot supercall.
- Fall back only after verified rollback, and only when the resolved reboot entry is readable and its explicit BTI checks permit a probe.
- Share command admission and deferred-work ownership between both routes.
- Reject queued work during initialization/removal, count the complete table wrapper including sleeping original calls, and preserve both wrapper and task-work return tails.
- Expose a root-readable, read-only `supercall_route` parameter and warn after a successful load using kprobe delivery.

The fallback refuses a build with kernel BTI enabled, an explicit `BTI c`/`BTI jc` entry, or an unknown/unreadable entry. It probes the exact address inspected. The existing restoration helper and its no-timeout lifetime contract are retained.

## Validation

All 26 host suites passed in their 50 supported runner configurations after correcting the existing path-listing runner's sanitizer deadline to match its C watchdog. The dispatcher has 40 cases/configurations and four failing negative controls per mode (normal and ASan/UBSan). Cases include both routes, original argument/result forwarding, sleeping callbacks, failed publication before fallback, refusal paths, repeated routes and admission/removal races.

The Android16/6.12 DDK build passed with CFI, Tasks RCU, MODVERSIONS and 4 KiB pages. All 14 syscall wrappers match the real-header syscall CFI type and have call landing instructions. The 136 kernel imports, existing callback CFI types and module version-table shape match the prior module. The focused patch reconstructs the exact candidate source tree.

## Prepared integration

- Source commit: `961fb6a0585c4ad503f9a9e1fe107058d3595c92`
- Source tree: `8fd0d9cab6fc13e4bb0161b479294f1a86a3a132`
- Module: 555,000 bytes, SHA-256 `f3a5e86347eef1710f1decde1318048839263605812ef22127fe29987db37f92`
- Source archive: 791,472 bytes, SHA-256 `6b5b2fb908e650846ba894906f5be347f679c183a86e5f0397434042a7535ff1`
- The archive contains 354 regular UTF-8 source/test/validation files. It was checked for embedded executables, nested archives and credential/private-key signatures.

`backends/susfs/dispatcher.patch` contains the complete delta from the current Samsung source, including test and build records. Full archive/module/profile integration and a signed test APK are pending source-archive publication approval. No phone validation has been performed.
