# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32702 / 6 | [6d31a613](https://github.com/tiann/KernelSU/commit/6d31a61370838380177dc5c46fb838f6ba7d639a) | 3.3.1-22-g6d31a613 |
| KernelSU-Next | 33405 / 6 | [b427a3f9](https://github.com/KernelSU-Next/KernelSU-Next/commit/b427a3f9c2421a443f961007bfce66b25486643f) | 3.4.2-50-gb427a3f9 |
| BakaSU | 35236 / 5 | [dac2d186](https://github.com/Baka-SU/BakaSU/commit/dac2d186406a0d2b0ce3c67cad5a1fa1adbf9f4d) | 4.2.0-rc3-65-gdac2d186 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
