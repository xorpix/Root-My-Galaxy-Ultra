# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32734 / 6 | [ff70c90d](https://github.com/tiann/KernelSU/commit/ff70c90d6de46b80b79d0c74d360d85611797b27) | 3.3.1-54-gff70c90d |
| KernelSU-Next | 33554 / 6 | [aa235b99](https://github.com/KernelSU-Next/KernelSU-Next/commit/aa235b99574b5b349cdb4980bcb98fd58e6abb28) | 3.4.2-199-gaa235b99 |
| BakaSU | 35262 / 6 | [7b65bde0](https://github.com/Baka-SU/BakaSU/commit/7b65bde0710b5e7c6f755545cb15a4abd417f3a8) | 4.2.0-rc3-91-g7b65bde0 |
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
