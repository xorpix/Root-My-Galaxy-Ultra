# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32704 / 6 | [528bafc5](https://github.com/tiann/KernelSU/commit/528bafc5696196f3a9500cee2e7e18497421cd32) | 3.3.1-24-g528bafc5 |
| KernelSU-Next | 33405 / 6 | [b427a3f9](https://github.com/KernelSU-Next/KernelSU-Next/commit/b427a3f9c2421a443f961007bfce66b25486643f) | 3.4.2-50-gb427a3f9 |
| BakaSU | 35252 / 6 | [d5e9e025](https://github.com/Baka-SU/BakaSU/commit/d5e9e025b8b49155f8014684d64c91742b465f65) | 4.2.0-rc3-81-gd5e9e025 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
