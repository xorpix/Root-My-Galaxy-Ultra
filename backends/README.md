# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32692 / 6 | [0faec0cc](https://github.com/tiann/KernelSU/commit/0faec0cc7b5f655170d89bf9c4d28bb5ca271fa2) | 3.3.1-12-g0faec0cc |
| KernelSU-Next | 33391 / 6 | [16be0f72](https://github.com/KernelSU-Next/KernelSU-Next/commit/16be0f72041985de893bcea4e3de7ee1bc32eb2d) | 3.4.2-36-g16be0f72 |
| BakaSU | 35230 / 5 | [b8754b20](https://github.com/Baka-SU/BakaSU/commit/b8754b203edfc03342d0f58adfc582719b31363c) | 4.2.0-rc3-59-gb8754b20 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
