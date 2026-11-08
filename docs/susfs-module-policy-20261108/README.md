# Automatic kernel-module selection and lifecycle

The candidate builds on the phone-tested selected-module policy. The maintainer
reported NoMount hidden from ordinary Termux, visible and functional to root,
with zram visible. That report applies to native commit
`42fb802901c3b13289f921f8f6bebadfdfaba848` and its signed module-policy-test1 APK.
The automatic-discovery candidate changes the binary and requires a new phone
result; previous phone evidence is not transferred to it.

## Selection

RMGU retains the explicit list `kernelsu,susfs_guard_lkm,nomount` and the explicit
root-visible exception `nomount`. It also attempts one complete metadata scan
of kernel-module files in these firmware locations:

- `/lib/modules`
- `/system/lib/modules`, `/system_dlkm/lib/modules`
- `/vendor/lib/modules`, `/vendor_dlkm/lib/modules`
- `/odm/lib/modules`, `/odm_dlkm/lib/modules`

Canonical directory and file paths must remain within these locations. Actual
`name=` metadata is read with Android Toybox modinfo; filenames, module taint,
signature status, install time and a preexisting loaded-module list are not
used to decide which names are stock. No module code is executed. Aliases and
names are deduplicated. A missing tool, unreadable file, missing/invalid name,
unsupported compressed module, empty inventory or an exceeded bound rejects
the scan. On a fresh boot, the explicit policy remains active if discovery
cannot be initialized; no empty inventory is published as "hide everything".

The inventory represents the mounted firmware-file view. It is not a
cryptographic attestation and cannot prove that a same-name replacement or a
root overlay is original firmware. Unusual stock locations are not inferred.
This is classification by firmware inventory, not a claim to identify every
root package or malicious module. Root ZIP modules without a kernel LKM are
outside this mechanism.

The kernel publishes a completed sorted stock table in one pointer swap.
Non-root module/proc views then hide loaded module names outside that table;
root keeps access to automatically selected names. Explicit stricter module
rules and independent KernelSU/SusFS symbol-prefix filtering remain in effect.
Stock module names remain visible unless explicitly hidden. Built-in sysfs
entries are not enumerated as loaded modules or hidden automatically.

After commit, RMGU reads the root /proc/modules snapshot and seeds existing
extra modules. Only an acknowledged complete snapshot sets `seeded=1`; an interrupted snapshot
is reported as incomplete and is retried at the next activation. The notifier
captures subsequent COMING/LIVE/GOING events,
including events concurrent with the snapshot. It copies names/presence only,
retains no module pointer, and never vetoes a load. Deferred work reconciles
sysfs rules and inode changes. No periodic scan, always-running userspace
watcher or per-read logging is introduced. Sysfs work is deferred, so a new
module can have a visibility window before its path rule is installed.

## Ownership and limits

Explicit and automatic selection are independent. Removing one selection
source preserves a module rule still needed by the other. Independent user
path rules retain their existing ownership. Automatically selected modules
stay discoverable to effective global UID 0. This does not bypass independent
DAC, SELinux or user hiding rules; private control authorization is unchanged.

The stock table holds up to 1024 unique names, and the metadata scan accepts at
most 4096 firmware file paths. The automatic sysfs registry holds up to 128
unique extra names per boot. Unloaded names are retained for safe lifecycle
tracking; absent paths have no active sysfs rule. Overflow and lookup errors
are reported explicitly; overflow means sysfs coverage is incomplete for
additional names, even though the proc predicate continues to exclude extra
names. Explicit names retain their existing 16-entry limit. Reader stock
lookups use binary search under the existing short lock.

Read `/sys/module/susfs_guard_lkm/parameters/hide_modules` as root. The existing
`names:` line lists explicit names; `automatic discovery:` reports the dynamic
policy, stock/registry sizes and errors. `automatic refresh:` reports notifier
registration. These status flags alone do not establish real-device coverage
or performance. `automatic off` disables the automatic policy and releases
its module-owned rules while retaining explicit and independent user rules.
All discovery state is boot-local; firmware updates require fresh inventory.

The root controller supports `automatic begin`, batched `automatic stock
NAME[,NAME...]`, `automatic commit`, `automatic abort`, `automatic observe
NAME[,NAME...]`, `automatic ready`, and `automatic off`. Inventory construction never edits the
active table in place. App activation is serialized across processes using
the existing activation lock. Manual control writes remain individually
serialized by the native configuration mutex.

## Qualification

The production controller, callbacks and cleanup are compiled by the host
suite with sanitizer runs and deliberate regressions required to fail. New
cases cover unknown-module discovery, stock exclusion, root visibility,
bootstrap, unload/reload, an event during lookup, ownership overlap, rejected
inventory and overflow. Android tests cover complete/failed metadata scans,
name/path rejection, limits and bootstrap command order. Native qualification
retains the pinned Samsung DDK, callback KCFI and import checks. The signed
candidate still needs device checks for actual metadata availability, zram
visibility, NoMount functionality and an additional loaded module.

The mechanism covers the implemented module-list, module-symbol and sysfs
views. It does not promise universal root invisibility or long-uptime
stability. Hiding every stock driver is deliberately not the default.
