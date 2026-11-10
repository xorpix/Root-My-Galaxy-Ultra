# Protected-table redirect fallback candidate

The maintainer tested the signed startup diagnostic on SM-S948B / BZJA with
BakaSU ksud 4.2.0-rc3-88-g548d1dda, UAPI 6. Root succeeded. SusFS reported all
required functions resolved (`symbols_checked=1 missing=none`), but all five
open entry installations returned `-14` (`EFAULT`) and zero entries armed.
The failure propagates from the syscall-table write path. It is consistent
with the protected-table behavior already handled by this fork's priority
filter and supercall fallback; no attempt is made to override that protection.

This candidate stops table installation at the first EFAULT, restores and
drains every attempted table hook, then uses entry probes to reach the same
normal-context open wrappers. The probe handler only checks routing, takes a
lifetime reference and changes the saved PC. Path lookup, file opening and
descriptor installation execute after exception return, outside the handler.
The five logical entries use four probes when native and compat openat2 share
their actual entry. ABI selection remains explicit.

Every target must still be the expected core-kernel function start in the
actual syscall table. BTI-enabled builds, explicit BTI entries, existing
breakpoints, foreign/interior targets, ambiguous same-ABI aliases and changed
table slots are refused. The exact registered address is checked. An empty
post-handler prevents optimizations that would ignore PC changes. The design
uses the documented kprobe execution-path mechanism:
https://docs.kernel.org/trace/kprobes.html#changing-execution-path

Calls from the wrapper to the saved original carry a stack-owned marker,
keyed by task, ABI operation and register-frame pointer. Short bucket locks
protect lookup and removal; no lock is held across an original syscall. This
requires no allocation, syscall-number modification, global bypass flag or
CPU-local state, and tolerates sleeping originals and altered return addresses.
References acquired in the handler cover the gap before the adapter starts;
shutdown unregisters the probes and drains these references and return tails
before the module text can be released. With no redirect rules the handler
passes through without entering a wrapper or taking a bypass-bucket lock.

No root backend, firmware profile, setting, required feature gate or redirect
rule semantics change. The temporary startup receipt from the diagnostic is
retained for phone validation; it now also shows the selected route and probe
result. Successful fallback reports five armed logical entries, `init_rc=0`,
`route=kprobe` and `probe_rc=0`. An EFAULT on the original table attempt remains
visible; unattempted table entries retain `-125`. This is an honest record of
which route armed, not suppression of the failed write. The temporary receipt
and app reader should be removed after phone confirmation, before stable use.

The source is reconstructed from the exact native source shipped in 1.3.7 plus
the recorded patch, with commit/tree and source archive digests. Host tests
exercise actual wrappers and fallback code with fault-injected kernel APIs:
all five native/compat routes, shared openat2, normal/error/PASS results,
recursion isolation, pending wrapper entry during teardown, each registration
failure, invalid/changed targets and safe refusal. Mutation controls reject
lost lifetime references, incorrect ABI selection and lost recursion guards.
Existing host/sanitizer regressions and Android tests run alongside a native
cross-build. The ABI check covers all 27 syscall-shaped entry functions,
including the five adapters, both new probe callbacks, unchanged imports and
all existing callback types. Build and model results are not a phone test.

## Phone validation

Install `RootMyGalaxyUltra-redirect-fix-test1.apk` over the current app, fully
reboot, then activate root and SusFS once. Copy the full app result. If needed,
the route can also be inspected read-only:

```sh
su -c 'cat /sys/module/susfs_guard_lkm/parameters/or_entry_stat'
```

Do not load another module in a boot with an existing partial module. No
stable publication or successful phone result is claimed by this candidate.
