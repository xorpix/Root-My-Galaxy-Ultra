# Samsung SusFS directory fix, 2026-10-17

The stable candidate includes the phone-tested directory syscall entry coverage,
original caller-UID handling and cross-procfs identity matching, along with
O_PATH/readlink ENOENT behavior and support for 64 mount prefixes. The native
kernel actors retain directory record validation, user access, signals and
compat overflow; the wrappers preserve references, position locking, partial
reads, final cookies and errors. Callback lifetime tracking covers teardown.

Temporary Test3/Test4 sampling, per-entry tracing, diagnostic counters and the
entry_diag report have been removed. Their counter-independent production
host tests remain. The kernel module retains CFI and its same 135 imports.
All 18 host runners and five real-header checker groups pass after cleanup.

The user reported PASS from all 200 non-root /proc listings, with O_PATH and
readlink returning ENOENT and the real hook counters advancing, then confirmed
ordinary apps/modules, early root and soft restarts work. The separate 32-bit
getdents route was checked on the host and was not exercised by that phone run.
Full phone evidence and exact private module sources are in the saved source
checkpoint. Public profiles pin the compiled source and module checksum.

Backend refresh and release validation are recorded in the bundled manifests,
release notes and CI runs. Use a matching manager UAPI. Fully reboot after
installing the release so the updated native modules are loaded.
