# Root on boot recovery review

A firmware update can change the generated profile while keeping the same bundled backend bytes.
In 1.4.0, the offline cache directory name used only three binary hashes. Publishing skipped an
existing directory, including its old payload.json, but returned the newly constructed descriptor
to the log. The log could therefore report the new profile as cached while the next boot read the
old firmware identity. Removing app data removed that directory and concealed the defect.

## Changes

- Save complete cache generations and atomically switch the active record. A healthy generation is
  reused only when all metadata and both files still match. A successful manual run repairs missing
  or corrupt entries. Failed copies leave the previous active generation intact. One previous
  generation is retained for readers already holding its files; explicit clearing removes both.
- Read the active cache, root receipt, boot claims, root-on-boot switch and retry request from files
  reopened across processes. Serialize changes with operating-system file locks. Existing settings
  and valid v1 caches remain readable; a cleared cache cannot be resurrected by a stale legacy pointer.
  A running worker sees a later master-switch opt-out. Damaged boot options stay off until an explicit
  edit repairs them, without silently restoring an older retry request.
- Save the cache before activating SusFS, while the observed kernel name is still the run's original
  identity. A verified manual DirtyFrag run also renews an already-enabled early-root policy for the
  same backend. Disabled choices stay disabled, and the current bundled profile must match exactly.
- Allow the normal unlocked boot worker to handle early-root preparation failures. A recorded native
  attempt prevents fallback, so this does not permit a second exploit attempt within the same boot.
- Claim an automatic attempt after delay and transport preparation. The old order consumed a one-shot
  retry and then rejected it because its request was gone. Preserve a retry armed for the next boot.
- Ignore duplicate boot broadcasts without stopping an existing worker. Report the actual cache
  refusal, including changed firmware or damaged files, instead of always claiming no install exists.

## Validation

Regression coverage uses real cache directories, legacy-pointer migration, same-byte firmware changes,
corrupt files and metadata, interrupted publication, bounded generation retention, existing early-root
opt-ins, and independent JVM processes contending for shared state and the same boot claim. The full
Android unit suite, boot handoff tests, signed APK build, embedded companion verification, and retained
backend/SusFS integrity checks run in the Early Root Test Build workflow.

The maintainer confirmed the combined fast-boot candidate working on an S26 Ultra on 14 November 2026.
The firmware-update cache-repair case has automated regression coverage. Install the update over the existing app, keep its existing
data, and fully reboot to check automatic rooting. If the previous cache was rejected after a firmware
update, complete one manual root run and reboot again. With early root already enabled, that reboot
should use the renewed configuration without toggling it or reinstalling. Firmware or backend
changes still require a successful manual run before early-root approval is renewed. The native root
method, bundled backends and SusFS module are unchanged.
