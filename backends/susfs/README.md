# Bundled Samsung SusFS source

`source.tar.gz` contains the complete corresponding source, tests, tools and
validation records for the bundled module. Extract it with `tar -xzf source.tar.gz`;
current production cleanup and checks are in `susfs/docs/production-20261031`.
Earlier test guides in the archive are historical.

`build-manifest.json` pins the source archive SHA-256, source Git commit/tree,
parent source commit, stripped module and ARM64 control client. `source.commit`
is the raw Git commit object. `priority-filter.patch` records the cumulative
delta from Samsung source `961fb6a`; dispatcher/audit patches retain earlier deltas.
The corresponding kernel source retains its GPL license.

Run `python3 tools/check_susfs_bundle.py` from the app repository to verify the
module, control client, firmware profiles and reconstructed Git tree/commit.
`--apk path/to/app.apk` also verifies packaged bytes.

The maintainer accepted test4 phone validation with HMA reporting no failures.
Production removes temporary phone probes, initialization diagnostics and device
fault injection while retaining the tested priority hooks/fallback and automatic
selection. Production host/sanitizer, ARM64 build and real-header ABI checks pass.
See [app integration notes](../../docs/susfs-priority-filter-20261030.md).
