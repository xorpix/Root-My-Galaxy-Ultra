# Redirect startup diagnostic candidate

RMGU 1.3.7 reports a live SusFS module without OPEN_REDIRECT. The maintainer's
read-only status shows `or_entry=Y`, `armed=0 entries=0/5 hits=0`, no rules and
no reverse hooks. The eight required kernel function names are visible in
`/proc/kallsyms`; their printed addresses are zero. This does not establish
whether internal function resolution succeeded. The reported uname release
may be spoofed and is not treated as a new supported firmware profile.

No root cause is confirmed. Zero installed entries can mean a prerequisite
lookup failed before installation, or every entry installation failed. Reverse
hooks are installed when a rule is added; their zeroes do not prove a second
failure. Filtered dmesg returned no matching messages.

This diagnostic-only candidate retains startup results in the existing,
root-readable `or_entry_stat` parameter. The first status line is preserved.
Additional lines report the init result, whether function resolution was
attempted, the names of any missing functions and the result for each of the
five entry installations. `-125` on an entry means installation was not
attempted, not a kernel installer error. The fields are written only during
initialization and add no work to the open wrappers. The app reads this receipt
after a partial status response missing OPEN_REDIRECT and includes it in the
normal failure text, even when dmesg cannot be collected.

The feature prerequisites, activation gates, syscall wrappers, root backends,
firmware profiles and default settings are unchanged. No extra log statements,
retry loops, runtime probes or symbol addresses are added. This is not a claim
that the failure is fixed and is not intended for stable publication. Remove
the temporary receipt and its app reader once the actual cause is corrected.

The native source is reconstructed from the exact source shipped in 1.3.7 plus
`native.patch`, with Git commit/tree and source/module digest checks. Tests
compile the real init and status getter against fault-injected kernel API
models. They cover pending and disabled states, each missing prerequisite,
all prerequisites missing, optional symbols missing, each entry failure, all
entries failing and success. Mutation controls reject dropped failure fields.
Native cross-build, all existing host regressions, ABI/import/landing checks
and signed Android packaging run in the qualification workflow. Their results
are not phone testing.

## Phone check

Install `RootMyGalaxyUltra-redirect-startup-test1.apk` over the existing app,
fully reboot, then activate root and SusFS once. If the same failure occurs,
copy the app's complete result, including `Redirect startup:`. If necessary,
the same receipt can be read without changing kernel state:

```sh
su -c 'cat /sys/module/susfs_guard_lkm/parameters/or_entry_stat'
```

Do not attempt a second module load in a boot with a partial live module.
