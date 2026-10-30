# Bundled Samsung SusFS source

`source.tar.gz` contains the complete source tree, tests, tools and validation records used for the bundled module. Extract it with `tar -xzf source.tar.gz`; current build and test instructions are in `susfs/docs/priority-filter-20261030/README.md`, with the earlier dispatcher and audit records retained.

`build-manifest.json` pins the source archive SHA-256, source Git commit and tree, parent source commit, stripped module SHA-256, and ARM64 priority control client. `source.commit` is the raw Git commit object, allowing its identifier to be verified without relying on a remote source branch. `priority-filter.patch` records the current delta from source `961fb6a`; `dispatcher.patch` and `audit-fixes.patch` retain the earlier deltas. The kernel source retains its GPL license.

Run `python3 tools/check_susfs_bundle.py` from the app repository to verify the module, control client and all firmware profiles, and reconstruct and verify the archive's Git tree and commit hashes. `--apk path/to/app.apk` also verifies the packaged bytes.

This is the Samsung Android16/6.12 implementation. The source snapshot includes historical documentation and upstream build workflows for context; those do not imply device validation of other kernel variants. See [the current app integration notes](../../docs/susfs-priority-filter-20261030.md).
