# SM-S948B BZJA validation

Status: maintainer confirmed successful phone testing on 2026-10-30.
Users also reported success on other S26 Ultra models; those reports do not
provide an exact firmware/backend inventory for every variant.

The existing 1.2.28 firmware-family route already selects BZJA. The signed
`1.2.28-bzja-test1` build used the production payloads unchanged.
Following successful phone testing, release 1.3.0 records the exact BZJA SusFS
profile and removes the testing labels. The native payloads remain unchanged.

## Firmware examined

- Model and region: SM-S948B / EUX
- Firmware: `S948BXXU5BZJA`
- Official Samsung FUS package: `SM-S948B_2_20261016025151_cwbgha4g4w_fac.zip.enc4`
- Kernel: `6.12.69-android16-6-pa4ef354-abogkiS948BXXU5BZJA-4k`
- Kernel build date: 2026-10-15 08:56:16 UTC
- Extracted `boot.img.lz4` SHA-256: `4cd48062cbd1d7b037c9e0c52a5eea3dbf28f950d0caedac43a55509a2b0141f`
- Decompressed kernel SHA-256: `62163366f26c083b7722c61960cab02d3b33acd7d07f8a99049acd078a404695`

Only the boot-image portion was retrieved and decoded; no full firmware ZIP
checksum was calculated. No firmware was flashed during the offline review.

The actual binary lacks the standard CVE-2026-43284 shared-fragment fix at all
four affected IPv4/IPv6 input/output sites. Its BTF layouts match the relevant
`sk_buff` and `skb_shared_info` offsets used by the bundled helper. The kernel
uses 4 KB pages, enables ESP/XFRM, and has kernel BTI disabled. These are static
compatibility findings, not proof of successful root or module activation.

## App checks

Regression coverage uses the exact BZJA kernel release and intended SM-S948B
Android 17 / SDK 37 / arm64-v8a / 4 KB device identity. Those Android properties
are test inputs, not a live device inventory. The tests check all four backend
selections, the stale BZIG profile refusal after updating, and selection of the
exact BZJA SusFS profile.

The test workflow also runs the existing backend provenance, SusFS host,
Android unit, early-root, D2 companion, APK signature and packaged-payload checks.
The artifact includes the signed APK, its SHA-256 checksum and version metadata.

## Additional validation coverage

The maintainer reports that everything works on BZJA. Exact backend-by-backend
results and separate cold-boot/restart timing records were not supplied. Continue
to record the firmware, model and backend when testing additional configurations.
One successful configuration does not establish compatibility with future patched
firmware.
