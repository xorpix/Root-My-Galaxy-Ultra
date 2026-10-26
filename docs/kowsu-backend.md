# KowSU backend

KowSU is a fourth independent backend, using `KOWX712/KernelSU` on `master`.
The initial pin is `6366f25b89f2bc5e287c1983715ebae60cd5540e`: driver 32737,
UAPI 6, daemon `3.3.0-136-g6366f25b`. Its manager package is
`com.kowx712.supermanager`. It requires KowSU's own manager signer; installing
the standard KernelSU manager does not make it KowSU's manager.

The driver and daemon are built together using RMGU's pinned Android 16 / 6.12
DDK, NDK r28c and Rust 1.98.1. The Samsung compatibility changes are adapted
from RMGU's KernelSU patch. KowSU's upstream UAPI headers, version formula and
manager signature remain unchanged. `backends/kowsu/build-manifest.json`
records the exact built source, compatibility patch and output hashes.

The app includes KowSU in the backend selector, manager identification,
AZHL/M3Q bundle and both recorded DirtyFrag profiles. Additional eligible S26
Ultra regional profiles inherit the same KowSU daemon. Selection still requires
a full reboot when another backend is already loaded.

M3Q uses a generated helper with the exact KowSU driver version and UAPI 6.
Its fork path omits the standard KernelSU manager override, allowing KowSU's
compiled package default. DirtyFrag uses an explicit KowSU bridge derived from
the verified KernelSU bridge. `tools/prepare_df_backends.py` checks the source
hash and removes that same package override only in non-executable string data;
it preserves the bridge's instructions and command handling. The generator
and `backends/kowsu/dirtyfrag-bridge.json` make this edit reproducible.

Weekly builds and daily compatibility checks include KowSU's `master` branch.
The manager downloader uses its `build-manager.yml` workflow and `manager`
artifact. Refresh staging and release verification change versions by backend
identity, so overlapping KernelSU/KowSU version numbers cannot rewrite each
other. KowSU keeps `me.weishu.kernelsu` as its Kotlin/JNI namespace despite
its different application id; UAPI verification uses that namespace.

The bundled SusFS implementation and its sources are unchanged. Build and
bundle checks do not establish on-device compatibility. From a full reboot,
select KowSU, activate root, verify it in the matching manager, and check root,
modules, SusFS and recovery actions before treating it as hardware-validated.
