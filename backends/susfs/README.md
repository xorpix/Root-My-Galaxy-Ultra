# Bundled Samsung SusFS source

`source.tar.gz` contains the complete source tree, tests, tools and validation records used for the bundled module. Extract it with `tar -xzf source.tar.gz`; current build and test instructions are in `susfs/docs/dispatcher-20261029/README.md`, with the earlier audit records retained in `susfs/docs/audit-20261027`.

`build-manifest.json` pins the source archive SHA-256, source Git commit and tree, parent source commit, and stripped module SHA-256. `source.commit` is the raw Git commit object, allowing its identifier to be verified without relying on a remote source branch. `dispatcher.patch` records the current delta; `audit-fixes.patch` retains the earlier audit repair delta. The kernel source retains its GPL license.

Run `python3 tools/check_susfs_bundle.py` from the app repository to verify the module and both firmware profiles, and reconstruct and verify the archive's Git tree and commit hashes. `--apk path/to/app.apk` also verifies the packaged bytes.

This is the Samsung Android16/6.12 implementation. The source snapshot includes historical documentation and upstream build workflows for context; those do not imply device validation of other kernel variants. See [the current app integration notes](../../docs/susfs-dispatcher-20261029.md).
