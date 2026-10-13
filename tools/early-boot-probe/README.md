# RMGU Privileged Boot Probe 0.3.0

A separate diagnostic experiment for a helper hosted inside `system_server` as UID
1000. It tests whether an approved Direct Boot-aware `ConditionProviderService`
can run early enough for normal IPsec setup during the original Samsung animation.
The helper does not run an exploit, load a root backend, hold the animation, change
DND mode, read notifications, or restart Android.

## One-time setup and reboot test

1. Disable the 0.2.0 accessibility probe and uninstall the old **RMGU Boot Probe**.
   Keep the main RMGU app. Install `RMGU-Privileged-Boot-Probe-0.3.0.apk`.
2. Root normally with RMGU. Open the probe and allow its request in the root manager.
3. Tap **Inspect setup (read-only, root)**. If it reports a refusal, share the setup
   report rather than attempting to modify the file manually.
4. Tap **Prepare helper certificate**, review the displayed action, and confirm.
   Close other app installers first. Preparation changes Android's system-UID
   certificate history in `/data/system/packages.xml`. A malformed registry can
   prevent Android starting. The backup still requires a working root environment
   to restore; it is not a guarantee of recovery from a failed cold boot.
5. If preparation succeeds, promptly perform a **full reboot**. A soft/userspace
   restart is outside this diagnostic's test flow. No automatic reboot is issued.
6. Open the probe and tap **Install embedded helper**. Allow installation from this
   app if Android asks, return, then tap the button again. Complete Android's
   installation prompt. The helper is embedded and signed by the same diagnostic
   key as the controller; download or root access is not needed for this step.
7. Tap **Activate startup hook**. The controller checks UID 1000, its matching
   signer/version, and the helper confirms that its process is `system_server`.
   Activation approves only this helper's condition-provider binding. It does not
   enable Do Not Disturb or create any rules. This legacy API is deprecated and may
   be restricted by an OEM; failure is reported rather than bypassed.
8. Turn off RMGU's **Root on boot**. Fully reboot again, leave the phone locked for
   at least 90 seconds, then unlock and **Share report**.

The controller uses the same package as 0.2.0; signing keys are disposable per CI
run, so uninstall the old controller before installing a different build. The
helper has the separate package `dev.experimental.rmgu.boothelper` and no launcher.
Use the controller to manage it. Preserve this controller APK until cleanup is
finished, as it contains the matching signer and helper.

## Cleanup

Tap **Disable startup hook**. Root normally with RMGU again, then tap **Remove helper
setup**. It uninstalls only this helper, re-reads the current registry, removes
only its recorded diagnostic certificate entries, and preserves other apps and
signatures. Fully reboot to clear the framework's cached trust. Backups and signer
receipts remain in the diagnostic directory for recovery; they are not automatically
restored over a newer package database. A later build can replace the signer record
only after the old setup is absent and cleanup has been followed by a full reboot.

## Setup implementation and limits

The shared-UID technique follows the publicly described RMG-Next/DFReroot method:
`android.uid.system` plus process `system`, with two certificate-history references
carrying the shared-UID capability. This prototype has its own Java implementation;
it does not include upstream unlicensed injector code.

The setup entry runs through the installed controller APK as `app_process` under
existing root. It accepts only inspect, prepare, and remove operations on fixed
paths and fixed package identities. This is administrative setup, not an early-root
exploit. The controller's signing certificate must already be recorded by Package
Manager, and the embedded helper must match its signer and declared identity.

This prototype supports only ABX v0 registries that re-encode byte-for-byte before
mutation. Unsupported text/XML formats, token types, malformed counts, duplicate
history blocks, missing controller certificates, and unexpected file ownership
are refused. Existing numeric wire types and bit patterns are preserved. Added
certificates are index-only references to the already installed controller signer,
so they do not introduce new entries into the global certificate table. Foreign
signatures and all data outside the target history are checked after transformation.
Android's own XML parser validates the original and staged documents as well.

A unique backup and staged file are fully written, fsynced, and checked before an
atomic rename. Original owner, group, mode, and SELinux label are copied and
verified. The file is never truncated in place. Detected package-manager writes
abort before the rename; this is not a shared lock with Package Manager, so avoid
concurrent installs and reboot promptly. A commit followed by a verification error
is explicitly marked as committed: inspect it before repeating or rebooting.

The helper is Java-only and has no custom native library or root command path.
Its callbacks enqueue bounded diagnostics, then return. Workers read properties
through the framework, record boot identity/UID/process/timing, and exercise only
ordinary IPsec allocation/setup. Resources are closed without applying a transform
to a network socket. Up to 25 serialized checks are attempted, initially every
250 ms, then every second, until success. Boot sampling has a 90-second deadline;
CPU sleep or an in-flight binder call can defer completion. There is no wake lock.
Observations are capped at 200 kB and retained in a system-owned diagnostic folder.
Only the matching controller can retrieve reports or change this helper's binding
through the signature-protected provider. Resolving that provider does not start
sampling; only the condition-service callback does.

A candidate requires UID 1000, process `system_server`, an actual startup callback,
explicit locked state, successful IPsec setup, and readable pre-exit animation
signals. Empty properties remain unknown. Reports from previous or mixed boots
cannot claim a current window, and an earlier exit blocks later animation cycles.
IPsec's first observed success does not establish when it first became available.
The test does not prove that the full root sequence or module startup fits in the
observed window. Samsung's actual startup order and helper installation remain
hardware tests.

## Build and provenance

Use the **Early boot diagnostic probe** workflow on the experimental branch. The
controller build depends on the release helper and embeds that exact APK. The
workflow tests the codec/trust transformations, report qualification, and command
validation; lints both APKs; verifies signatures, version, permissions, process,
protected components, and that the privileged helper has no native/root setup code.
Both APKs are signed using this run's same disposable diagnostic key and do not use
the production RMGU release key.

Primary implementation references:
- RMG-Next commit `a2eab34a6efdfe09c34c5232cd1b67255607bb15` (manifest and setup behavior).
- AOSP ABX v0 `BinaryXmlSerializer` / `FastDataOutput` protocol.
- AOSP `ManagedServices.readXml` and condition-provider binding.
