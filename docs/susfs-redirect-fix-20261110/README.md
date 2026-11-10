# Samsung Open Redirect startup hotfix

Root could succeed while SusFS failed to start because all five Open Redirect
syscall-table writes returned EFAULT (-14). The required kernel functions were
successfully resolved. The signed fallback candidate was then confirmed working
by the maintainer: all five logical entries armed, init returned zero and the
entry-probe route started successfully. See `phone-validation.json` for the exact
candidate identity and the limits of that evidence. The empty rule table and zero
hit count do not constitute a redirect-rule execution test.

When the first table write returns EFAULT, the production code stops that route,
restores and drains every attempted table hook, then installs validated entry
probes. The handler only checks routing, acquires a callback lifetime reference
and changes the saved PC. Path lookup, file opening and descriptor installation
continue through the existing normal-context wrappers after exception return.
Native and compat openat2 share a probe when they share the actual kernel entry.

Targets must be actual expected core-kernel function starts in the syscall tables.
BTI-enabled builds and entries, existing breakpoints, foreign/interior targets,
ambiguous same-ABI aliases, adjusted registrations and changed table slots are
refused. An empty post-handler prevents optimizations that ignore PC changes.
The mechanism follows the kernel's documented kprobe execution-path interface:
https://docs.kernel.org/trace/kprobes.html#changing-execution-path

Original calls carry stack-owned markers keyed by task, ABI operation and register
frame. Short bucket locks cover lookup and removal without spanning an original
syscall. Handler-acquired references cover the entry-to-adapter gap; shutdown
unregisters probes and drains every wrapper and return tail before freeing module
text. With no redirect rules, the handler passes through without entering a
wrapper or taking the bypass-bucket lock.

The production source removes the temporary startup receipt variables, formatter
and app reader. The pre-existing one-line entry status remains accurate for either
route. No new runtime logging, settings, backend or firmware changes are included.
Historical diagnostic APKs remain reproducible from their original Git commits;
the diagnostic-only tool and patch are removed from the current tree.

Host tests exercise the actual wrappers and fallback with fault-injected APIs:
all five ABI routes, shared openat2, normal/error/PASS results, recursion isolation,
entry gaps during teardown, registration failures and invalid targets. Startup
regressions cover every required symbol, refusal behavior, and a table-write fault
at each of the five installation positions. Mutation controls catch missing
cleanup, incorrect readiness, a lost first-fault stop, ABI errors and lifetime
reference loss. All supported sanitizer runs, native compilation, 27 syscall
entry and callback ABI checks, unchanged imports, Android tests, payload checks
and signing checks are required before publication.

The production binary differs from the phone-confirmed candidate only by removal
of temporary diagnostic code/state. Its binary hash and qualification evidence
are recorded separately; no separate phone test of the stripped binary is claimed.
