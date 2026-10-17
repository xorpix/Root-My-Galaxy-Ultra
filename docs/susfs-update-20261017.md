# SusFS update test 1, 2026-10-17

This 1.2.12 test candidate selectively adapts fixes from
inforcqb/susfs4ksu-lkm through commit
5be002255e638358d24343ecb9a7259e92941fec to the Samsung implementation.

- Filter hidden directory entries before the original kernel actor writes them,
  covering native and compat layouts without empty placeholder records.
- Deny O_PATH for a resolved hidden inode before fd installation.
- Make readlink return ENOENT for hidden regular files as well as links, keeping
  native lookup, security checks, errors, ESTALE retry and reference ownership.
- Increase configurable mount prefixes to 64 and format consistent snapshots.
- Bundle matched KernelSU-Next 33391/UAPI 6 and BakaSU 35230/UAPI 5 pairs;
  KernelSU remains 32692/UAPI 6 at its unchanged upstream revision.
- Retain Samsung compatibility patches, mount identity/statfs behavior, early
  rooting and the removed early-root status text in Settings.

Entry probes divert into process-context callbacks with task-specific original
call guards and callback lifetime tracking. Missing required symbols roll back
activation. The upstream syscall-table architecture and per-thread unmount
gates are not claimed integrated. Existing fdinfo, map_files, compat-stat and
inode teardown fixes are retained.

The module cross-compilation and four real-header type-check groups passed.
All 18 native host runners pass (sanitizers where supported), including the
new path-entry and concurrent prefix-snapshot tests. The matched backend
refresh passed all 928 Android unit tests and an APK build. Final signed APK
validation is performed by the integration-test workflow. Hardware behavior
is pending; this is a test candidate.

## Phone check

Install over 1.2.11, open the app, perform a full reboot and activate root and
SusFS again. Use a UAPI 6-compatible manager with KernelSU-Next; its verified
link is in README. Check ordinary apps, modules, root before unlock and soft
restarts. Collect:

```sh
su -c 'cat /proc/susfs_path; cat /sys/module/susfs_guard_lkm/parameters/mount_stat'
```

The path report should show `entry: armed=1`. Its iterate, hidden, O_PATH and
readlink counters establish which new hooks actually ran. The private source
checkpoint retains the complete implementation and tests; profile.json pins
the compiled snapshot and module checksum.
