# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32716 / 6 | [28db74f6](https://github.com/tiann/KernelSU/commit/28db74f6f4692e97006ce9ebd8b19b9cc614b5b7) | 3.3.1-36-g28db74f6 |
| KernelSU-Next | 33455 / 6 | [a6c81fa4](https://github.com/KernelSU-Next/KernelSU-Next/commit/a6c81fa4a8b7341d0bc5c3e4507531459df871ae) | 3.4.2-100-ga6c81fa4 |
| BakaSU | 35257 / 6 | [516d3e87](https://github.com/Baka-SU/BakaSU/commit/516d3e87fbc986ccefdde2c2765f1700612441a9) | 4.2.0-rc3-86-g516d3e87 |
| KowSU | 32740 / 6 | [6ecdd692](https://github.com/KOWX712/KernelSU/commit/6ecdd69224fe6769e6c8715a4ef2b91602b23362) | 3.3.0-139-g6ecdd692 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
