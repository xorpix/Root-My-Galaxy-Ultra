# DirtyFrag provenance (DF-1)

Exploit family for OneUI 9 / newer kernels, alongside the M3Q flow (which is
untouched). Source: **https://github.com/diabl0w/DFRoot** at commit
`e47ea6eb546d71394a454454fd4b08a7af5de170` (master HEAD as vendored).

## What it is

DirtyFrag (CVE-2026-43284): the kernel decrypts AES-CBC ESP packets directly
into the page cache of files open for `splice()`. Crafted IVs overwrite
16-byte-aligned blocks of mapped shared libraries without write permission.
The chain patches shellcode into `libc++.so`/`libc.so`, triggers via an
orphan process into init (uid 0), `modprobe`s the embedded `dirtyfrag.ko`,
and execs ksud from a memfd. No Shizuku, no WiFi needed.

## Vendored here (verbatim except the renames below)

- `exp.c`, `elf_parser.c`, `libcxx.S`, `include.inc`, `aes256.h`,
  `hmac_sha256.h`, `reporter.h`, `splicehelper.c` (built, not shipped)
- `ko/dirtyfrag-android{12-5.10,13-5.10,13-5.15,14-5.15,14-6.1,15-6.6,16-6.12,17-6.18}.ko`
  (12048/10920/11168/11168/7176/7624/8104/8176 bytes; selected at runtime
  by `uname`, so OneUI 9 / BZIG and BZID take a per-flavor blob below, not generic)
- `ko/dirtyfrag-android16-6.12-{kernelsu,kernelsu-next,resukisu}.ko`
  (11128 bytes each stripped; selected by `select_ko_image()` in `exp.c:352` when
  `uname` reports android16/6.12 plus matching `flavorId`. Each embeds
  `mkdir -p /data/adb && cat /data/user_de/0/dev.experimental.azhlroot/ksud`
  `> /data/local/tmp/.ksud-stage && <ksud> late-load --package-name <flavor>`
  `> /data/local/tmp/.ll.log 2>&1 &` plus a 60x2 s driver poll:
  `[ -e /sys/module/kernelsu ] || [ -e /sys/module/ksunext ] ||`
  `[ -e /sys/module/sukisu ] || [ -e /sys/module/resukisu ] ||`
  `[ -e /sys/module/ksu ] && touch /dev/dfm0 && exit 0` on first sight
  (late-load hangs after the daemon is up, so waiting for its exit never
  fires), `touch /dev/dfm1` on 120 s timeout or non-zero exit. Flavor manager
  packages: `me.weishu.kernelsu`, `com.rifsxd.ksunext`,
  `org.bakasu.bakasu`. A `df-wipe-requested` flag next to the staged
  daemon arms a pre-load module-state wipe (modules only, grants kept;
  skipped while a backend is live). Staged ksud comes from
  `DfCatalog.stage()` per-flavor `asset://azhl/<flavor>/ksud`.)
  Rebuild with `build-df-variants.sh` (DDK docker, NDK objcopy strip).
- `../../assets/df/ksud` (6028336 bytes, diabl0w's KernelSU fork build)
- `DfExploitRunner.java` / `DfReporter.java` (from `df.root.ExploitRunner` /
  `IReporter`; only the package, class names, JNI binding and the `df/ksud`
  asset path were adapted)

## Adaptations (exact)

- `exp.c`: `FindClass("df/root/IReporter")` became
  `"dev/busung/s25uroot/dirtyfrag/DfReporter"`; JNI entry
  `Java_df_root_ExploitRunner_nativeRunAll` became
  `Java_dev_busung_s25uroot_dirtyfrag_DfExploitRunner_nativeRunAll`.
- Native lib keeps upstream's name (`exp`).
- `splicehelper` builds into CMake's binary dir (never into sources) and is
  found by `.incbin` through the binary-dir include path.

## Deliberately NOT vendored

- `BootReceiver.java`, `MainActivity.java`, resources (our app has its own
  boot, automation and UI flows; DF-2 wires the runner into those).
- `dirtyfrag-lkm/` sources (reference for the prebuilt blobs above;
  upstream: `dirtyfrag-lkm/dirtyfrag.c`).
- `build.sh`, gradle wrapper (our toolchain builds it).

## License warning

Upstream ships **no LICENSE file**. Default copyright applies. These files
are vendored for local test builds only; **do not publish a release
containing them without diabl0w's explicit permission** (or an upstream
license). Upstream credits the underlying ideas to lspromise, DFReroot
(polygraphene) and DirtyInit (combeng6th).

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

## BakaSU manager migration (2026-10-05)

The resukisu bridge keeps its filename and size. Only the manager-package
literal in its non-executable data section changes to `org.bakasu.bakasu`,
with space padding. The command suffix and executable sections are unchanged.
The reproducible, input-hash-checked edit is in
`tools/backend-refresh/stage.py`. This is not a new exploit build.
