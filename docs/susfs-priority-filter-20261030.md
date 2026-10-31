# Automatic SusFS app protection

RMGU 1.3.1 adapts [SusFS PR #158](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/158)
for Samsung and selects root-related apps automatically. It covers `getpriority`,
`setpriority`, `ioprio_get` and `ioprio_set` for explicit app UIDs, including
native and 32-bit callers.

## Automatic selection

SusFS includes RMGU itself, the backend's registered manager, apps currently
granted root and installed recognized managers. It also recognizes renamed
managers through their daemon libraries or base/split APKs, without requiring a
known label or launcher icon.

Root grants, revocations and registered-manager changes follow the backend's
live policy immediately, even when RMGU is closed. Installed-manager extras are
refreshed after unlock, on app resume and supported package-change events.
Scanning is deferred outside the boot root path; no recurring timer is used.
Android may defer package events while RMGU is stopped, so a newly installed
inactive manager can wait until the next app open or SusFS activation.

The optional installed-manager inventory covers the main Android user. Explicit
app IDs and RMGU's own ID apply across users; live grants use the target user's
actual UID. Apps sharing an app ID cannot be separated. Root, system services,
trusted backend profiles, self-queries and non-UID operations retain their normal
behavior.

## Updating and HMA OSS

Install the production APK, **fully reboot**, then obtain root and enable SusFS.
A soft restart retains the old kernel modules. The normal SusFS status reports
automatic selection or explains that the optional protection could not start.
Core SusFS can remain active independently of this optional layer.

[HMA coexistence details](susfs-hma-compatibility-20261031.md): keep RMGU outside
HMA's filtered-caller scope so it can discover installed inactive managers. HMA
can still hide RMGU from other apps. Neither layer changes the other's settings.
The maintainer completed the test4 phone checks and reported no HMA failures.

## Production implementation and validation

Separate native/compat originals, transactional RCU rules, verified restoration,
full callback/return-tail accounting and Tasks-RCU teardown are retained. The
priority path does not allocate. Live backend policy pointers retain their
owning module until all readers and callbacks drain.

If Samsung refuses a syscall-table write with EFAULT, the code restores/drains
the table hooks before trying verified shared entry probes. Native/compat core
symbol identity and entry instructions must match. Unsupported BTI entries and
foreign redirections are refused; partial registration is fully unwound. Linux's
I/O-priority input/capability validation precedes the hidden-UID response.

Temporary phone probes, raw priority queries, verbose initialization fields and
the module's device fault-injection parameter are removed. Configuration/status
remain available to the app; no manual commands are required for normal use.

All 29 native host suites / 56 configurations and real-header checks for all 22
syscall wrappers, five probe callbacks, 140 imports and callback types passed.
The source archive includes complete corresponding code and production evidence.
`backends/susfs/build-manifest.json` is authoritative for current source/binary
hashes. The production cleanup has build/host/ABI checks; accepted device results
were obtained with test4 before diagnostic-only removal. Backend refresh checks,
Android unit tests and final APK signature/integrity checks gate publication.
