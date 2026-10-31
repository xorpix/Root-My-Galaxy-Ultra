# KoW history transition review

The daily compatibility check and weekly build stopped after KoW rebased its
official `master` branch. A normal clone no longer contains RMGU's built pin.
The previous commit remains fetchable by its exact SHA for source verification.

| Item | Built baseline | Reviewed replacement |
|---|---|---|
| Commit | `6366f25b89f2bc5e287c1983715ebae60cd5540e` | `6ecdd69224fe6769e6c8715a4ef2b91602b23362` |
| Git tree | `0f4ee6063696739480ec7e78357ceb718f00ba7a` | `2b756d1c09a33e9df3eaa4dd4b99aca9bf6ff5cf` |
| Commit count | 2737 | 2740 |
| Driver version | 32737 | 32740 |
| Daemon description | `3.3.0-136-g6366f25b` | `3.3.0-139-g6ecdd692` |
| UAPI | 6 | 6 |

## Source review

Compared all old/new paths and ran `git range-diff` from the shared ancestor
`75e179d378d7aaa0de8946358a3384444c3e36b0`. Thirty-two old commits become
35 commits. Most fork commits are replayed unchanged; the tree delta is 11 files.

The only kernel runtime delta is `kernel/feature/selinux_hide.c`, incorporating
the upstream SELinux parser repair. It parses user/role/type once for both
policies, restores the MLS suffix before parsing the live policy, validates each
context against its own policy, and synchronizes only valid contexts. Context
cleanup and a live SID-table reload retry replace the old invalid/uninitialized
result handling. The Samsung compatibility patch applies directly to this tree.

The complete `uapi/` and `userspace/` trees, and `kernel/Kbuild`, are unchanged.
In particular, the GET_INFO layout/ioctl and UAPI 6 contract are unchanged, as
are both version formulas. The remaining delta affects the separate manager's
WebUI/package APIs and view model, a manager build filename, and documentation;
those manager/JS sources are not bundled in RMGU's driver/daemon pair. Manager
download selection retains its independent APK/UAPI verification.

| File | SHA-256 (identical in both revisions) |
|---|---|
| `uapi/supercall.h` | `5096fcb9e5ba303e6805f2d4afc808194913c75a562fb6ab94df18a3ed4ed2ea` |
| `kernel/Kbuild` | `292bd20fb9e168f6c144a5069a2962ebdb4ce5dd2e1656dc6f6063639b9cb54c` |
| `userspace/ksud/build.rs` | `cfa2d3bde101b72a604685170aec1e67d7e48b8e022010da5b4359a1c0e6b64a` |

## Automation behavior

The recorded bridge is scoped to backend `kowsu`, the exact previous built pin,
and the reviewed replacement anchor. A future head is accepted only when it is
that anchor or a Git-proven descendant. Thus a normal update arriving before
the scheduled build can proceed. Another unrelated rebase still requires review,
even if its source tree is identical. Existing reviewed exceptions for other
backends remain limited to their exact revision pairs.

The old commit is fetched only for an approved transition. Missing source,
Git lookup errors, version rollback, unreviewed UAPI changes, patch conflicts and
native build failures still stop the refresh. Source pins and bundled binaries
are advanced together by the existing staging process after a matched build.
This maintenance change alone does not claim newer binaries or phone validation.

Regression fixtures use real temporary Git repositories with an unadvertised
old pin, a replacement, its later commit, and an unrelated identical-tree
replacement. They exercise retrieval, normal progress, backend/pin boundaries,
missing anchors, Git errors and rejection of subsequent rewrites. The live
discovery check also reviews all currently changed official backend heads.

Local validation passes: 32 refresh/history cases, seven patching cases, two
backend-bundle cases and eight M3Q bundle cases. Live discovery accepts these
source pins with UAPI 6 and direct Samsung patch application for every backend:

| Backend | Snapshot commit | Driver version |
|---|---|---|
| KernelSU | `51c217863c5e1fb639501e92ef17ac6d0cf90c92` | 32712 |
| KernelSU-Next | `8979126b66aa8cd503071ffb98df8ec5c7a78742` | 33447 |
| BakaSU | `5785bdb8091304322f597f67d50e0b79f9815cb2` | 35253 |
| KoW | `6ecdd69224fe6769e6c8715a4ef2b91602b23362` | 32740 |

The release-note generator and current before-unlock guide no longer describe
SusFS as experimental, consistent with the confirmed 1.3.0 app settings.
Historical release notes retain their original descriptions.
