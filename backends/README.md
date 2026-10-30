# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32708 / 6 | [bb5f9faa](https://github.com/tiann/KernelSU/commit/bb5f9faaa846b7beb833d3f5303cff7ac2aaedcf) | 3.3.1-28-gbb5f9faa |
| KernelSU-Next | 33445 / 6 | [31946acd](https://github.com/KernelSU-Next/KernelSU-Next/commit/31946acd2f529b2b24e5e4f0267cfacb5d13498c) | 3.4.2-90-g31946acd |
| BakaSU | 35252 / 6 | [d5e9e025](https://github.com/Baka-SU/BakaSU/commit/d5e9e025b8b49155f8014684d64c91742b465f65) | 4.2.0-rc3-81-gd5e9e025 |
| KowSU | 32737 / 6 | [6366f25b](https://github.com/KOWX712/KernelSU/commit/6366f25b89f2bc5e287c1983715ebae60cd5540e) | 3.3.0-136-g6366f25b |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
