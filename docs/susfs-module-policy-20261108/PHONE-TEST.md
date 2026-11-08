# Phone check: dynamic kernel-module candidate

Install `RootMyGalaxyUltra-dynamic-modules-test1.apk`, fully reboot, activate
root and enable SusFS. The previous module-policy-test1 binary's phone receipt
does not validate this new binary.

1. Confirm NoMount still works through the normal workflow.
2. In ordinary Termux, run:

   ```sh
   ls -d /sys/module/nomount /sys/module/zram
   ```

   NoMount should be absent. Zram should remain visible if present on this phone.
3. As root, read the policy and verify NoMount is still accessible:

   ```sh
   su -c '
   cat /sys/module/susfs_guard_lkm/parameters/hide_modules
   ls -d /sys/module/nomount /sys/module/zram
   grep -E "^(nomount|kernelsu|susfs_guard_lkm) " /proc/modules
   '
   ```

   Expect `automatic discovery: on`, a nonzero stock count, `overflow=0` and
   `errors=0` and `seeded=1`. The `names:` line remains the explicit baseline list. The dynamic
   registry is reported separately. Root should still see NoMount's module row.
   If discovery remains off/incomplete, retain the SusFS report; do not call
   the feature validated merely because the explicit NoMount rule still works.
4. Check that automatic selection can replace the explicit NoMount entry. This
   uses the already-loaded NoMount module and does not require loading an
   arbitrary test driver or forcing an unload:

   ```sh
   su -c 'printf "%s\\n" "del nomount" > /sys/module/susfs_guard_lkm/parameters/hide_modules'
   ls -d /sys/module/nomount /sys/module/zram
   su -c '
   cat /sys/module/susfs_guard_lkm/parameters/hide_modules
   ls -d /sys/module/nomount
   grep "^nomount " /proc/modules
   '
   ```

   The explicit names line should no longer contain NoMount, while non-root
   still cannot access its path and root still can. Confirm NoMount continues
   working normally. Restore the baseline explicit selection afterwards:

   ```sh
   su -c 'printf "%s\\n" "add nomount" > /sys/module/susfs_guard_lkm/parameters/hide_modules'
   ```

If your normal NoMount workflow permits enabling SusFS before loading NoMount,
test that sequence and report it separately. A successful existing-module test
does not establish late-load, unload stress or long-uptime stability. Android
may deny a non-root /proc/modules read independently; that is inconclusive for
that particular view.

Retain firmware/kernel release, the SusFS report, command outputs, candidate
APK SHA256 and qualified module SHA256 with the result. Keep 1.3.5 stable until
the new candidate's scoped phone checks are complete.
