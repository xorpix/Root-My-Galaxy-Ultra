# SM-S948B BZJA test build

Status: offline kernel review completed; phone validation pending.

The existing 1.2.28 firmware-family route already selects BZJA. This test build
keeps the production payloads unchanged and uses the version suffix `bzja-test1`.
It does not add BZJA to the list of hardware-tested SusFS kernels.

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
selections, the stale BZIG profile refusal after updating, and retention of the
untested SusFS family-fallback classification.

The test workflow also runs the existing backend provenance, SusFS host,
Android unit, early-root, D2 companion, APK signature and packaged-payload checks.
The artifact includes the signed APK, its SHA-256 checksum and version metadata.

## Phone validation still needed

Before recording BZJA as hardware tested, verify root with the selected backend,
the reported running kernel, SusFS activation and features, and normal app use.
Then check a cold boot with early root enabled and the existing supported restart
path. Record which backend was actually tested; one successful backend does not
validate all four. The existing untested-kernel warning remains until validation.
