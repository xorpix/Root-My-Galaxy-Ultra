# Maintenance review, 22 November 2026

The daily compatibility run 45011598022 failed because KowSU replaced the
history containing our built pin `d4873310`. The replacement anchor is
`44758e199257bb973aa6ca5b3a393c1b21694408`. Comparing the endpoint trees leaves
two runtime changes, selecting the first exact symbol match and granting logd
permission to read root-process attributes. UAPI and daemon sources are identical.
The version increases from 32761 to 32766 and the Samsung compatibility patch
applies directly. `refresh.py` accepts this exact old pin and the reviewed anchor
or Git-proven descendants. Unrelated rewrites continue to fail verification.

BakaSU subsequently advanced to `2734e0bc155759955880f5fb99be640fc5b92156`.
Its SELinux feature moved into a central file and separate automatic and
built-in-SusFS implementations. The Samsung patch now follows that split,
retains upstream's backup-policy lifetime handling and publishes the feature's
enabled state after hook installation. The new inode_setxattr hook uses the
same reviewed Samsung fallback as the other three backends. Existing route
tests now compile BakaSU's adapter as well, covering arguments, recursion,
disabled state, failed installation and cleanup during a diverted callback.
The upstream UAPI and version formulas remain unchanged. The new template
hash marks this backend for a matched driver and daemon rebuild.

The SusFS survey covered 19 distinct upstream, fork and packaging repositories.
The old `inforcqb/susfs4ksu-lkm` URL redirects to `susfs4ksu-for-lkm`.
Their exact inspected revisions follow.

