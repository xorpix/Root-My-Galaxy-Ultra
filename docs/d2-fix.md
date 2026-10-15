# D2 fix: keep Odin reachable

This optional setting implements the DMC AT-flag workaround described by
[polygraphene/DFReroot](https://github.com/polygraphene/DFReroot/blob/main/D2_Error.md)
and used by Root My Galaxy Next. It is **off by default** and needs a separate,
release-signed companion. It does not run a root exploit or enable autoroot.

Samsung's newer bootloaders can reject Download Mode with D2 when the DMC flags
are `[lock=1, maintenance=0, at=0]`. Samsung resets AT during Android startup.
The helper sets AT back to 1 after boot broadcasts and verifies the result.
This is an undocumented Samsung interface: matching the observed record format
does not establish compatibility with every firmware or guarantee recovery.

## Setup and use

1. Open **Advanced settings → Recovery → D2 fix · Keep Odin reachable**.
2. Root normally and grant RMGU root access in the manager. Optionally run
   **Inspect setup** first; it reads the registry without changing it.
3. Read the preparation confirmation and choose **Prepare helper certificate**.
   Avoid app installations during this step. **Fully reboot immediately** using
   the provided button or the power menu.
4. Reopen RMGU and choose **Install embedded helper**. Complete Android's APK
   installation prompt. The helper is embedded; no third-party download is used.
5. **Fully reboot again** after installing the helper. Choose **Activate helper for boot**,
   then enable the D2 switch. Check that the
   status reports an actual current-session AT=1 reading.
6. To test independence from root, turn **Root on boot** off, fully reboot, leave
   the screen lock enabled, and check the D2 reading again without rooting.
   Confirm real Download Mode access separately on the test phone.

An enabled switch records intent. The status separately shows the observed
flags, the observation's age, an unconfirmed/timeout condition, and whether the
reading belongs to this Android session. Old observations cannot report current
verification after a framework or full restart. A successful flag read-back does
not prove that a particular bootloader will accept Odin.

Turning the switch off stops future writes. It deliberately does not clear AT or
change the screen lock/maintenance flags. Samsung normally clears AT during the
next full boot. Root and autoroot settings remain independent.

## Helper and transaction boundaries

- Package: `dev.experimental.azhlroot.d2helper`; UID 1000; process `system`.
  This matches the measured VaultKeeper access context. The companion is Java
  only, non-debuggable, has no native payload, and requests only the boot receiver
  permission. It has no launcher entry.
- The only exported controls are an explicit bound service for fixed status,
  read-only refresh and switch operations, plus the activation activity. They require a signature permission and additionally
  check the exact controller UID/package and signing certificate. No shell,
  arbitrary file, property, vault name, record number or byte-offset API exists.
  Each Messenger request checks the framework-provided sender UID, has a short
  deadline, and enabling also requires the running source/version to match the
  controller. Control work runs off the system main thread. The app unbinds after
  its query or cancellation; there is no permanent or foreground control service.
  Read-only refresh does not set the activation flag or change the saved switch.
- Boot work uses the private system-data settings file and `LOCKED_BOOT_COMPLETED`,
  `BOOT_COMPLETED` and package-replacement delivery. Explicit activation clears
  the new package's stopped state. Force-stopping or disabling the helper can
  prevent future boot delivery; activate it again after doing so.
- The switch and activation are stored in the helper's fixed
  `/data/system/rmgu-d2-settings.bin` file, with system ownership, private mode,
  atomic replacement, file/directory sync and exact read-back. This avoids app-data
  preference writes that the system process can be denied even with UID 1000.
  Settings reads fail closed. Live observations remain in memory and disappear
  with the system process; they are never mistaken for a new boot's readings.
- A single worker checks at approximately 0, 0.5, 2 and 6 seconds. It rereads after
  successful operations because Samsung may reset AT during boot. The broadcast
  is finished within 8 seconds; there is no permanent poller or root invocation.
  A blocked vendor Binder call cannot be forcibly cancelled. The deadline gates
  any subsequent write and a timed-out result is never shown as verified.
- Only a 32-byte record with known 0/1 flag fields is accepted. Only byte 2 is
  changed; all other bytes are preserved. A second read checks for a concurrent
  change before writing, and the complete 32-byte result must match afterward.
  Failed or uncertain writes stop that worker's retries. The Samsung API offers
  no compare-and-swap, so a concurrent vendor write cannot be excluded entirely.

## Preparation, updates and removal

The one-time preparation uses existing root to add the app's signing certificate
to the system shared UID's signature history. **Other APKs signed with that key
could also join this UID.** This changes package-signature trust, not just an
ordinary app setting, and an incorrect registry modification can prevent boot.

The setup accepts only validated ABX v0 registries, verifies byte-identical codec
round trips and Android framework parsing, preserves unrelated package entries
and signatures, makes a verified backup, checks for concurrent Package Manager
changes, and atomically renames a fully written, fsynced file with the original
owner/mode/SELinux label. A root-only setup lock serializes RMGU mutations.
Package Manager is still running: its later writes remain a reason to reboot
promptly and check installation rather than assuming a receipt guarantees trust.

Receipts and backups are kept in `/data/system/rmgu-d2-setup`. The helper's signing
trust persists across reboots; it is not a one-boot certificate. The embedded APK
is checked for package, signature, digest, version and source identity. A source
change offers **Update embedded helper**; after installation, **fully reboot** to
load its new code into Android's system process. The app distinguishes an update
that still needs installation from one installed but waiting for reboot. A
main-app-only rebuild does not force an unnecessary helper update.

The original provider transport could become unreachable after a full reboot:
Android's system-provider startup skips sideloaded APKs in the `system` process.
The bound service avoids that provider path. An unavailable connection now preserves
the installed helper identity so updating and activating remain accessible, without
reporting an unconfirmed switch or AT reading as verified. Updating from either
earlier D2 test needs **Update embedded helper**, a full reboot and activation.
Certificate preparation does not need to be repeated. The app does not bind newly
installed helper code until a full reboot; this avoids loading a new component
from Android's old cached APK. First installations also require that reboot.

When updating from the first D2 test APK, old app-directory preferences are
deliberately not imported: a failed preference commit could still have changed
its in-memory value. Update the embedded helper, fully reboot, activate it again
and explicitly enable the switch. Existing certificate preparation remains valid;
it does not need to be repeated just to update this helper.

Use **Remove helper and owned trust** with existing root, then fully reboot. It
uninstalls the companion before editing a freshly read registry. It removes only
the recorded signer's history references, retaining trust if another system-UID
app needs it. Without an ownership receipt it removes the matching helper and
retains pre-existing trust. It never restores an old complete package database
over the current one. Removal also deletes only the fixed, metadata-validated
helper settings file and its atomic-write siblings. Verified backups are retained
for manual recovery.

## Validation limits

Host tests exercise byte preservation, unknown records, cancellation, concurrent
changes, uncertain writes and exact read-back, plus ABX/certificate-table
validation and scoped cleanup. Builds check the actual nested APK's signature,
source fingerprint, manifest, direct-boot receiver and permission surface.
Weekly releases rebuild/sign the helper in the fresh signing job and align and
sign the outer APK afterward; no disposable helper key is shipped.

**Phone validation remains required.** Very early boot failures before Android
can deliver a broadcast and the brief reset/reapply interval remain uncovered.
There is no boot-animation hold or claim of guaranteed unbricking. No new firmware
root support, backend update or SusFS behavior is introduced by this feature.
