# DirtyFrag provenance (DF-1)

Exploit family for OneUI 9 / newer kernels, alongside the M3Q flow (which is
untouched). Upstream: **https://github.com/diabl0w/DFRoot**, now GPL-2.0.

Two upstream revisions are vendored because the userspace chain and the kernel
module moved independently:

- **e47ea6e** (`2026-09-30`, pre-v3.0) for the userspace chain: `exp.c`,
  `elf_parser.c`, `libcxx.S`, `include.inc`, `aes256.h`, `hmac_sha256.h`,
  `reporter.h`, `splicehelper.c`, and the non-6.12 prebuilt KOs.
- **7a6513a** (`2026-11-06`, v4.7 plus the libbase fallback) for
  `lkm/dfroot.c` and `lkm/Makefile`. The 6.12 modules are rebuilt from this
  source by `tools/build_df_lkm.py`; see `ko/provenance.json`.

## What it is

DirtyFrag (CVE-2026-43284): the kernel decrypts AES-CBC ESP packets directly
into the page cache of files open for `splice()`. Crafted IVs overwrite
16-byte-aligned blocks of mapped shared libraries without write permission.
The chain patches shellcode into `libc++.so`/`libc.so`, triggers via an
orphan process into init (uid 0), loads the embedded `dirtyfrag.ko` through a
vendor library's page cache, and runs the staged ksud as init. No Shizuku, no
WiFi needed.

## Vendored here

- `exp.c`, `elf_parser.c`, `libcxx.S`, `include.inc`, `aes256.h`,
  `hmac_sha256.h`, `reporter.h`, `splicehelper.c` (built, not shipped),
  from e47ea6e.
- `lkm/dfroot.c`, `lkm/Makefile`, `lkm/LICENSE`, from 7a6513a (GPL-2.0).
- `ko/dirtyfrag-android{12-5.10,13-5.10,13-5.15,14-5.15,14-6.1,15-6.6,17-6.18}.ko`
  (12048/10920/11168/11168/7176/7624/8176 bytes), unchanged e47ea6e blobs.
- `ko/dirtyfrag-android16-6.12.ko` and
  `ko/dirtyfrag-android16-6.12-{kernelsu,kernelsu-next,resukisu,kowsu}.ko`
  (12824 bytes each, stripped), rebuilt from the 7a6513a module by
  `tools/build_df_lkm.py`. `select_ko_image()` in `exp.c:362` picks the
  per-flavor image when `uname` reports android16/6.12 and the flavor is
  known, and the generic image otherwise. The module's command is the same
  RMGU bridge the old e47ea6e blobs carried: stage the selected daemon at
  `/data/user_de/0/dev.experimental.azhlroot/ksud`, run
  `ksud late-load --package-name <flavor>`, and drive the marker contract:
  - `/dev/dfm0` when the backend driver appears;
  - `/dev/dfm1` on a 120 s timeout or a non-zero daemon exit;
  - `/dev/dfro` after pre-load block-device protection;
  - `/dev/dfwipe` after the armed module-state wipe (or `/dev/dfwipeskip`
    when a backend is already live);
  - `/dev/dfmoddis` after disabling installed modules.
  The command backgrounds late-load because it hangs after the daemon is up;
  waiting for its exit would never fire. Flavor manager packages:
  `me.weishu.kernelsu`, `com.rifsxd.ksunext`, `org.bakasu.bakasu`. The
  `kowsu` image is derived from the KernelSU image by
  `tools/prepare_df_backends.py`, which blanks the manager override in
  non-executable data; KowSU's daemon uses its compiled
  `com.kowx712.supermanager` default.
