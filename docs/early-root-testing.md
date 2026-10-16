# Early root test 2

Test 2 refreshes helper readiness when settings resume and retries temporary helper or
kernel-read failures while the screen is open. The row shows the specific missing step,
and offers **Activate boot helper** directly when activation is required. The helper
itself is unchanged from test 1, so its successful update does not need to be repeated.

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
