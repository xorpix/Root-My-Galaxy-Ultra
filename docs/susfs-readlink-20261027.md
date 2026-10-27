# Samsung SusFS readlink LSM fix

The bundled Samsung module now uses source
`870bf952aab096789a0f4153bc5ea5bf6fcfba1e`, a focused port of upstream
[`b69dee4`](https://github.com/inforcqb/susfs4ksu-lkm/commit/b69dee412aac54cdf99832412de3b4758118a1eb)
onto the previous Samsung source `b5c994d`.

The existing Samsung syscall wrapper already checks hidden readlink targets
before SELinux. It is retained. The additional `inode_readlink` LSM hook gives
the same ENOENT answer to direct security-hook callers, before a SELinux
EACCES could reveal the hidden target. Normal SELinux decisions, Samsung
caller/owner/identity gates and callback lifetime tracking are preserved.
The hook participates in mandatory registration rollback and unload.

The module's proc/sysfs path listing adds `readlink: lsm_enoent=N`. It counts
only denials from the new LSM hook; earlier syscall-wrapper denials do not
increment it. The userspace loader, status helper and control ABI are unchanged.

## Validation

- Android16/6.12 DDK build with clang-r536225 and CFI enabled passed.
- All 24 native host runners passed in plain and ASan/UBSan modes (48 runs).
  The new LSM runner covers 42 cases and three runtime negative controls per
  mode, including SELinux pass-through errors and a sleeping original call.
- All 13 real-kernel-header callback type checks passed, including wrong
  readlink return and const-pointer rejection.
- Stripped module size: 546,152 bytes, up from 545,232. All 136 undefined
  kernel imports match the previous module; none were added or removed.
- Both BZIG and BZID profiles pin the rebuilt module's SHA-256:
  `3374a5b721f2abdce2f5b993cd424e6c25e30f02c57215a0ce9c455c560a1fa8`.
- This revision has automated validation; phone validation remains pending.

The integration workflow uses JDK 25 and NDK r28c and verifies every backend
from the shared registry, including KowSU. No stable release is published by
that workflow.

After installing an APK containing this update, fully reboot before loading
the new SusFS module. A soft reboot retains the previously loaded module.
