# NoMount module hiding with installer compatibility

RMGU adds the exact module name `nomount` to its existing `kernelsu,susfs_guard_lkm`
load parameter. The native matcher preserves `nomount` rows for effective UID 0,
while filtering configured NoMount rows from non-root `/proc/modules` and
module-owned `/proc/kallsyms` output. KernelSU and SusFS retain their existing
policy for all readers. Ordinary vendor drivers and similarly prefixed names
are unaffected.

The existing self-protected path rule also hides `/sys/module/nomount` from
non-root callers when that directory exists at configuration or rescan time.
Root keeps control access. This is trace filtering; it does not unload NoMount,
change its mounts, improve performance, or add support for another firmware.

## Why root must keep the NoMount row

Classic [NoMount v2.2.0's installer](https://github.com/maxsteeel/nomount/blob/bf1847f98fed382369c43bfd652cd93774b9b8e3/module/customize.sh#L74-L96)
first probes the API, then uses `/proc/modules` to distinguish an LKM from
built-in support. If the API responds but the `nomount` row is absent, it
classifies the driver as built-in and deletes the staged LKM directory. Adding
NoMount to the previous all-reader filter would therefore damage upgrades and
could leave the next boot without its driver.

Its [boot loader](https://github.com/maxsteeel/nomount/blob/bf1847f98fed382369c43bfd652cd93774b9b8e3/module/common.sh)
uses an API probe. NoMount Suite's newer
[loader](https://github.com/Bouteillepleine/NoMount-Suite/blob/7fcda72550022662565db310d5850f1fe785fe83/module/lkm-load.sh)
also uses an engine probe, and its
[installer](https://github.com/Bouteillepleine/NoMount-Suite/blob/7fcda72550022662565db310d5850f1fe785fe83/module/customize.sh#L135-L140)
documents the same earlier misclassification. Suite builds that unlink their
own module row continue to control that behavior themselves. The installed
NoMount variant on the maintainer's phone has not been independently identified.

## Source and checks

The accepted baseline is app commit `c68fab0cdd3597f5552481cf610fa36b546969e4`,
native commit `2be2a77433418e0c3688a649dca160dc414d325b`, and module SHA-256
`482efab75ab8b682bfdf22332c2f7cf7c70af05ceee2ad41ca3aa1bdcfb048b7`.
The candidate native commit is `08cff8220c7db9e37fa378a5757d950e6bdb0d1a`,
tree `9a8961abf991432fbe14730e205037d479ac8c34`.
`native.commit` and `native.patch` allow exact reconstruction with
`tools/build_susfs_nomount_hiding.py`. The builder verifies both Git trees,
the parent commit, baseline digests, and the rebuilt archive/module bytes.

The production-extracted tests cover both proc callbacks, real/effective UID
differences, exact-name matching, unconfigured NoMount, unchanged KSU/SusFS
hiding, unrelated vendor modules, and the independent symbol-prefix policy.
Negative controls reject a missing root exception and a mistaken real-UID
check. The local host suite passed 66/66 runner executions. CI repeats the
suite from the reconstructed archive, compiles against the pinned Android
16/6.12 DDK, and checks imports, CFI, BTI, module metadata and syscall wrappers.
Android CI tests the app, verifies packaged provenance, and checks the signing
certificate before producing the test APK.

## Phone validation

The maintainer's phone check passed on 2026-11-08. At app UID 10362,
`/sys/module/nomount` was absent while `/sys/module/zram` stayed visible. At
root UID 0, the NoMount directory and module row were visible, and KernelSU/
SusFS rows were absent. The maintainer also confirmed NoMount still works.
The non-root `/proc/modules` read returned `Permission denied`, so that
specific proc-filter check is inconclusive. [The scoped receipt](phone-validation.json)
records the results and limitations; kallsyms, actual installer upgrades and
long-uptime behavior were not separately tested on the phone.

The receipt identifies the supplied signed test APK and its bundled native
hash; installed APK/module hashes and firmware were not independently read
from the phone. No implementation or native binary changed after the test.

To repeat the check, install the signed test APK, perform a full reboot,
activate root, and enable SusFS. A framework restart does not replace the
loaded kernel module.

Confirm NoMount still works and that its root installer can detect the LKM:

```sh
su -c 'grep -E "^(nomount|kernelsu|susfs_guard_lkm) " /proc/modules'
```

For classic NoMount loaded as an LKM, `nomount` should remain; KernelSU and
SusFS should be absent. From an ordinary non-root shell, confirm `id` reports
a nonzero UID, then check `/proc/modules` and `/sys/module/nomount` if Android
permits those reads. A denied read alone does not prove SusFS filtering. The
root view is deliberately insufficient to test the new non-root policy.
If NoMount loads after SusFS configuration, proc filtering still applies, but
the existing sysfs controller needs a rescan after that directory appears.
Do not apply an `add nomount` command to the older native binary: it lacks
the installer compatibility exception.
