# Root before first unlock

This optional setting restores temporary root while the phone is still at its
first PIN/password screen after a full reboot. It is **off by default** and
requires **Root on boot**, a previously verified bundled DirtyFrag setup, a saved
superuser grant for RMGU, and the matching activated boot helper.

## Update and enable

1. Install the latest release over the existing RMGU app and open it once.
2. Under **Recovery Management → Keep Odin reachable**, follow the guided helper
   setup if needed. Existing users should choose **Update helper** when offered
   to install **version 5**, then **fully reboot**. Activate it afterward if asked.
   Existing certificate preparation remains valid. Users already on test6 with
   helper version 5 do not need another helper update.
3. Complete a successful manual root run with the selected bundled DirtyFrag
   backend if the app requests it. Grant RMGU superuser access in that backend's
   manager so boot verification and post-root actions can run without a prompt.
4. Under **Root Management**, enable **Root on boot**, then **Root before first
   unlock**. Keep the desired boot delay, **SusFS** and **Auto Soft
   reboot** choices. Open RMGU after changing these settings so the saved boot
   policy is current.
5. Fully reboot and leave the phone at its first PIN/password screen. The app
   starts after Android delivers its locked-boot event and the configured delay
   has elapsed. If Auto Soft reboot is enabled, a framework restart may follow.
6. After unlocking, confirm root in your selected manager and check that SusFS
   and your modules are working as expected.

Root on boot is the master switch. Turning it off disables both automatic root
paths; the dependent before-unlock switch appears off and disabled while retaining
its saved preference. Turning off only Root before first unlock retains ordinary
automatic root after unlock. **Keep Odin reachable** remains independent.

## Startup behavior

When Keep Odin reachable is enabled, the helper first obtains a verified Odin
observation, then wakes RMGU. Its remaining scheduled checks continue while RMGU
restores root and, if configured, activates SusFS. The automatic module soft
restart waits until the helper has finished those checks.

The early worker can shorten DirtyFrag's five-second post-trigger wait when a
completed, bounded superuser command confirms UID 0. It retains the initial
trigger window and the original fallback for a slow or unavailable backend.
Native cleanup and final backend verification still run. Manual root runs retain
the original wait.

Root, optional SusFS activation and the configured module soft restart use a
device-protected settings snapshot. Manager refresh, Shizuku startup and ordinary
app bookkeeping finish after unlock. A restart request can stop the worker before
its acknowledgement is saved; check actual phone and module behavior as well as
the reported request.

## Results and limits

- Before-unlock reporting requires a locked-boot event, no unlock at the helper
  request, a user still awaiting its first unlock, and a locked device at verification.
  Missing evidence is reported as timing unconfirmed.
- The working setup was confirmed on **SM-S948B / S948BXXS4BZIG with BakaSU**.
  Root was verified around 54 seconds after boot on that phone. Boot time depends
  on firmware, Android broadcast delivery, the selected delay and backend; this
  does not establish results on every recognized variant.
- Firmware or backend changes require a new verified setup. The helper reads the
  genuine kernel identity even when SusFS overrides the reported release.
- Manual and automatic DirtyFrag paths share a durable one-attempt-per-kernel-boot
  guard. A failed attempt needs a **full reboot** before retrying. A soft restart
  cannot launch another exploit in that kernel boot. SusFS and automatic restart
  claims also prevent repeated side effects after worker or framework restarts.
- Android safe mode skips early automatic root. Android must reach Direct Boot;
  this feature cannot recover failures before that stage.

If root is not restored, check the helper update/reboot/activation guidance and
the saved superuser grant. Retain the boot result and app log when reporting a
failure. Do not count an after-unlock or timing-unconfirmed result as a confirmed
before-unlock run.
