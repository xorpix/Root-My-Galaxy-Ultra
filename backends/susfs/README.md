# Bundled Samsung SusFS source

`source.tar.gz` contains the complete source tree, tests, tools and audit validation records used for the bundled module. Extract it with `tar -xzf source.tar.gz`; build and test instructions are in `susfs/docs/audit-20261027/README.md`.

`build-manifest.json` pins the source archive SHA-256, source Git commit and tree, parent source commit, and stripped module SHA-256. `source.commit` is the raw Git commit object, allowing its identifier to be verified without relying on a remote source branch. `audit-fixes.patch` records the changes from the previous Samsung source. The kernel source retains its GPL license.

Run `python3 tools/check_susfs_bundle.py` from the app repository to verify the module and both firmware profiles, and reconstruct and verify the archive's Git tree and commit hashes. `--apk path/to/app.apk` also verifies the packaged bytes.

This is the Samsung Android16/6.12 implementation. The source snapshot includes historical documentation and upstream build workflows for context; those do not imply device validation of other kernel variants. See [the current app integration notes](../../docs/susfs-audit-fixes-20261027.md).
