# Bundled backends

| Backend | Driver / UAPI | Source | Daemon |
|---|---|---|---|
| KernelSU | 32725 / 6 | [02ffbd44](https://github.com/tiann/KernelSU/commit/02ffbd447eb6a2f05d9f46e17b1e1c3bcfdd9971) | 3.3.1-45-g02ffbd44 |
| KernelSU-Next | 33501 / 6 | [b9087642](https://github.com/KernelSU-Next/KernelSU-Next/commit/b90876426197f5d35c3003bf42d8bf927dc20181) | 3.4.2-146-gb9087642 |
| BakaSU | 35259 / 6 | [548d1dda](https://github.com/Baka-SU/BakaSU/commit/548d1dda060ca198823d8808804d01f5209ef4a9) | 4.2.0-rc3-88-g548d1dda |
| KowSU | 32750 / 6 | [21787ce1](https://github.com/KOWX712/KernelSU/commit/21787ce135c988bae4244a44cfcb5a542aa3347f) | 3.3.0-149-g21787ce1 |

Each folder contains the exact driver/daemon hashes and the complete Samsung
compatibility patch for that revision. Keep these records in git: they are used
by the weekly build and by `tools/test_backend_bundle.py`.

Use a manager matching the UAPI shown for its backend. BakaSU retains the internal `resukisu` id.
After installing a new APK, fully reboot and activate root to load its driver.
A manager APK update alone does not replace the running driver.

Build and bundle validation is not phone testing. Firmware support, exploit
algorithms, and Samsung compatibility behavior are not changed by this pipeline.
See [the build workflow](../tools/backend-refresh/README.md).
