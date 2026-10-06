# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32661 / 5 | [08b2e9e4](https://github.com/tiann/KernelSU/commit/08b2e9e451325ebe506c273cfb0fde17d18f592f) | 3.3.0-60-g08b2e9e4 |
| KernelSU-Next | 33319 / 5 | [9ba1a51e](https://github.com/KernelSU-Next/KernelSU-Next/commit/9ba1a51e46d0e4a88ba502a80eda1351a6ce1cd8) | 3.4.0-25-g9ba1a51e |
| BakaSU | 35212 / 5 | [e5423590](https://github.com/Baka-SU/BakaSU/commit/e5423590bec3e24daffa4e9555c9592071319c68) | 4.2.0-rc3-41-ge5423590 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a UAPI 5 manager. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
