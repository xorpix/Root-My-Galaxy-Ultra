# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32673 / 5 | [6e386044](https://github.com/tiann/KernelSU/commit/6e3860443f8fea7c5fc226277e53888033796488) | 3.3.0-72-g6e386044 |
| KernelSU-Next | 33337 / 5 | [cd4a6b46](https://github.com/KernelSU-Next/KernelSU-Next/commit/cd4a6b468653fb9640caaf203c05e07609ba5da1) | 3.4.1-4-gcd4a6b46 |
| BakaSU | 35223 / 5 | [48fa4bb7](https://github.com/Baka-SU/BakaSU/commit/48fa4bb7ec8bddb8ea932a8c9be0877f89a1fa73) | 4.2.0-rc3-52-g48fa4bb7 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a UAPI 5 manager. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
