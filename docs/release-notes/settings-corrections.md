# Settings corrections — proposed release notes

- Settings now follow the active firmware/root method. AZHL keeps its applicable manual delay and run-limit controls; DirtyFrag uses its own settings presentation.
- Run plan shows the selected backend, bundled payload source and actual transport. Removed obsolete cache, environment and tuning details.
- Manual profile selection stays within the selected backend.
- Clarified mandatory backend loading and AZHL's Shizuku requirement. Removed the unsupported “run without Shizuku” action from its setup dialog.
- Manual delay choices reflect the existing minimum, and the setting displays the value read back from storage.
- Reset warnings describe the scope for each root method and explain that the reset preference stays enabled until turned off.
- Removed the unavailable Reload modules action from settings on all firmware.
- SusFS status distinguishes automatic activation being off from the current module remaining active until reboot. Existing activation preferences can still be turned off after an incompatible firmware update.
- Clarified the boot-delay description and optional wireless debugging use on DirtyFrag.

Validation: isolated presentation tests and default-resource checks. Android build and device validation remain pending. These notes do not claim a published APK or a completed release.
