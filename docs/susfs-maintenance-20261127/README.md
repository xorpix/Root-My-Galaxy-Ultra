# SusFS LKM maintenance review, 27 November 2026

This review extends the Samsung bundle shipped in 1.4.7. It adapts the newly
added upstream `/proc/cmdline` coverage while retaining the existing Samsung
dispatcher, control lifetime and package visibility policy. It does not claim
full upstream 2.4 feature parity or phone validation of the new native module.

## Review coverage

Repository search and the public fork network yielded 90 LKM-related candidates:
85 reachable repositories, four unavailable repositories and one empty repository.
The reachable default branches contained 54 distinct heads. Twenty-six were
already ancestors of upstream `17cbf97e70ec0d80b20e02c5537b40baa388f24b`; all
28 divergent head groups were cloned and screened for native changes. Recent
pull requests were queried for all 85 reachable repositories, and every open
upstream PR was checked separately. Embedded KernelSU-Next SusFS changes were
also reviewed. This is a dated public-source survey, not a claim that private
repositories or every experimental branch were available.

Exact repository heads, branch inventories, divergent native commit summaries
and PR heads are recorded in [survey.json](survey.json). The earlier
[22 November review](../susfs-maintenance-20261122/README.md) remains applicable.

## Applied adaptation

Upstream [3b0676db](https://github.com/inforcqb/susfs4ksu-for-lkm/commit/3b0676dbc949494889238647f3f9516b452eb0e9)
and the corresponding [KernelSU-Next change](https://github.com/KernelSU-Next/KernelSU-Next/commit/2baabd6090291849f52790fc5c8d24221417d761)
exposed a missing path: configuring command-line/bootconfig spoofing covered
`/proc/bootconfig` but left `/proc/cmdline` showing the original command line.

Our adaptation hooks the output of `cmdline_proc_show` through the standard
ARM64 kretprobe API. It deliberately leaves `saved_command_line` untouched:
[Linux 6.12 declares that pointer `__ro_after_init`](https://github.com/torvalds/linux/blob/v6.12/init/main.c).
The hook is armed only when spoofing is first configured and requires a verified
function start. A failed registration rejects the configuration before it
changes bootconfig state. The hook replaces the appended seq_file output,
clears original tails, preserves the overflow/retry protocol and shares the
existing Tasks-RCU allocation retirement. Unload unregisters the return probe
before restoring and reclaiming values. No background service is introduced.

The standard probe is registered at the actual function entry, without shifting
past BTI/PAC instructions. The ARM64 kernel owns return-address handling.
See [Linux kprobes](https://github.com/torvalds/linux/blob/v6.12/kernel/kprobes.c)
and its [ARM64 implementation](https://github.com/torvalds/linux/blob/v6.12/arch/arm64/kernel/probes/kprobes.c).

## Other reviewed ideas

| Source or proposal | Decision for this bundle |
| --- | --- |
| Upstream PR #198 / pershoot 2.4, including the new package observer, statfs/kstat indexes and SELinux controls | Different hook and control architecture; retain the qualified Samsung dispatcher and lifetime handling. This maintenance port does not advertise unimplemented 2.4 features. |
| Upstream PR #226, priority logging build fix | Targets the new 2.4 parser, absent from this implementation. |
| Upstream PR #225, directly flashable DDK ZIP | Packaging path differs from RMGU's matched APK bundle. |
| Upstream PR #201, unsigned kstat ring slot | Already adapted in the existing bundle. |
| Upstream PR #165, priority and seq-file lifetime fixes | Existing Samsung implementation already has separate control-lifetime and reader protections. |
| LowKeyMerlin inode generation and Rust kallsyms type fixes | Already adapted in the 22 November maintenance source. |
| LowKeyMerlin pending-path create gate | Requires VFS helper and hook changes absent from this source; not a safe isolated cherry-pick. |
| mohdakil2426 x64 kallsyms scan enlargement | Targets embedded KallRecon, which this bundle does not contain. Its fixed-size search limit is absent here. |
| UIRAN23 uname/proc consistency and late-load changes | Mutates UTS namespace and replaces hook ownership; needs a separate adaptation. Existing RMGU backend and namespace handling is retained. |
| Carmenzr getpriority-only return-probe prototype | Existing implementation covers all four priority syscalls, native/compat routes and backend policy. |
| Remaining divergent heads | Primarily packaging, websites, architecture-specific work, older snapshots or changes already represented upstream; exact native summaries are in the survey. |

## Source and validation

`native.commit` and `native.patch` identify the exact incremental source based
on `e6ccb947d1ec1d0a0516ae4edd94577fbef38ff6`. The builder verifies baseline
archive/module hashes, reconstructs the Git tree, compiles with the pinned
Android 16 / Linux 6.12 DDK and checks the resulting module against the previous
bundle. The callback check now includes the two new kretprobe KCFI signatures.

Local validation passed 52 focused cases: 38 positive cases and 14 failing
mutation controls. These include default behavior, shorter/longer/maximum
output, registration failure, reset, malformed show state and reader retirement.
All 72 host-suite executions passed, including ASan/UBSan runs. The host reader
gate models a preempted reader; it is not an implementation of kernel RCU.
CI must additionally pass host, real-header ARM64 ABI and Android bundle/APK
qualification before the generated bundle is adopted.

Historical phone receipts retain their original source and module hashes.
The new module is explicitly recorded as hardware-unvalidated. Compilation,
host models and Android tests do not establish phone or long-uptime stability.
