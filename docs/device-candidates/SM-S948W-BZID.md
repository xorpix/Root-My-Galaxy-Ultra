# SM-S948W / BZID experimental port

**Status: experimental runtime support enabled; hardware validation pending.**

The supplied inventory was collected on 2026-10-05 against app source
`5287e5bb112ef33143ba23457c05255e5498cf27`. It explicitly reports
`compatibility: not_assessed`. The adjacent JSON contains only the selected
device/kernel identity fields, without a user name, serial number or app list.

| Field | Reported value |
|---|---|
| Model / device | SM-S948W / m3q |
| Board / SoC | canoe / QTI SM8850 |
| Firmware incremental | S948WVLU4BZID |
| Bootloader | S948USQU4BZID |
| Android / API / One UI | 17 / 37 / 90000 |
| Kernel | 6.12.69-android16-6-pee899be-abogkiS948USQU4BZID-4k |
| ABI / page size | arm64-v8a / 4096 |
| SELinux | Enforcing |

It shares the Android 16 / Linux 6.12 kernel family and 4 KB page size with the
supported SM-S948B/BZIG profile. The kernel hash and vendor build identifier
are different. The Canadian model's U-series bootloader/kernel strings are
recorded exactly as supplied; they are not rewritten to match its model name.

The inventory contains no matching kernel configuration, BTF type information,
module ABI evidence or successful hardware test. It cannot establish that the
existing Samsung backend patches and loading path work on this vendor kernel.

## Runtime support

`DfPort` and the bundled DirtyFrag catalog now register this exact model,
incremental, full kernel release, SDK, ABI and page size for all three backends.
The existing native helper selects its Android 16 / Linux 6.12 bridge from
`uname` and the selected backend. It resolves userspace patch locations from ELF
data at runtime; no BZIG-specific exploit offset or new native blob is used here.
The backend daemons remain the same hash-verified Android 16 / 6.12 builds used
by BZIG. This enables a test of the Canadian build without asserting that its
vendor kernel ABI or backend loading has already been verified.

The install path uses DirtyFrag directly, without Shizuku or the AZHL boot-settle
floor. Settings and recovery hide the same inapplicable controls as on BZIG.
Nearby firmware versions, other regional models and different kernel releases
remain outside the registered profiles.

## Device validation

Start with a full reboot, choose one backend and run from Home. Export the run
history, confirm the selected backend and UAPI 5 in its matching manager, and
check a superuser shell before enabling modules or root-on-boot. Record any
backend-load failure with its run log. Fully reboot before testing another
backend or retrying a failed attempt.

If readable through the tester's existing authorized ADB connection, collect:

```sh
adb pull /proc/config.gz SM-S948W-BZID-config.gz
adb pull /sys/kernel/btf/vmlinux SM-S948W-BZID-vmlinux.btf
```

These are read-only copies; they do not root or change the phone. If either is
unavailable or denied, retain that error rather than attempting to bypass it.
Matching vendor kernel build artifacts can supply missing configuration/ABI
information if the first hardware test exposes a loading problem. No success,
safe-load or Knox-preservation claim follows from the inventory or the profile.
