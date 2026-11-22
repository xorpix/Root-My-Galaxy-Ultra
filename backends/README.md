# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32735 / 6 | [2a60b289](https://github.com/tiann/KernelSU/commit/2a60b2893b8d0d885d1ceff2287d8ad854f0e0f4) | 3.3.1-55-g2a60b289 |
| KernelSU-Next | 33646 / 6 | [896f45a7](https://github.com/KernelSU-Next/KernelSU-Next/commit/896f45a78c0806cfd585c4e14e6989f867b143d7) | 3.4.2-291-g896f45a7 |
| BakaSU | 35281 / 6 | [2734e0bc](https://github.com/Baka-SU/BakaSU/commit/2734e0bc155759955880f5fb99be640fc5b92156) | 4.2.0-rc3-110-g2734e0bc |
| KowSU | 32766 / 6 | [44758e19](https://github.com/KOWX712/KernelSU/commit/44758e199257bb973aa6ca5b3a393c1b21694408) | 3.3.0-165-g44758e19 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
