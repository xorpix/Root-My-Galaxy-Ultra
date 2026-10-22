# Localization review

Resource coverage and successful Android compilation establish that text can be loaded and formatted. They do not establish that a translation is accurate or natural. Never report a language as linguistically reviewed from the output of `tools/check_translations.py` alone.

Read each English message together with its caller and surrounding controls. Translate its meaning directly, preserve its conditions and instructions, and then read the localized sentence on its own. A translation model's draft is not a reviewed catalog. Do not use back-translation or isolated word substitutions as a substitute for reviewing a message.

## Context that changes the translation

- **Logs** are diagnostic records, not wood or logos. **Staging** means preparing temporary files, not theatre. **Run** means an execution, not a race or flight.
- **Override the payload** changes three configuration parameters. It neither deletes nor disables the payload. A fresh-session profile still gets one attempt regardless of the override.
- **Current**, **left**, **auto** and **legacy** describe a revision, a remaining item, automatic selection and an older method. They are not electricity, a direction, a car or inheritance.
- **Download Mode** is a named Samsung boot mode. It does not mean starting a download. Preserve the distinction between a full device reboot, a userspace restart and restarting Zygote.
- `tracefs`, `p0`, `boot`, `vbmeta`, paths, commands and permission identifiers are technical names. Do not translate their bytes. `run_limits_route_tracefs` inherits its nontranslatable English resource.
- Preserve qualifiers such as *may*, *only*, *until reboot* and *not confirmed*. Do not turn an attempted operation into a verified success.
- Check every clause of a destructive-action warning, including what is deleted, what is preserved, when it takes effect and how to disable it afterwards.

## Russian terminology

| English context | Russian |
| --- | --- |
| root access | root-доступ |
| payload | пейлоад |
| run | запуск |
| shell | оболочка |
| daemon | демон |
| KernelSU backend / flavour | вариант KernelSU |
| boot, the system starting | загрузка системы |
| boot, the partition | `boot` |
| log | журнал |
| soft reboot | программная перезагрузка |
| userspace | пользовательское окружение |

Russian count messages must work for 1, 2, 5, 11, 21, 22 and 25. The `one` category also covers 21; it must not hardcode “one”. Confirm that the caller supplies the count to every form before adding a formatter argument. The active callers for run counts, line counts and deletion notices supply that argument. The older delete-confirmation resources currently have no callers; any future caller must also supply the count. The confirmation body can use count-neutral wording about the selected data.

## Scope of the October 22 repair

The complete Russian catalog was authored directly against the English source and app context. The other ten locales received contextual rewrites for navigation, temporary files, pairing, cached payloads, execution status, version selection, reboot destinations, limits and the warnings found to have omitted or reversed meaning. This scope is distinct from the automated coverage result; it is not a claim of independent native-speaker certification for every language.

After reviewing text, run `python3 tools/test_translations.py`, `python3 tools/check_translations.py` and Android resource compilation. The translation workflow also runs the app unit tests.
