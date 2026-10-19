# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32699 / 6 | [b609757f](https://github.com/tiann/KernelSU/commit/b609757fad4441b88e73966c5dc12e4788be9d3b) | 3.3.1-19-gb609757f |
| KernelSU-Next | 33403 / 6 | [d65c9db1](https://github.com/KernelSU-Next/KernelSU-Next/commit/d65c9db1b07edcdc3ad531ad2108fc66227e502c) | 3.4.2-48-gd65c9db1 |
| BakaSU | 35234 / 5 | [896e8981](https://github.com/Baka-SU/BakaSU/commit/896e8981e0ed76ec14d1dcb39ab793438b304886) | 4.2.0-rc3-63-g896e8981 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
