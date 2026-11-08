# Selected-module visibility and lifecycle

This change builds on the phone-tested NoMount module-list repair. It retains
the verified Samsung `m_show` entry and bounded fault-safe module-name read.
KernelSU/SusFS symbol-prefix filtering is independent of the selected-name
policy and remains enabled for root readers too.

## Configuration

```
hide_modules=kernelsu,susfs_guard_lkm,nomount hide_modules_root_visible=nomount
```

`hide_modules` selects up to 16 exact module names. The separate
`hide_modules_root_visible` list permits effective global UID 0 to see those
selected names in `/proc/modules` and module-qualified `/proc/kallsyms` rows.
The policy alone never selects a module. Bare selected names keep their
all-reader proc filtering. Policy can precede the selected-name parameter.

The root control node accepts `root-visible set NAME [NAME...]` and
`root-visible clear` as well as the existing set/add/del/clear/rescan commands.
NoMount is configured by RMGU, not hard-coded in the native matcher. Other
installer modules can opt into the same behavior without changing the matcher.
The app does not automatically hide arbitrary vendor modules.

Selected sysfs module paths remain hidden from non-root readers and available
to effective global UID 0. This does not grant access through independent user
rules, DAC or SELinux. Private SusFS control-node authorization retains its
existing real-UID checks. Names must fit `MODULE_NAME_LEN`, use ASCII letters,
digits, `_`, `-` or `.`, and cannot be `.` or `..`; slash/path traversal and
silent truncation are rejected before changing configuration.

## Reliability and cost

Status formatting takes the sleeping configuration mutex before the reader
spinlock, matching writers. The previous order could acquire a mutex while
holding a spinlock and deadlock a concurrent configuration update.

Module COMING/LIVE/GOING notifications record only a selected-name bit and
presence state, then queue work. A worker performs sleeping filesystem lookups.
No module pointer survives the callback, no module load is vetoed, and unrelated
modules queue no work. Events coalesce; a new event during work can queue another
pass. There is no periodic polling or per-read logging. Sysfs updates are
deferred and therefore do not eliminate every possible observation window.

Ordinary configuration changes retain unchanged sysfs rules. Lifecycle work
refreshes dirty names; `rescan` explicitly checks all selected names. Reloads
replace a stale inode rule under the path-table lock. Allocation and lookup
errors other than a confirmed absent path preserve the previous owned rule.

Module rules have their own ownership bit. User/control rules may overlap the
same path or inode; module cleanup removes only module-owned entries, and user
deletion cannot remove module-owned entries. Unload first closes admission,
unregisters the notifier and cancels work outside the configuration mutex, then
releases rules while the path layer is still alive. Internal owner cleanup also
works after the public control gate closes.

## Validation limits

Host tests compile the production functions and exercise both normal and
ASan/UBSan runs, with deliberate regressions required to fail. The native build
checks the new notifier/work callback KCFI against real pinned DDK headers and
checks the two new imports against DDK exports. A new binary needs a new phone
check before stable publication; the previous NoMount receipt is baseline
evidence only. No claim is made about device performance gains, all detector
behavior, non-root `/proc/modules` availability, or long-uptime stability.
