# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32730 / 6 | [64833c00](https://github.com/tiann/KernelSU/commit/64833c005eca6a8dc3d73ec2d37d84c37e0d9ad2) | 3.3.1-50-g64833c00 |
| KernelSU-Next | 33553 / 6 | [7ccf6147](https://github.com/KernelSU-Next/KernelSU-Next/commit/7ccf61479621f240c0d968ef46f1b167b6ceb500) | 3.4.2-198-g7ccf6147 |
| BakaSU | 35261 / 6 | [5d6d1843](https://github.com/Baka-SU/BakaSU/commit/5d6d1843c8937f5ff2a145dc89a0cf46030ae6f8) | 4.2.0-rc3-90-g5d6d1843 |
| KowSU | 32761 / 6 | [d4873310](https://github.com/KOWX712/KernelSU/commit/d4873310a2738c4c5726a804ed58f1b473799a92) | 3.3.0-160-gd4873310 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
