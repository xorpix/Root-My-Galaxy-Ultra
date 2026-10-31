# SusFS automatic selection and HMA OSS

Reviewed 2026-10-31 against RMGU executable source `78c21e889ed9d319a57c5c21ac5c26d0c5bfee35`,
bundled Samsung SusFS source `0c95e284270a1efc10331f218f11f9eed8658d52`, and
[HMA OSS `1fe86ce2b4a6f21197e6e92d15e054d609b7d77c`](https://github.com/frknkrc44/HMA-OSS/tree/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c).

## Result

No direct hook collision was found in the reviewed code. HMA OSS uses Zygisk to
load Android framework hooks in system_server. Its package filtering operates on
PackageManager/AppsFilter results, with additional framework handling for app
data isolation and launches. RMGU's new SusFS feature instead filters the kernel's
`getpriority`, `setpriority`, `ioprio_get` and `ioprio_set` operations when a caller
queries an explicit app UID. It adds no PackageManager, Binder, ART or Zygisk hook.
Neither layer writes the other's configuration or list.

The maintainer completed the test4 phone checks on 31 October 2026 and reported
no HMA failures. Production cleanup removes only temporary diagnostic paths. Other modules require their own review if they modify these syscall
entries, KernelSU policy, credentials or namespaces.

## HMA configuration

Keep RMGU (`dev.experimental.azhlroot`) outside HMA's list of apps **to which
filtering is applied**, or configure that app so it can see all installed managers.
Root managers should also retain the visibility and permissions they need.

This does not prevent putting RMGU and managers in HMA's list of apps **hidden
from other selected apps**. Those are different roles in HMA's configuration.

RMGU deliberately respects the installed-app inventory returned by Android. HMA's
`shouldHide` checks the querying package's scope and can filter that inventory if
RMGU is scoped. In that configuration, an inactive manager hidden from RMGU can
be absent from its optional manager extras. APK inspection recognizes renamed,
split-APK and launcher-hidden managers only after they appear in that inventory;
it does not bypass HMA's package filtering. No HMA settings are changed automatically.

RMGU itself, the backend's registered manager and apps currently granted root
are selected through the live kernel policy. Those decisions do not depend on
the filtered PackageManager inventory. An empty/filtered extras list and a
successful `clear` preserve automatic mode. Confirmed native list failures also
leave the live automatic policy intact. Kernel policy must be available, as
indicated by `ready=1 automatic=1 root_lookup=1` and RMGU's `own_appid`.

The automatic list does not mirror HMA templates. An arbitrary app hidden by HMA
is not automatically a root app; conversely, an HMA visibility exception does not
override SusFS's independent priority policy for an untrusted caller. HMA's own
manager is not a hardcoded manager extra: it is covered by live automatic policy
if granted root. The feature closes a priority-query visibility gap; HMA still
provides its separate package-list filtering.

## Scheduling, services and performance

The production filter passes through root/effective-root callers, Android system
service app IDs (including system_server), trusted KernelSU profiles, queries of
the caller's own app ID, UID zero and non-UID query classes. Ordinary per-process
and per-thread scheduling operations remain on their original kernel path.
HMA's framework and service work does not require changing these exclusions.

Priority queries allocate no memory and enumerate no packages. Live root grants
are read from the existing backend policy; there is no recurring grant polling.
Optional APK discovery runs in a coalesced background job after unlock and outside
the activation lock. This avoids introducing app enumeration into early root or
the syscall path, but does not establish zero overhead or rule out device-specific
performance problems in either module.

Android may defer package broadcasts to a stopped process. HMA also has optional
broadcast filtering. An inactive manager can therefore wait for the next app
resume, unlock or SusFS activation to refresh its extras. Live kernel selection
continues without those broadcasts.

## Validation

The production-C suites cover native/compat queries, caller exclusions, live
root grants/revocations, registered-manager changes, per-user grants, transactional
extras and I/O-priority validation order. Android tests cover automatic package
selection, renamed/split managers, unreadable APKs and boot-local state.
The maintainer accepted the device testing and reported no HMA failures; no
further phone test is required for this release. Fully reboot when installing
1.3.1 so the production module replaces the loaded test module.

## Reviewed HMA sources

- [Zygote entry](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/ZygoteEntry.java)
- [system_server initialization](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/service/SystemServerHook.kt)
- [PackageManager filtering and caller checks](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/hook/PmsHookTargetBase.kt)
- [Android 13+ and Samsung package hooks](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/hook/PmsHookTarget33.kt)
- [HMA scope and visibility decisions](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/service/HMAService.kt)
- [Zygote permissions and data mounts](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/hook/ZygoteHook.kt)
- [App data isolation](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/hook/AppDataIsolationHook.kt)
- [Broadcast filtering](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/hook/BroadcastHook.kt)

## Entry-probe fallback follow-up (test4)

Reviewed native source `98b5149536fdc564132ca82b08d3980114be298a` after the
BZJA phone reported EFAULT during the first priority table write. The optional
layer now tries four kernel entry probes only after the table route fails with
that error and verified cleanup finishes. It requires matching named core
native/compat entries, refuses BTI kernel/call landings and unknown/foreign
entries, retains all caller exclusions, and unregisters/drains every partial
installation. HMA framework/package hooks and both apps' configuration are
unchanged. This introduces no direct overlap with the reviewed HMA hooks.

Actual production-C tests cover native/compat passthrough and filtering,
trusted/system/self callers, live policy changes, validation ordering,
registration failures and callback return-tail teardown. All 56 native host
runner executions pass. These tests do not load HMA or prove on-phone
coexistence by themselves. The maintainer subsequently accepted phone testing
and reported no HMA failures. Other kernel modules that
redirect these syscall entries can still affect coverage and require their
own review.

