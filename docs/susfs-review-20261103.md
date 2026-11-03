# SusFS upstream review — 3 November 2026

> **Historical comparison.** This review examined the pre-1.3.4 Samsung bundle
> at the revisions listed below. The subsequent audit repairs shipped in
> [1.3.4](https://github.com/xorpix/Root-My-Galaxy-Ultra/releases/tag/1.3.4);
> see the [current repair report](susfs-repairs-20261103/README.md).

The pinned Samsung implementation already covered the specific upstream lifetime
and mount-identity changes assessed here. The included patch for upstream issue
#167 remains a separate contribution for the reviewed upstream revision; it is
not an additional change to RMGU's bundled module.

## Exact comparison

- App: `xorpix/Root-My-Galaxy-Ultra@3c1394edcbc49e40cc46b0c31eb4d09f0be4c42b`.
- Bundled Samsung source: `8b9d19b3b348439b519260706896247e25633ec9`;
  archive SHA-256 `953216c328b35ab9bf5ebb7599d0f9a5627565276a0ea340d2be1372a1fddd46`.
- Module SHA-256: `d8008ad50ae174ca321c2320ca66152b540f84a72fc92d845b751ac93bd783c7`.
- Previous upstream review: `646c9abbd8f6c90b311ef2daba0dbf1a732d6c18`.
- Current upstream main: `6c4ab849a0b6fd9b039552da8664cea77cd7743d`.

The current comparison contains five commits: the hide-syms rollback
(`7539220`), create-gate removal (`7fe8355`), their merge (`494e2dd`),
compat restoration (`46590bb`), and create-gate restoration (`6c4ab84`).
Upstream has two open PRs (#165 and #171) and three open issues (#80, #149,
and #167). Closed reports #141, #164 and #168 were also checked.

## Commits and pull requests

| Change | Applicability and decision |
| --- | --- |
| [#169 — compat restoration](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/169) | Our `susfs_fp_remove()` already restores native and compat independently, verifies restoration and drains callbacks. A foreign native owner cannot skip compat cleanup. Keep that stronger lifetime handling. Upstream also adds stale-self-wrapper admission checks; those are optional hardening for a future native rebuild, rather than a demonstrated normal-path RMGU regression. |
| [#165 — priority and sequence-hook races](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/165) | Native/compat separation, transactional configuration, RCU reclamation, package validation and complete-coverage rollback already exist. Our priority policy deliberately applies in the initial user namespace. Our package lookup uses the caller's existing credentials, so the upstream SELinux credential-blob manipulation is unnecessary. The affected sequence-operation replacements are absent from our kprobe-based map/symbol implementation. |
| [#166 — create/hide-syms rollback](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/166) | Our symbol filtering already uses the tested kprobe route. Our syscall wrappers also retain independent compat restoration and complete callback lifetimes. The 5.15 startup regression is not evidence that the validated Samsung 6.12 path should be removed. |
| [#170 — create-family restoration](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/170) | This is a larger implementation change. Our visible-name operations delegate to the kernel's original syscall; preserve that existing Samsung path rather than replacing it with the new takeover implementation. |
| [#171 — mkdir special-component errno](https://github.com/inforcqb/susfs4ksu-for-lkm/pull/171) | The incorrect `-EINVAL` is in `create_takeover_mkdirat()`, which our fork does not contain. Visible `/`, `foo/.` and `foo/..` requests go to the kernel's original handler in our implementation. No direct backport is needed. |

## Issues

| Issue | Evaluation |
| --- | --- |
| [#167 — missing /data mount](https://github.com/inforcqb/susfs4ksu-for-lkm/issues/167) | Confirmed an upstream false-match mechanism with a production-C reproducer. A bind mount and its visible /data parent can share a block-device source string while having different root inodes. Upstream's source-name fallback matches both. RMGU already matches the device and mount-root inode together under a lock. The included upstream patch removes the broad fallback and prevents mixed keys during record reuse. |
| [#80 — Samsung 6.6 protected LSM write](https://github.com/inforcqb/susfs4ksu-for-lkm/issues/80) | Still unresolved for that hardware path. The reporter established that ordinary module data writes succeed while the protected hook-page store is refused. That does not establish a fault in our validated Samsung 6.12 integration. The experimental negative-dentry variant mentioned in the discussion changes the architecture and still needs functional and teardown stress testing. It is not a drop-in repair for our module. The later uname and proc/readlink discussion already has Samsung-specific fixes or configuration guidance in this project. |
| [#149 — manager module updates](https://github.com/inforcqb/susfs4ksu-for-lkm/issues/149) | A request for updates to the standalone flashable module through KernelSU Manager. RMGU distributes its bundled module through the APK, so it does not need that installer feature. |
| [#168 — MTK 5.10 putname panic](https://github.com/inforcqb/susfs4ksu-for-lkm/issues/168), [#164 — 5.15 SystemUI startup](https://github.com/inforcqb/susfs4ksu-for-lkm/issues/164), [#141 — 6.18 BTI fault](https://github.com/inforcqb/susfs4ksu-for-lkm/issues/141) | Different kernel families and implementation paths. Current RMGU checks cover separate compat originals, rollback, callback draining and the existing symbol-filtering route. These reports do not justify importing an untested create or hook rewrite into the Samsung 6.12 bundle. |

## Upstream patch and reproduction

[susfs-167-mount-identity.patch](upstream-patches/susfs-167-mount-identity.patch)
applies to upstream `6c4ab84` and includes its own host tests.

The matcher uses the coherent `(s_dev, mount-root inode)` key for recorded
identities. Source strings remain diagnostic; configured source-prefix selection
still runs through the existing path selector. Genuine namespace copies still
match. Readers share the existing identity lock with record retirement and reuse,
so a reader cannot combine an old inode number with a replacement device number.
The patch adds no allocation or retained dentry/inode reference to the matching path.

Apply in a checkout of that upstream revision:

```sh
git apply /path/to/susfs-167-mount-identity.patch
python3 tests/mount_identity/run.py
python3 tests/mount_identity/run.py --sanitizers
```

The RMGU-side checker also verifies the original failures and a locking negative
control:

```sh
python3 tools/check_susfs_upstream_mount.py \
  --upstream /path/to/upstream-checkout \
  --evidence-dir /tmp/susfs-upstream-review
```

The original upstream source fails seven of ten cases: bind-parent visibility,
shared source, different device, long source, missing root, slot reuse and
concurrent reuse. The patched source passes all ten in normal and ASan/UBSan
builds. Removing only the new reader lock makes the concurrent-reuse case fail
again while the other nine pass.

This reproduces and fixes a concrete false-match mechanism consistent with
#167. The reporter's complete device scenario has not been retested, so the
issue itself remains open and additional device-specific causes are not excluded.

## Shipping-module validation and limits

The exact shipped source passed the mount, syscall-hook, priority-filter,
lifecycle, map-hook and m_show-equivalence suites in both normal and sanitizer
configurations: six suites and twelve configurations. The mount suite includes
43 functional cases plus its identity-retirement concurrency check.

LeakSanitizer cannot enumerate tasks in this execution container. Its failure
was reproduced for both the live and reference m_show handlers, and the suite
passed with the same `detect_leaks=0` setting used by the other harnesses.
ASan and UBSan remain enabled; these runs do not establish absence of leaks.

The checks execute extracted production C with explicit host kernel models.
They do not load a module, repeat phone qualification, or constitute an ARM64
kernel build of the proposed upstream patch. Existing RMGU source/module pins
remain authoritative. The patch-check workflow independently repeats the
reproductions and shipping-module regressions.

The connected account has read-only access to the upstream repository and the
available connector has no fork-creation operation. The contribution is supplied
as a ready-to-apply patch here; it has not been opened as an upstream PR.
