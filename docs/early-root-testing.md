# Early root test 4: launch and verification

The phone test has **not** established working root before PIN entry. This build
fixes a concrete startup timeout bug and records the missing timing evidence;
it is a candidate for a new phone test, not a confirmed solution.

`SuProbe` previously read `su` output to EOF before reaching its three-second
`waitFor` timeout. A child waiting for a grant or another boot condition could
therefore block the automatic worker without a deadline. The probe now uses a
reader thread while the deadline runs. A timed-out child is killed and cannot
count as root proof, even if it printed `uid=0` before hanging. Root-command
output continues to be captured completely.

Helper version 4 passes the originating boot event, request uptime and its unlock
reading to the worker. The worker logs service entry, probe duration, helper
query, exploit start and verification with monotonic uptime and lock state.
The saved result adds a `launch` object with those times and verification state.

A before-unlock label requires a locked-boot origin, a locked helper request,
a not-yet-unlocked user and a locked device at root verification. A post-unlock
boot event or any positive unlock evidence produces an after-unlock result.
Missing evidence and legacy results are shown as timing unconfirmed. This is
stronger reporting; real phone behavior remains the acceptance criterion.

**This build requires updating the helper to version 4 and fully rebooting.**
Existing certificate preparation is retained. The SusFS/module-startup extension
is separate and is not included in this candidate.

This test adds **Root before first unlock** to Root Management. It is off by default and
requires Root on boot, a previously verified bundled DirtyFrag backend, and the matching
activated boot helper. Keep Odin reachable remains independent.

## Update and enable

1. Install the signed test APK over the existing RMGU app.
2. Under Recovery Management → Keep Odin reachable, use **Update helper** to install version 4. Existing
   certificate preparation is retained. Fully reboot to replace the system process's
   cached helper code, then activate the helper if requested.
3. Root manually through RMGU once if the app requests a verified payload. Enable
   **Root on boot**, then **Root before first unlock** under Root Management.
4. Fully reboot. Leave the phone at its first PIN/password screen for the configured
   automatic boot delay plus about two minutes. Do not unlock it during that interval.
5. Unlock, open RMGU settings, and read the boot result beneath the new switch. Success
   must say **Root verified before first unlock**. A result after unlock does not qualify
   the pre-unlock path. Timing unconfirmed does not qualify either. If the new
   result still conflicts with observed phone behavior, retain the report and do
   not declare success. Confirm Working in the selected backend's manager as well.

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
