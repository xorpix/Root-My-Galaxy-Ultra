# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32665 / 5 | [e7b07110](https://github.com/tiann/KernelSU/commit/e7b071100754b55e28be6930f9967d99adb4a385) | 3.3.0-64-ge7b07110 |
| KernelSU-Next | 33323 / 5 | [3daa5787](https://github.com/KernelSU-Next/KernelSU-Next/commit/3daa57876983f64755b85adaf8acb69c2b09af3a) | 3.4.0-29-g3daa5787 |
| BakaSU | 35217 / 5 | [c9246641](https://github.com/Baka-SU/BakaSU/commit/c9246641b9eee8b9986050e8bf1832f88da880c2) | 4.2.0-rc3-46-gc9246641 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a UAPI 5 manager. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
