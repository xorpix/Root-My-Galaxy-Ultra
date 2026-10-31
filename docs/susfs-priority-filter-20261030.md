# SusFS automatic app protection test

Some apps can discover running root-related apps by asking Android for their
scheduling priority. This adaptation of [upstream PR #158](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/158)
closes that visibility gap for four priority APIs in both 64-bit and 32-bit apps.
It does not hide every trace of an app.

## Automatic selection

When SusFS activates, RMGU automatically protects:

- RMGU itself.
- The backend's registered manager and apps currently granted root.
- Installed known root managers and renamed KernelSU managers recognized by the
  daemon-library scanner or chosen in RMGU's settings. Discovery checks extracted
  libraries and base/split APKs, and does not require a known label or launcher icon.

Root grants, revocations and registered-manager changes are followed directly by
the kernel, even when RMGU is closed. Installed-manager extras are refreshed after
unlock, on app resume and supported package-change events. Package scanning stays
out of the boot root path. There is no recurring timer or grant scan.

Android can defer install events while RMGU's process is stopped. A newly installed
inactive manager can therefore wait until you next open the app or activate
SusFS. The active manager and root-granted apps remain covered immediately.
Only the main user's installed-manager inventory is scanned; explicit app IDs and
RMGU's own ID apply across Android users, while root grants use the target user's
actual UID. Apps sharing an app ID cannot be separated.

Root, system services, trusted KernelSU profiles and apps querying themselves keep
their normal behavior. Nothing needs to be entered manually.

## Install and check

The artifact contains the signed `1.3.0-priority-test4` APK, a signed 1.3.0 rollback
APK, this guide, the optional ARM64 control tool, and checksums. The rollback uses
the same signing key and a higher version code, preserving app data when installed
over the test build.

1. Install `RootMyGalaxyUltra-1.3.0-priority-test4.apk` over the existing app.
2. **Fully reboot**, then obtain root and activate SusFS normally. A soft restart
   retains the old loaded module and cannot install this update into the kernel.
3. Open RMGU after unlock. The SusFS status should say root-related apps are
   selected automatically. Give root to an ordinary test app, then check your
   usual root, modules, SusFS and soft-restart behavior.

If automatic protection is unavailable, core SusFS remains active. This test
build adds a verified entry-probe fallback for the table-write failure observed
on BZJA and retains its route/error information in read-only status.
The message no longer suggests that another reboot necessarily resolves a failure.
Core SusFS may still be active; its activation result is separate from this
optional layer. This build has passed host/static checks; phone testing is pending.

To roll back, install `RootMyGalaxyUltra-1.3.0-rollback.apk` and **fully reboot**
before obtaining root again. This restores the previous module on its next load.
Use a full reboot to replace modules rather than attempting a live `rmmod`.

## Optional verification and manual control

These commands are for testing; normal use needs none of them. Copy the supplied
`rmgu-priority-ctl` to `/data/local/tmp/` and make it executable:

```sh
adb push rmgu-priority-ctl /data/local/tmp/
adb shell chmod 755 /data/local/tmp/rmgu-priority-ctl
adb shell "su -c '/data/local/tmp/rmgu-priority-ctl status'"
```

Expect `ready=1 automatic=1 root_lookup=1` with your RMGU app ID in `own_appid`.
`configured` counts explicit manager app IDs, not live root grants. It can be zero
while automatic protection is working. All state is boot-local.

For a live target, compare root-visible priorities with an untrusted caller:

```sh
adb shell "su -c '/data/local/tmp/rmgu-priority-ctl probe 10124 10123'"
```

Replace both UIDs: target `10123` must be a running selected app; caller `10124`
must be a different ordinary, untrusted app ID. `passed:true` verifies the modeled
UID transition against a root-visible target. The probe isolates its own mount
namespace before dropping privileges, reads priorities, and checks deliberately
invalid input. It sends no valid scheduling changes. Exit 0 means verified,
1 means an unexpected result, and 2 means invalid/inconclusive/blocked. This
synthetic caller does not reproduce a complete app's SELinux sandbox. A real app
shell can use `query <target_uid>` for read-only comparison with root.

Advanced controls:

```sh
rmgu-priority-ctl auto <RMGU_app_id>
rmgu-priority-ctl auto-off
rmgu-priority-ctl set com.example.one,com.example.two
rmgu-priority-ctl clear
```

Run them as root. `set` replaces explicit extras and `clear` clears only those
extras; both preserve automatic mode. `auto-off` disables the live automatic mode
while preserving extras. RMGU's next activation/background refresh restores its
automatic selection. Failed list updates preserve the previous list; RMGU clears
confirmed failed snapshots and retries once, but does not issue a competing
fallback after an indeterminate timeout.

## Implementation and validation

Reviewed upstream head: `d55749105beaac03776d95241f4400a68292f831`.
Samsung priority-filter parent: `fc114e5f01c797a3d0e30372c4422db7bb703852`.
The earlier #158 adaptation is retained: separate native/compat originals,
complete callback accounting, verified restoration, Tasks-RCU teardown, validated
package names, transactional immutable bitmaps and original kernel I/O-priority
validation. There is no credential or private SELinux blob mutation.

Automatic mode consults the backend's existing live policy and retains its owning
module until every SusFS hook is drained. Installed-manager extras use fresh
package names after unlock, a coalesced Android job and a cross-process lock;
no numeric UID list is stored between boots. A boot-local device-protected record
keeps status consistent between the early-root and app processes.

All 29 native host suites pass in 56 normal/sanitizer configurations, including
concurrent policy/list updates, initialization/fault cases, dependency lifetime
and deliberately unsafe negative controls. The ARM64 DDK module and standalone
client build successfully. All 22 syscall wrappers match real-header CFI types;
the five new probe callbacks match their real-header types. This build adds no
imports to test3; the recorded BZJA image review covers the same 140 import names
and module-owner lookup.
The actual I/O-priority validation prefix still matches its callable helper.
These checks do not establish complete ABI compatibility or replace phone testing.

The complete source and evidence are in `backends/susfs/source.tar.gz`, especially
`susfs/docs/auto-root-apps-20261031`. The manifest pins the source tree, commit,
archive, module and client. `priority-filter.patch` includes the full delta from
the stable Samsung integration. Stable 1.3.0 has not been replaced by this test.

## BZJA table-write fallback (test4)

Test3 on the BZJA phone reported `init_errno=-14 stage=2 op=0`, both
installation masks zero, a present compat table and valid PACIASP wrapper
entries. The first getpriority table write returned EFAULT; more full reboots
of test3 cannot fix that write refusal.

Test4 first tries the existing syscall-table route. Only EFAULT after verified
restoration/draining can enter the new fallback. Four kernel-supported entry
probes cover getpriority, setpriority, ioprio_get and ioprio_set. The exact named
core entry addresses must match between native and compat tables. Unknown,
foreign, divergent or unreadable entries and BTI kernel/call landing pads are
refused. A post handler prevents jump optimization from discarding the return
redirection. Partial registration unregisters everything; exit drains callback
counters and Tasks RCU before releasing code/policy dependencies. No forced
protected-memory write or kernel protection change is added.

After installing test4, perform one full reboot to replace the loaded module,
obtain root and activate SusFS. Then run this read-only command:

```sh
su -c 'cat /sys/module/susfs_guard_lkm/parameters/priority_filter'
```

Expected successful fallback: `ready=1 automatic=1 root_lookup=1 init_errno=0
stage=0 route=2 probe_mask=f table_errno=-14`, with your RMGU app ID in
`own_appid`. `route=1` means the table route worked. Stage 4 is a refused or
failed probe fallback. Status remains root-only and prints no kernel addresses.
Existing caller exclusions and permission/error ordering are retained.

All 56 host runner executions pass, including actual production C with both
compat configurations, partial failures, unsafe-entry refusals, rearming,
untouched passthrough frames, live policy changes and return-tail teardown.
Deliberate unsafe mutants are detected. The pinned DDK build verifies real
kernel-header callback types and unchanged imports. Hardware validation is
pending. Probes add breakpoint handling to these four calls; no global syscall
tracepoint, timer or package scan is added to the kernel fast path.

The complete source review is in
`susfs/docs/priority-probe-20261031` inside the source archive. HMA OSS operates
through framework package filtering and has no direct overlap with these
priority hooks. Keep RMGU out of HMA's filtered-caller scope so it can discover
inactive managers; hiding RMGU from other apps can remain enabled. See
[the HMA review](susfs-hma-compatibility-20261031.md). Runtime coexistence still
needs phone testing.
