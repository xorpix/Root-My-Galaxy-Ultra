# SusFS module-status lock fix

Reading the module-status parameter previously took the module spinlock before the configuration mutex. A writer takes those locks in the opposite order. That could sleep while holding a spinlock or deadlock with a concurrent writer.

The only production C change moves the existing configuration mutex outside the spinlock in `hide_modules_format()`. Both readers and writers now acquire the configuration mutex first. No locking primitives, imports, module parameters, status fields or logging were added. Android application code and all module selection and visibility behavior match stable 1.3.5.

The reviewed source is reconstructed from native commit `08cff8220c7db9e37fa378a5757d950e6bdb0d1a`, using the patch and raw commit record in this directory. The build recipe verifies the source trees, baseline artifact hashes, unchanged imports and ABI, then updates the binary and its provenance together. Build evidence stays in CI artifacts.

The regression exercises concurrent status reads and configuration writes against the actual production functions. Its instrumented lock mock rejects sleeping mutex acquisition under the spinlock. A deliberate restoration of the original lock order must fail. Existing module-controller and full host regressions also run. These mocks and regression runners are not compiled into the module or APK.

## Phone validation

On 2026-11-09 the maintainer confirmed the supplied signed `lock-fix-test1` candidate works and authorized publication. The exact candidate APK, module and source hashes, confirmation and scope are recorded in [phone-validation.json](phone-validation.json). The release uses the same module bytes. No separate detector output or long-uptime measurements were supplied.

Install the production hotfix over the existing app, fully reboot the phone, then activate root/SusFS as usual. Toggling SusFS alone cannot replace a module already loaded in the kernel.
