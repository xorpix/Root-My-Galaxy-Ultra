# RMGU Early Boot Probe 0.1.0

A separate diagnostic app for the first autoroot-on-boot timing experiment. It installs as
`dev.experimental.rmgu.bootprobe`, beside RMGU. It contains no root exploit, backend, SusFS binary,
boot-animation delay, system-UID installation, or automatic restart.

## Phone test

1. Install `RMGU-Early-Boot-Probe-0.1.0.apk` and open **RMGU Boot Probe** once.
2. If RMGU autoroot is enabled, turn it off for this baseline test.
3. Tap **Select test wallpaper** and apply the temporary dark-gradient live wallpaper.
   Choose Home and lock screens together if Samsung offers that choice. This replaces your current
   wallpaper selection; you can choose your regular wallpaper again after the test.
4. Perform a **full reboot**, not an Android soft restart. Leave the phone locked for about 90 seconds.
   Do not open RMGU or run root during that interval.
5. Unlock, open the probe, and tap **Share report** or **Save report**. Send the text report for review.
6. Choose your regular wallpaper in **Wallpaper settings**. The diagnostic app can then be uninstalled.

This app does not require root, Shizuku, wireless ADB, a notification permission, or a storage permission.
Reports stay in device-protected app storage until you explicitly export them. The five most recent
identified boots are retained; each file is capped at approximately 2 MiB.

## What it measures

- Timestamped wallpaper-service creation, real/preview engine creation, surface creation, and boot broadcasts.
- Monotonic elapsed time since kernel boot, plus wall time for correlating records.
- Public NDK reads of `init.svc.bootanim`, `service.bootanim.exit`, and `sys.boot_completed`.
- Whether the Android user is unlocked.
- IPsec service access, UDP encapsulation socket allocation, SPI allocation, and loopback transform creation,
  matching the normal API setup used before RMGU's DirtyFrag exploit. No exploit or page-cache modification
  runs, no transform is applied to a network socket, and every temporary resource is closed.

Property sampling runs off the main thread every 250 ms for at most 90 seconds. State changes and
one-second heartbeats are persisted. IPsec tests run on a separate worker, never overlap, stop after
successful setup with a real engine, and allow at most 20 attempts separated by at least two seconds.
There is no wake lock. Wallpaper drawing happens immediately; this version does not test holding
the animation or rooting while it runs.

## Interpretation and limits

**Candidate window observed** requires successful IPsec setup while the user is locked, a real
wallpaper engine is known, and all three properties report `running`, `0`, and `0`. A wallpaper-picker
preview, missing user state, an exit request, or completed boot cannot satisfy that condition.

**Possible window** means the animation service reported `running` at IPsec readiness, but some
other boot properties were unavailable. Empty values are absent or inaccessible, never assumed to be zero.
Polling records intervals, not exact visible animation frames. Android's property access and Samsung's
wallpaper behaviour can make a run inconclusive. An IPsec-ready observation does not prove DirtyFrag
will succeed at that point or that modules can finish initialization in the same boot.

The kernel boot ID is used if readable; otherwise Android's boot count is used. If both are unavailable,
the process receives a unique unidentified segment instead of merging logs using estimated wall time.
Each process segment has a separate identifier, so a framework restart remains visible within a kernel boot.

## Build

From the repository root, with JDK 25 and the Android SDK/NDK installed:

```sh
./gradlew -p tools/early-boot-probe :probe:testDebugUnitTest :probe:lintRelease :probe:assembleRelease --no-daemon
```

This standalone Gradle project does not configure or rebuild the main RMGU app. Its release variant is
not debuggable and uses a disposable diagnostic signing key, not the RMGU production key. Future builds
from a different machine may require uninstalling this diagnostic app first; export observations before
doing so. The branch workflow builds only this project and uploads a diagnostic artifact, without
publishing a release or changing main.
