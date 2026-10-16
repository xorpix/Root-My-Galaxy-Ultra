# Early root test 5: finish setup before unlock

The test4 phone log records the locked-boot worker starting at 55 seconds,
obtaining a root shell at 62 seconds while both unlock readings were locked,
and receiving its first unlocked boot event at 88 seconds. The user still
observed the expected root behavior only after unlock. Test4 explicitly deferred
SusFS and the configured module soft restart until then. Its retained process
exit history contained idle-process cleanup, not a crash trace.

Test5 moves the enabled SusFS activation and module soft restart into the Direct
Boot worker. It is a candidate for phone validation; the full before-unlock
experience is not yet confirmed.

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

The helper remains version 4, with the same source identity as test4. An existing
matching helper needs no update. Open RMGU once after upgrading so existing SusFS
and Auto Soft reboot choices are copied into device-protected boot policy.

Each setup attempt is saved before its side effect. A restarted worker may query
an interrupted SusFS activation, but cannot repeat the load or the automatic soft
restart in that kernel boot. Root verification, SusFS status and restart request
have separate timing fields. A restart request is not proof that modules work.

Later skipped checks retain an existing attempt's result and are recorded as
bounded observations. When a new boot replaces the report, the old report is
retained as `early-root-previous-result.json`. Stage logs now include boot IDs.

This test adds **Root before first unlock** to Root Management. It is off by default and
requires Root on boot, a previously verified bundled DirtyFrag backend, and the matching
activated boot helper. Keep Odin reachable remains independent.

## Update and enable

1. Install the signed test APK over the existing RMGU app.
2. Open RMGU. If Recovery Management requests it, update the helper to version 4,
   fully reboot, then activate it. A matching activated helper from test4 can stay.
3. Root manually through RMGU once if the app requests a verified payload. Enable
   **Root on boot**, then **Root before first unlock** under Root Management.
4. Retain the desired **SusFS · experimental** and **Auto Soft reboot** choices.
   Fully reboot. Leave the phone at its first PIN/password screen for the configured
   automatic boot delay plus about three minutes, including any module soft restart.
   Do not unlock it during that interval.
5. Unlock, open RMGU settings, and read the boot result beneath the new switch. Success
   must say **Root verified before first unlock**. A result after unlock does not qualify
   the pre-unlock path. Timing unconfirmed does not qualify either. If the new
   result still conflicts with observed phone behavior, retain the report and do
   not declare success. Check the separate SusFS and restart lines, then confirm
   Working in the selected backend's manager and actual module behavior.

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
- Enabled SusFS activation and the configured module soft restart use only a
  device-protected settings snapshot, bundled bytes and the saved `su` grant.
  Manager refresh, Shizuku startup, staging cleanup and UI bookkeeping remain
  after first unlock. SusFS activation is serialized across app processes.
- With Root on boot off, the dependent before-unlock switch is shown off and
  disabled. Its saved preference is retained for when the master is re-enabled.
- This still uses the existing temporary-root exploit. Android must reach Direct Boot;
  there is no recovery guarantee for failures before that stage.

## Disable and compare

Turn off **Root before first unlock** to retain ordinary root after unlock. Turn off
**Root on boot** to disable both automatic root paths. Keep Odin reachable can remain on.
Verify a reboot with each root option off before considering a public stable release.

The test workflow uploads repository-key-signed APKs as Actions artifacts and does not
publish a stable release. Automated checks cannot establish pre-unlock phone behavior.
