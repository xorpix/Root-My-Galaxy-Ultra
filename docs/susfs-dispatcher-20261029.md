# Samsung SusFS dispatcher adaptation

This update adapts upstream's preferred syscall-table delivery and kprobe
fallback through `2517cae` to the Samsung fork. It keeps the existing command
ABI and handlers, plus the initialization, deferred-request and verified
restoration safeguards from the current Samsung source.

## Behavior

The dispatcher first attempts the native reboot syscall-table entry using the
existing Samsung hook helper. If that cannot be installed, the helper must
finish any required rollback and callback drain before the dispatcher considers
the kprobe route. The routes are never intentionally installed together.

The fallback resolves and inspects the exact reboot entry it will probe.
It refuses a build with kernel BTI enabled, explicit `BTI c`/`BTI jc` entry
instructions, and an unknown or unreadable entry. This guard is conservative;
it is not a declaration of support for another kernel family.

Both routes share command admission. Only recognized commands from root queue
work, and no work is admitted during module initialization or ordinary removal.
The table wrapper counts its full lifetime, including any sleep in the saved
original reboot syscall. Queued work additionally holds a module reference.
Teardown retains the final Tasks RCU grace period for deferred callback returns
on both routes. Failed restoration still prevents module text from being freed.

Root can inspect the selected route:

```sh
su -c 'cat /sys/module/susfs_guard_lkm/parameters/supercall_route'
```

The result is `syscall-table` or `kprobe` after a successful load. The parameter
is read-only. A completed load using the fallback emits a warning about its
reboot-entry breakpoint. Table delivery avoids this particular breakpoint;
other SusFS probes remain.

## Validation

- 40 dispatcher cases/configurations in normal and ASan/UBSan builds, with four
  failing negative controls in each mode. Cases include both routes, a sleeping
  original syscall, callback return tails, failed installation before fallback,
  refusal paths, repeated routes and 200 admission/removal races per route.
- All 26 host suites in their 50 supported runner configurations. The existing
  maximum-size path-listing sanitizer case needed the Python deadline aligned
  with its C harness's existing 120-second watchdog; failed attempts and the
  final rerun are recorded in the source archive.
- Android16/6.12 DDK build with CFI, Tasks RCU, MODVERSIONS and 4 KiB pages.
  All 14 syscall wrappers match the kernel-header syscall CFI type and have
  call landing instructions. All 136 kernel imports and existing callback CFI
  types match the previous Samsung module.

`backends/susfs/build-manifest.json` pins the new source and module. The complete
source, test code, build report and validation records are in `source.tar.gz`;
`dispatcher.patch` is the focused delta from the previous Samsung source.

The maintainer subsequently reported that the signed dispatcher test works on
their phone and approved release publication. The report covers the dispatcher
test build; refreshed backend pairs receive the release workflow's separate
build and bundle validation. The integration workflow builds signed test APKs;
the backend release workflow publishes the combined stable release.

## Phone check

Install the APK, fully reboot, restore root and activate SusFS. Read the
route, confirm SusFS status and the manager's normal version/feature/rule
commands, and check the configured modules after the normal soft restart.
A soft restart alone retains the previous loaded module.
