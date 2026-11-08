# Phone check for the module-policy candidate

The candidate needs its own phone result. The 1.3.5 receipt applies only to the
previous binary. Host tests cannot establish real-device load/reload behavior.

1. Install the repository-signed `module-policy-test1` APK. Reboot so the old
   boot-local SusFS module is no longer loaded, then enable SusFS in RMGU.
2. Confirm RMGU reports SusFS active. Start or use NoMount through your usual
   workflow and confirm its normal functionality still works.
3. Run the following in ordinary Termux, outside `su`:

   ```sh
   id
   uname -r
   ls -d /sys/module/nomount /sys/module/zram
   grep -E '^(nomount|kernelsu|susfs_guard_lkm) ' /proc/modules
   ```

   NoMount's path should be absent and zram's path should remain visible if
   zram is loaded. If Android denies `/proc/modules`, record that as inconclusive
   for this particular non-root check; it is not proof of filtering.

4. Run:

   ```sh
   su -c '
   id
   cat /sys/module/susfs_guard_lkm/parameters/hide_modules
   ls -d /sys/module/nomount
   grep -E "^(nomount|kernelsu|susfs_guard_lkm) " /proc/modules
   '
   ```

   The status should include `root-visible: nomount` and
   `automatic refresh: on`. Root should see NoMount's path and module row;
   KernelSU/SusFS rows should remain absent. NoMount must actually be loaded
   for its path/row checks to be meaningful.

To exercise late loading, enable SusFS before starting NoMount if your normal
NoMount workflow permits that order, then repeat the path/row checks after
NoMount starts. Record whether this sequence was tested. The APK change takes
effect in the kernel only after the old module is gone and the new one loads.

Retain the RMGU SusFS report, outputs, firmware/kernel release, APK SHA256 and
qualified module SHA256 with the result. A basic passing check does not by
itself validate module unload stress, long uptime, every detector, or every
supported firmware profile.
