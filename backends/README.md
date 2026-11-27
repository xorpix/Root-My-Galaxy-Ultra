# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32737 / 6 | [eeb3bbf8](https://github.com/tiann/KernelSU/commit/eeb3bbf8359434f5596b8a7875b54ac4e5fc5495) | 3.3.1-57-geeb3bbf8 |
| KernelSU-Next | 33686 / 6 | [2baabd60](https://github.com/KernelSU-Next/KernelSU-Next/commit/2baabd6090291849f52790fc5c8d24221417d761) | 3.4.2-331-g2baabd60 |
| BakaSU | 35286 / 6 | [cac1e793](https://github.com/Baka-SU/BakaSU/commit/cac1e79355ef451539f5c5ba9dc2bda1c64069de) | 4.2.0-rc3-115-gcac1e793 |
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
