# Early root test 6: overlap Odin checks and shorten the exploit wait

Test5 is the phone-confirmed baseline on SM-S948B / S948BXXS4BZIG. The user
confirmed an automatic soft restart into a rooted state at the first PIN screen.
Its report recorded the helper request at 55.279 seconds, exploit start at
55.603 seconds, root verification at 62.181 seconds and SusFS active at 64.212
seconds. Root, SusFS and the restart attempt were all recorded before unlock.

Test6 implements two speed candidates. Actual savings still need phone testing:

- Helper version 5 wakes root after the first verified Odin observation. All
  four scheduled checks at 0, 0.5, 2 and 6 seconds remain, as does the eight-second
  watchdog/completion fallback. Requests joining an already busy worker share
  its verified/completion signals instead of starting root without an observation.
- The Direct Boot worker reads helper status without waiting for later rechecks.
  Root and SusFS overlap them; the automatic soft restart waits for helper idle
  so it cannot interrupt the planned Odin checks. A stuck helper refuses that restart.
- After DirtyFrag's trigger, a completed saved-grant command reporting UID 0 can
  shorten the five-second wait. The one-second orphan trigger window is retained.
  Missing/denied root, stalled commands and probe errors retain the old five-second
  fallback. Manual runs retain that full window. Native cleanup and the existing
  final root/backend verification always run; readiness alone never verifies a run.

The report now adds `launch.broadcastUptimeMs`, `launch.helperHandoff`,
`launch.postTriggerWaitMs` and `launch.postTriggerReady`. Compare broadcast-to-request
time separately from request-to-root time. `odinWaitBeforeRestartMs` records any
remaining helper wait, and `restartRequestedUptimeMs` records the actual handoff
time separately from the earlier restart claim. The final framework restart's
duration is not measured by these fields.

`SuProbe` previously read `su` output to EOF before reaching its three-second
`waitFor` timeout. A child waiting for a grant or another boot condition could
therefore block the automatic worker without a deadline. The probe now uses a
reader thread while the deadline runs. A timed-out child is killed and cannot
count as root proof, even if it printed `uid=0` before hanging. Root-command
output continues to be captured completely.

The helper passes the originating boot event, request uptime and its unlock
reading to the worker. The worker logs service entry, probe duration, helper
query, exploit start and verification with monotonic uptime and lock state.
The saved result adds a `launch` object with those times and verification state.

A before-unlock label requires a locked-boot origin, a locked helper request,
a not-yet-unlocked user and a locked device at root verification. A post-unlock
boot event or any positive unlock evidence produces an after-unlock result.
Missing evidence and legacy results are shown as timing unconfirmed. This is
stronger reporting; real phone behavior remains the acceptance criterion.

**Update the helper to version 5 and fully reboot.** Existing certificate
preparation and stored choices are retained. Open RMGU once after upgrading so
SusFS and Auto Soft reboot choices are copied into device-protected boot policy.

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
2. Open RMGU. Under Recovery Management, update the helper to version 5 and fully
   reboot. Activate it afterward if requested, then reboot again for the timing test.
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