- `../../assets/df/ksud` (6028336 bytes, diabl0w's KernelSU fork build).
- `DfExploitRunner.java` / `DfReporter.java` (from `df.root.ExploitRunner` /
  `IReporter`; only the package, class names, JNI binding and the `df/ksud`
  asset path were adapted).

## One UI 9.5 / kernel 6.12.69 panic fix (issue #68)

The 1.4.8 module was built from e47ea6e and had no `module_exit`. On
`S948BXXU6ZZKN` (One UI 9.5 beta, kernel 6.12.69) the module load rebooted the
phone right after the second page-cache patch. The old module also skipped
Samsung DEFEX functions with kprobes and never patched `avc_has_perm`, so it
could not survive the newer kernel even when it loaded.

The 7a6513a module carries the fixes:

- an empty `module_exit`/`cleanup_module`, because some kernels' `cfi_init`
  walks both the init and cleanup jump tables and crashes in `strcmp` when the
  cleanup entry is absent;
- `avc_has_perm`/`avc_has_perm_noaudit` patching when the `selinux_state`
  write is a no-op (`CONFIG_SECURITY_SELINUX_DEVELOP=n`);
- direct DEFEX patching through `aarch64_insn_patch_text` instead of kprobes
  that skip the function body;
- `crash_dump64` page-cache invalidation after the trigger;
- BTI/PAC-aware return stubs.

RMGU keeps its own command instead of upstream's `/data/user_de/0/df.root/bootstrap`,
so the per-flavor staged daemon and the existing marker contract stay intact.

## Second issue #68 root cause: hook shellcode placement (1.4.9 analysis)

The reporter's panic capture showed the 1.4.9 reboot was a different, earlier
failure than the missing `module_exit`: `Kernel panic - not syncing: Attempted
to kill init! exitcode=0x0000000b`, PID 261 comm `init` (ueventd), SIGSEGV at
syscall return, with a `crash_dump64` exec one second earlier and no `dfroot:`
output at all. The phone died roughly one second into the run, during the
write/hook phase, before any module load - and the preserved 1.4.8-era panic
archives show the identical signature. The `module_exit` fix was real (upstream
added it for kernels that walk both CFI jump tables) but not the defect that
reboots this phone.

The defect was in the e47ea6e userspace chain: `elf_parser.c` placed the hook
shellcode blindly at the executable segment's file tail. On the Android 17 /
One UI 9.5 `libc++.so` that write runs past the mapped exec page into the
bytes that follow it in the file - shared libc++ data that any process can
fault in at any moment. An init-family process (ueventd, comm `init`) hit the
corrupted page within a second of the run starting, and Samsung's kernel
panics when an init-named process dies, so the phone rebooted before the
chain could finish. The old parser also recognized only PACIASP and BTI c at
the hook site, so a PACIBSP/BTI-j preamble was left under the trampoline,
which breaks the function's return path the same way. That is why standalone
DirtyFrag 1.18 (upstream V3 userspace) works on the same phone while 1.4.8
and 1.4.9 did not.

`elf_parser.c` now carries the upstream 7a6513a (V3) placement, adapted to
the reporter plumbing: the shellcode lands in the tail only while it fits the
rest of its page, otherwise in a zero run (alignment padding) inside the
executable segment, and it refuses to run when neither exists. The size budget
is the 16-byte-padded write, so the jmpback stub cannot spill past the
placement either. The hook site skips up to two BTI/PAC preamble instructions
(PACIASP, PACIBSP, BTI, BTI c, BTI j, BTI jc) before the trampoline lands on
real code. Host tests pin the opcodes, the padded budget and the placement
bounds (`tools/test_m3q_host.py`).

The 7a6513a module also drops the old `soft_reboot` module parameter. RMGU's
userspace chain now matches upstream V3 exactly: `libcxx.S` no longer carries
the `soft_reboot=1` insmod argument or its flag, and the JNI/`DfRunner`
plumbing for the flag is removed. That parameter belonged to the e47ea6e
module; passing it to the 7a6513a module would fail the insmod as an unknown
parameter, so the machinery is gone rather than merely kept off.

This removal is confined to the native exploit chain. The app's real soft
reboot feature is a separate path and is fully retained: `RecoveryTool
.SoftReboot` runs the Samsung-patched bundled daemon through
`RootRecovery.softReboot`, restarting the hardware composer services before
the framework (the 1.2.7 DeX fix), selecting the bundled daemon so a
manager-installed `/data/adb/ksud` cannot lose the fix (1.2.8), and the
shared lifecycle hooks that cover manager-triggered soft reboots (1.2.9).
Nothing in the DirtyFrag load path invokes or depends on any of it; the
exploit chain only loads the backend and leaves reboot timing to the app.

The userspace chain stays at e47ea6e. Upstream's post-e47ea6e V3 refactor
removed the HMAC/ICV ESP framing that RMGU's Samsung chain passes from Java,
and RMGU's `exp.c` also carries its own JNI reporter, readiness wait, marker
reporting and per-flavor KO selection. Porting the refactor would drop those
Samsung-specific pieces; the kernel module is the part that caused the panic,
so only it is rebased.

## Adaptations (exact)

- `exp.c`: `FindClass("df/root/IReporter")` became
  `"dev/busung/s25uroot/dirtyfrag/DfReporter"`; JNI entry
  `Java_df_root_ExploitRunner_nativeRunAll` became
  `Java_dev_busung_s25uroot_dirtyfrag_DfExploitRunner_nativeRunAll`.
- Native lib keeps upstream's name (`exp`).
- `splicehelper` builds into CMake's binary dir (never into sources) and is
  found by `.incbin` through the binary-dir include path.
- `lkm/dfroot.c`: upstream's `bootstrap` exec is replaced with RMGU's
  late-load command; `cmd[512]` grows to `cmd[4096]`. The source is otherwise
  upstream 7a6513a, including `module_exit`, the SELinux guard and the DEFEX
  patches. `tools/build_df_lkm.py` renders the per-flavor command and checks
  that the source still contains those fixes.
- `elf_parser.c`: e47ea6e base plus the upstream 7a6513a V3 shellcode
  placement (`find_code_cave`) and the full BTI/PAC preamble list. The
  e47ea6e blind tail placement is gone; see the second issue #68 root cause
  above. `exp.c` passes the padded shellcode size to the placement.

## Deliberately NOT vendored

- `BootReceiver.java`, `MainActivity.java`, resources (our app has its own
  boot, automation and UI flows; DF-2 wires the runner into those).
- `bootstrap.c` and the `/data/user_de/0/df.root/bootstrap` handoff (RMGU's
  module runs the staged daemon directly).
- `build.sh`, gradle wrapper (our toolchain builds it).

## Build

```
python tools/build_df_lkm.py            # build every 6.12 flavor
python tools/build_df_lkm.py --check    # verify source/provenance/KO hashes
```

The build needs Docker and `ghcr.io/ylarod/ddk-min:android16-6.12`; the
Makefile's `llvm-objcopy` strips the module inside the container. Outputs and
the exact source/command hashes are recorded in `ko/provenance.json`.
`build-df-variants.sh` is a thin wrapper around the same tool.

## License

The vendored DirtyFrag component (userspace chain and kernel module) is
GPL-2.0; `lkm/LICENSE` is the upstream copy. The rest of Root My Galaxy Ultra
remains under the repository's Apache-2.0 license. Keep the component's
license notice with any redistribution of these files or the built modules.

## Roadmap (DF-2)

Done: `DfRunner` (IpSec setup, JNI, 120 s marker poll, native-probe grant-free
verify, su-verified fallback), `DfCatalog` closed payload bundles with recorded
SM-S948B / S948BXXS4BZIG and SM-S948W / S948WVLU4BZID profiles plus runtime
profiles for eligible S26 Ultra regional/carrier variants. Shizuku preconditions
are bypassed for the DirtyFrag route, with per-flavor
daemons from `asset://azhl/<flavor>/ksud`. M3Q/AZHL behavior unchanged.

First-try = `choose flavor -> run -> Installed` via `dfm0` or native probe, no
manager grant. The su-grant hold remains only as fallback when neither
grant-free signal fires.

The Canadian BZID profile reuses the same native helper and per-flavor bridges;
its Android 16 / Linux 6.12 kernel family and 4 KB pages were recorded in the
supplied inventory. No native code or binary changed for this profile. Hardware
validation of its backend loading remains pending; see
`docs/device-candidates/SM-S948W-BZID.md` at the repository root.

## S26 Ultra family profiles

`DfPort` recognizes regional SM-S948 names and the SC-53G/SCG37 carrier aliases,
with Samsung/m3q, ARM64/aarch64, 4 KB pages, API 36+ and an android16 / Linux 6.12
kernel. The exact AZHL identity selects its existing GhostLock route.
`DfCatalog.forDevice()` derives additional profiles from the checked bundled
backend templates, binds them to the observed full identity and generates
stable IDs from that identity. Staging reconstructs the current APK-owned
profile and compares every field before writing a daemon. Caches and retries
retain their exact firmware requirements. Model recognition and kernel-family
matching do not establish vulnerability or vendor ABI compatibility; hardware
validation remains required. See `docs/S26-ULTRA-SUPPORT.md` at the repo root.

## BakaSU manager package (2026-10-05)

The resukisu image keeps its filename and is built from the same 7a6513a
module with `--package-name org.bakasu.bakasu`. The manager literal is part of
the command string, so the rebuilt image has one byte less in `.rodata` than
the KernelSU image; the aligned file size is still 12824 bytes. The old 1.4.8
resukisu blob was a space-padded edit of the KernelSU blob; the rebuild removes
that special case. `tools/build_df_lkm.py` builds it directly and records its
hash in `ko/provenance.json`.

## KowSU integration (2026-10-26)

KowSU uses `dirtyfrag-android16-6.12-kowsu.ko`, derived from the bundled
KernelSU bridge by `tools/prepare_df_backends.py`. The generator checks the
source SHA-256 and removes only its manager-package override in a
non-executable ELF section. The existing command, instructions, image
protection, module policy and deadlines are retained. KowSU's daemon uses its
compiled `com.kowx712.supermanager` default, matching its driver. The source and
output hashes and the exact edit are in `backends/kowsu/dirtyfrag-bridge.json`.
`exp.c` selects this bridge explicitly for `kowsu`; CMake embeds it.
