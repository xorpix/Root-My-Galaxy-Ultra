# SusFS priority filtering test

Some apps can discover other running apps by asking Android for their scheduling
priority, even when ordinary package visibility is restricted. This adaptation of
[upstream PR #158](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/158) lets you
choose packages whose app IDs should appear absent to those queries from
untrusted apps. It covers four priority APIs in both 64-bit and 32-bit apps.
This is one specific visibility gap; it does not hide every trace of an app.

No packages are selected automatically. Existing SusFS activation and the app UI
are unchanged. Rules last until a full reboot and can be replaced or cleared.
Root, system services, trusted KernelSU profiles and an app querying itself keep
their normal behavior. The caller boundary also covers SDK sandbox and app-zygote
process UIDs, subject to the same KernelSU profile gate. Packages sharing a UID
are inseparable. Selected app IDs
apply across Android users, but configuration currently resolves packages in the
main Android user only. Reapply the list after installing, removing or reinstalling
a selected package: UID changes or reuse can make an old rule inaccurate.

## Install and check

The test artifact contains the signed `1.3.0-priority-test1` APK, a signed rollback
APK built from the tested 1.3.0 source, this guide, the standalone ARM64 control
tool, and checksums. The rollback APK uses the same signing key and a higher
version code, so it can replace this test build without clearing app data.

1. Install the test APK over the existing app. **Fully reboot**, then obtain root
   and activate SusFS normally. A soft restart retains the old loaded module.
2. Copy `rmgu-priority-ctl` to `/data/local/tmp/` and make it executable:

   ```sh
   adb push rmgu-priority-ctl /data/local/tmp/
   adb shell chmod 755 /data/local/tmp/rmgu-priority-ctl
   adb shell "su -c '/data/local/tmp/rmgu-priority-ctl status'"
   ```

   Expect `ready=1 configured=0`. An unavailable status or `ready=0` means this
   optional layer could not initialize; do not infer that filtering is active.
3. After first unlock, choose a harmless package installed in the main user.
   Replace the example below with its actual name. Open that app and keep it
   running so there is a live target to observe.

   ```sh
   adb shell "su -c '/data/local/tmp/rmgu-priority-ctl set com.example.target'"
   adb shell "su -c '/data/local/tmp/rmgu-priority-ctl status'"
   ```

   `set` replaces the entire list. Use comma-separated names for multiple apps.
   Failed updates preserve the previous list. `configured` counts unique app IDs.
4. Find the target UID and the UID of an ordinary, untrusted caller app using
   `cmd package list packages -U`. Use distinct app IDs. Substitute those numbers:

   ```sh
   adb shell "su -c '/data/local/tmp/rmgu-priority-ctl probe 10124 10123'"
   ```

   `passed:true` verifies the modeled UID transition against a live root-visible
   target. The probe isolates its own mount namespace before dropping its own
   privileges, so KernelSU's unmount hooks cannot affect the parent namespace.
   It reads priorities and checks one deliberately invalid request; it sends no
   valid scheduling changes. Exit 0 means verified, 1 means an unexpected result,
   and 2 means invalid arguments or an inconclusive/blocked check. This synthetic
   caller does not reproduce a complete app's SELinux sandbox. A real app shell
   can use `rmgu-priority-ctl query <target_uid>` for read-only comparison with
   root; `query` reports observations and makes no filtering verdict by itself.
5. Clear the list, check ordinary visibility returns, and verify normal root,
   modules, SusFS commands and the usual soft restart on your firmware/backend:

   ```sh
   adb shell "su -c '/data/local/tmp/rmgu-priority-ctl clear'"
   ```

To roll back, install `RootMyGalaxyUltra-1.3.0-rollback.apk` and **fully reboot**
before obtaining root again. This restores the previous module on the next load.
Avoid attempting to unload/replace a live module with `rmmod`.

The control executable is also bundled in the APK's native library directory as
`librmgususfs_priority.so`. It is the same verified file as the standalone tool.
There is no new settings screen or background package observer in this test.

## Review and safeguards

Reviewed upstream head: `d55749105beaac03776d95241f4400a68292f831`.
Samsung source parent: `961fb6a0585c4ad503f9a9e1fe107058d3595c92`.

The adaptation uses separate native/compat originals, complete callback accounting,
verified restoration and the existing Tasks-RCU teardown. Installation must cover
all required entries or the optional layer rolls back. Package input is validated,
then a complete immutable bitmap is published atomically and retired with RCU.
Readers perform no file lookup, allocation or credential mutation. Configuration
uses the authorized root caller's existing SELinux domain and returns errors for
denied lookups; it never writes private SELinux credential blobs. The actual kernel
`ioprio_check_cap` preserves invalid-input and permission checks before a hidden
UID is reported absent.

All 28 host suites pass in their 54 normal/sanitizer configurations, including
production-code fault injection, concurrent readers/writers and failing negative
controls. The ARM64 Android16/6.12 DDK build passes. All 22 syscall wrappers match
the real-header CFI type; all 139 imported names exist in the recovered BZJA
kernel. Its actual I/O-priority validation prefix also matches the callable helper.
These checks do not establish complete kernel ABI compatibility or replace a
phone test. This new layer has not yet been validated on hardware.

The complete source and evidence are in `backends/susfs/source.tar.gz` under
`susfs/docs/priority-filter-20261030`. The manifest pins the source Git tree,
commit, archive, module and client; `priority-filter.patch` is the focused delta.
