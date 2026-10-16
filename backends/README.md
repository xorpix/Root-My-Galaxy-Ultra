# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32692 / 6 | [0faec0cc](https://github.com/tiann/KernelSU/commit/0faec0cc7b5f655170d89bf9c4d28bb5ca271fa2) | 3.3.1-12-g0faec0cc |
| KernelSU-Next | 33390 / 5 | [204f0071](https://github.com/KernelSU-Next/KernelSU-Next/commit/204f007164905395e280e9290cc7774f57af8c61) | 3.4.2-35-g204f0071 |
| BakaSU | 35229 / 5 | [d7260ca1](https://github.com/Baka-SU/BakaSU/commit/d7260ca1e5c128061122924d0d48754e361e135c) | 4.2.0-rc3-58-gd7260ca1 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