| Repository | Reviewed head |
| --- | --- |
| [Bouteillepleine/susfs4ksu-lkm](https://github.com/Bouteillepleine/susfs4ksu-lkm) | [8619b6ee](https://github.com/Bouteillepleine/susfs4ksu-lkm/commit/8619b6ee0d0629836e9c6ce668e8a785dfd53517) |
| [CoolBreeze0/susfs4ksu-lkm](https://github.com/CoolBreeze0/susfs4ksu-lkm) | [191339da](https://github.com/CoolBreeze0/susfs4ksu-lkm/commit/191339da5db5e9a45a4b011f9f7aa4174a760bae) |
| [Hobbit233/susfs4ksu-lkm](https://github.com/Hobbit233/susfs4ksu-lkm) | [060fa0e9](https://github.com/Hobbit233/susfs4ksu-lkm/commit/060fa0e9cb48caf5c554e33ab012ca10e0f1308c) |
| [LowKeyMerlin/susfs4ksu-lkm](https://github.com/LowKeyMerlin/susfs4ksu-lkm) | [775751fc](https://github.com/LowKeyMerlin/susfs4ksu-lkm/commit/775751fc46989a5de40d195fa6d3398fdc8955f3) |
| [Tr4cck/susfs4ksu-lkm](https://github.com/Tr4cck/susfs4ksu-lkm) | [85ef920c](https://github.com/Tr4cck/susfs4ksu-lkm/commit/85ef920ccf27682a2a9089b4676547f691b92828) |
| [Xiaogugtfgdfhgrh/SUSFS4LKM](https://github.com/Xiaogugtfgdfhgrh/SUSFS4LKM) | [f5416280](https://github.com/Xiaogugtfgdfhgrh/SUSFS4LKM/commit/f5416280a86ed8a7e833c053b96b5eb7cf483b39) |
| [Yunnijian/susfs4ksu-lkm](https://github.com/Yunnijian/susfs4ksu-lkm) | [b69dee41](https://github.com/Yunnijian/susfs4ksu-lkm/commit/b69dee412aac54cdf99832412de3b4758118a1eb) |
| [bikesh109/susfs4ksu-lkm](https://github.com/bikesh109/susfs4ksu-lkm) | [484562df](https://github.com/bikesh109/susfs4ksu-lkm/commit/484562df2012c051954d0033a91d76fbe45d2cca) |
| [doccstat/susfs4ksu-lkm](https://github.com/doccstat/susfs4ksu-lkm) | [a4f475b2](https://github.com/doccstat/susfs4ksu-lkm/commit/a4f475b2485f52c006d7932e157b2d02bfcebf47) |
| [feiyunwill/susfs4ksu-lkm](https://github.com/feiyunwill/susfs4ksu-lkm) | [191339da](https://github.com/feiyunwill/susfs4ksu-lkm/commit/191339da5db5e9a45a4b011f9f7aa4174a760bae) |
| [flybird0010/susfs4ksu-lkm](https://github.com/flybird0010/susfs4ksu-lkm) | [85ef920c](https://github.com/flybird0010/susfs4ksu-lkm/commit/85ef920ccf27682a2a9089b4676547f691b92828) |
| [forskifx/susfs4ksu-lkm](https://github.com/forskifx/susfs4ksu-lkm) | [761b9308](https://github.com/forskifx/susfs4ksu-lkm/commit/761b93083afd846858257221453bf306465042a3) |
| [inforcqb/susfs4ksu-for-lkm](https://github.com/inforcqb/susfs4ksu-for-lkm) | [2a08c67e](https://github.com/inforcqb/susfs4ksu-for-lkm/commit/2a08c67e4199fec079ee1e899d5c41a7091f6bf9) |
| [matsuzaka-yuki/susfs-lkm-module](https://github.com/matsuzaka-yuki/susfs-lkm-module) | [cf8f05f1](https://github.com/matsuzaka-yuki/susfs-lkm-module/commit/cf8f05f103434b7cfe99ff9505fc32ba9950012f) |
| [pershoot/susfs4ksu-for-lkm](https://github.com/pershoot/susfs4ksu-for-lkm) | [2a08c67e](https://github.com/pershoot/susfs4ksu-for-lkm/commit/2a08c67e4199fec079ee1e899d5c41a7091f6bf9) |
| [rifsxd/susfs4ksu-lkm](https://github.com/rifsxd/susfs4ksu-lkm) | [add7aaf3](https://github.com/rifsxd/susfs4ksu-lkm/commit/add7aaf38f749898a24bf03d3892d7f3438a3062) |
| [supervooc/susfs4ksu-lkm](https://github.com/supervooc/susfs4ksu-lkm) | [9e776d87](https://github.com/supervooc/susfs4ksu-lkm/commit/9e776d87b731ef13b27ab3b17669336b36d8cdd5) |
| [yapixel/susfs4ksu-lkm](https://github.com/yapixel/susfs4ksu-lkm) | [494e2dd2](https://github.com/yapixel/susfs4ksu-lkm/commit/494e2dd2c725d4f5f6e6f14af933680e7052e10d) |
| [ymuuuu/susfs4ksu-lkm-x64](https://github.com/ymuuuu/susfs4ksu-lkm-x64) | [c6e94c35](https://github.com/ymuuuu/susfs4ksu-lkm-x64/commit/c6e94c35d45cd2b96b5f97719b4a12236c0cb4fe) |

The current Samsung source starts at
`3ebe7cf0cb08108856e79fdcdbfe4b3c2bd3a3a1`. Two LowKeyMerlin fixes apply to
this implementation and are adapted in `native.patch`.

- [Inode generation check, 766f09b](https://github.com/LowKeyMerlin/susfs4ksu-lkm/commit/766f09b05507383373b8de34edc84d25f2a2e668). Capture the generation on every registration and pending-rule publication path. The LSM identity fallback excludes a reused inode number when both generations are known and different. The exact-object match, zero-generation behavior, rule locks and control-node namespace matching retain their existing semantics.
- [Rust kallsyms type, 759a145](https://github.com/LowKeyMerlin/susfs4ksu-lkm/commit/759a145994f168f9821a93fb572f7b9c9af23ea2). Accept the observed Rust type `3` in the existing bounded iterator alphabet. This adaptation retains the other alphabet restrictions and nofault snapshot checks.

The path tests cover matching and reused generations, both zero-generation
fallbacks, exact-object matching, control rules and ordinary registration,
pending resolution and manual pending completion. A negative control removes
the generation comparison and must fail the reused-inode case. Kallsyms tests
exercise all 256 type bytes and both unterminated string fields, with the old
alphabet as a failing control. The full extracted production-C host suite runs
in ordinary and ASan/UBSan configurations. These checks model kernel services
on the host and do not constitute phone testing.

Other reviewed changes include upstream PRs #198, #200, #201, #202, #203,
#204, #205, #206, #212, #213, #214, #215, #216, #219 and #221. The unsigned
kstat ring and validated modules callback already exist in our source. The
PAC return and inline-hook changes use a different hook architecture. The
new LSM list-offset fix targets the pre-6.12 list-insertion branch. The current
Samsung 6.12 static-call path has separate runtime identity checks. The
non-BTI, statfs/fdinfo, 512-rule indexing, new UID controls and broad layer
refactor changes require distinct adaptations or target implementation paths
absent from this bundle. Packaging-only and x86 changes have no Samsung ARM64
runtime change to apply here.

The native builder reconstructs the exact baseline, verifies the patch, Git
tree and commit, compiles with the pinned Android 16 6.12 DDK, compares the
module ABI and stages matching source and bundle metadata. Historical phone
receipts keep their own source and module hashes. The new module is recorded
as hardware-unvalidated until separately tested on a phone.
