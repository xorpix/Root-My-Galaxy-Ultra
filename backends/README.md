# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32692 / 6 | [0faec0cc](https://github.com/tiann/KernelSU/commit/0faec0cc7b5f655170d89bf9c4d28bb5ca271fa2) | 3.3.1-12-g0faec0cc |
| KernelSU-Next | 33396 / 6 | [b28fce51](https://github.com/KernelSU-Next/KernelSU-Next/commit/b28fce510568ba0b7584b38492cabfaaeb61ac37) | 3.4.2-41-gb28fce51 |
| BakaSU | 35231 / 5 | [2489b385](https://github.com/Baka-SU/BakaSU/commit/2489b38582a08fee7b586d05185da5e5d12c6a5c) | 4.2.0-rc3-60-g2489b385 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
