# RMGU Early Boot Probe 0.2.0

A standalone diagnostic for the accessibility startup route on Samsung. It checks
whether a user-enabled, Direct Boot-aware accessibility service connects while the
original boot animation is still running, and whether normal IPsec setup works then.
It does not root the phone or hold the animation.

## Install and test

1. Uninstall the previous **RMGU Boot Probe** if installed. Each diagnostic CI build
   uses a disposable test signing key, so it cannot reliably update an older build.
   Keep the main Root My Galaxy Ultra app installed.
2. Install `RMGU-Early-Boot-Probe-0.2.0.apk` and open it once.
3. Tap **Open accessibility settings**, then **Installed apps → RMGU Boot Probe**,
   and enable it. Menu wording can vary with One UI.
4. If Android blocks enabling it, open **App info** from the probe and use the
   three-dot menu to **Allow restricted settings**, if that option is offered.
   Return to Accessibility and enable the service yourself.
5. Return to the probe. It should say **Service connected**. Tap **Refresh
   observations** if the connection is still pending.
6. Turn off RMGU's **Root on boot** for this baseline test. Fully restart the phone;
   do not use a soft/userspace restart. Leave it locked for at least 90 seconds.
7. Unlock, open the probe, and use **Share report** or **Save report**.
8. Turn off the probe in Accessibility after collecting the report.

No wallpaper selection, root approval, ADB, or Shizuku is needed. If an older probe
changed your wallpaper, restore your usual wallpaper before this test.

## What it records

- Service creation, accessibility connection/disconnection, and boot broadcasts.
- Kernel elapsed time, process segment, boot identity, model, and build fingerprint.
- `init.svc.bootanim`, `service.bootanim.exit`, `sys.boot_completed`, and lock state.
- The result and duration of ordinary temporary IPsec socket/SPI/transform setup.

Sampling begins when the accessibility service connects, or from a boot broadcast
as a late baseline. It samples for up to 90 seconds per process, polls boot state
every 250 ms, and records state changes plus one-second heartbeats. IPsec checks
are serialized, at most 20, two seconds apart; they stop after success while
connected. A binder call already in flight can finish after the sampling deadline;
this is marked in the report. Opening the activity only reads existing observations.

The service subscribes to no accessibility events and declares no screen retrieval,
gesture, key filtering, touch exploration, or fingerprint gesture capabilities.
It does not act as an accessibility aid. Android controls enabling and disabling it.
Its callbacks return promptly; storage and IPsec work run on worker threads.

All observations use device-protected storage, available before the first unlock.
There is no network upload, root command, native root payload, boot property write,
boot delay, foreground service, wake lock, or automatic restart. IPsec resources are
closed, and no transform is applied to any network socket.

## Reading the result

A **candidate window** requires IPsec success, a connection recorded in the same
process segment, explicit locked state, and all three signals: animation `running`,
exit `0`, and boot-completed `0`. Unknown values cannot qualify. A service starting
early without early IPsec success is reported separately. Earlier observed exit
signals prevent a later/restarted animation from qualifying as the original window.

Reports from a different identified boot are marked as historical. If the current
boot identity cannot be read, that limit is stated explicitly. The raw JSONL includes
property-read intervals and partial/truncated trailing records do not erase earlier
evidence. Report summaries give a conservative span between observed pre-exit
samples; they do not infer that the entire rooting procedure will fit in that span.

A candidate result is not proof of successful root, Samsung's exact visible screen
state, or module/Zygisk initialization. Those require separate device validation.

## Build

Use the **Early boot diagnostic probe** workflow on this experimental branch, or:

```sh
./gradlew -p tools/early-boot-probe :probe:testDebugUnitTest :probe:lintRelease :probe:assembleRelease
```

The APK has the separate package `dev.experimental.rmgu.bootprobe`, is not debuggable,
and does not use the RMGU release key. The workflow verifies its signature, alignment,
identity, permissions, accessibility configuration, and native library inventory.
