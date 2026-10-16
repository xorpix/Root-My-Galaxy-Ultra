# Early root test 3

Test 3 moves the genuine kernel-release query into the authenticated system helper.
Normal app access to the two proc sources can be denied on the phone. Settings,
enable-time validation and the Direct Boot worker now use the same helper response;
the spoofable uname syscall is never used as a substitute for the genuine release.
The fixed query reads at most 4 KiB per source and accepts no caller-supplied paths.
An unavailable kernel read does not prevent Odin status or control from working.

**This build requires updating the helper to version 3 and fully rebooting.** Existing
certificate preparation and Odin settings are retained. The row shows the helper
update, reboot or activation step before reporting a kernel-read problem.

This test adds **Root before first unlock** to Root Management. It is off by default and
requires Root on boot, a previously verified bundled DirtyFrag backend, and the matching
activated boot helper. Keep Odin reachable remains independent.

## Update and enable

1. Install the signed test APK over the existing RMGU app.
2. Under Recovery Management → Keep Odin reachable, use **Update helper**. Existing
   certificate preparation is retained. Fully reboot to replace the system process's
   cached helper code, then activate the helper if requested.
3. Root manually through RMGU once if the app requests a verified payload. Enable
   **Root on boot**, then **Root before first unlock** under Root Management.
4. Fully reboot. Leave the phone at its first PIN/password screen for the configured
   automatic boot delay plus about one minute. Do not unlock it during that interval.
5. Unlock, open RMGU settings, and read the boot result beneath the new switch. Success
   must say **Root verified before first unlock**. A result after unlock does not qualify
   the pre-unlock path. Confirm Working in the selected backend's manager as well.

## Behavior and limits

- The helper restores the Odin flag first when that feature is enabled, then wakes a
  signature-protected service in RMGU's separate `:early_root` app process. No exploit
  or native payload runs inside Android's system process.
- The worker uses only device-protected boot policy and APK-owned, verified payloads.
  It checks the genuine kernel release so an active SusFS uname override is not saved
  as the next boot's firmware identity. Changed firmware/backend bytes require rearming.
- Root on boot is the master switch. Disabling it prevents early attempts too. Changing
  backend disarms early root until it is enabled again after a verified run.
- Manual, normal automatic and early automatic DirtyFrag runs share an atomic kernel
  boot claim before staging the native handoff. A failed attempt requires a full reboot;
  a userspace/soft restart cannot start another exploit in that kernel boot.
- Boot broadcasts only wake the worker. Its foreground service has a wake lock and a
  watchdog; a stuck JNI call terminates that separate process rather than the helper.
  Android safe mode skips early automatic rooting.
- Manager refresh, optional SusFS activation, staging cleanup and any configured module
  restart run after first unlock, when credential-protected app settings are available.
- This still uses the existing temporary-root exploit. Android must reach Direct Boot;
  there is no recovery guarantee for failures before that stage.

## Disable and compare

Turn off **Root before first unlock** to retain ordinary root after unlock. Turn off
**Root on boot** to disable both automatic root paths. Keep Odin reachable can remain on.
Verify a reboot with each root option off before considering a public stable release.

The test workflow uploads repository-key-signed APKs as Actions artifacts and does not
publish a stable release. Automated checks cannot establish pre-unlock phone behavior.
