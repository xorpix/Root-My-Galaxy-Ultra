# Backend refresh for 1.4.0

KernelSU advances from `02ffbd447eb6a2f05d9f46e17b1e1c3bcfdd9971` to
`dbb73665d103da40c7ecbd21ba3b5ac76eb95784`. KernelSU-Next advances from
`b90876426197f5d35c3003bf42d8bf927dc20181` to
`3987e4d5de21348050b923e7d7a6540ddc2d5c8c`. BakaSU remains at `548d1dda`
and KowSU at `21787ce1` in the 12 November upstream snapshot.

Both changed backends retain the reviewed UAPI 6 control contract and version
formula, and the Samsung patches apply directly. KernelSU's new daemon change
validates profile names before forming policy/template paths. Next includes the
same change and upstream integrated SusFS work. RMGU does not enable that
integrated SusFS configuration; its separate tested Samsung module is retained.

The previous refresh stopped at Rust Clippy's `needless_pass_by_value` errors in
the new profile helpers. The compatibility patches borrow the package/template
names as `&str` and adapt their callers. Path validation and policy/template
contents are unchanged. Strict checks remain enabled; no lint is suppressed.

Source pins in `targets.json` describe the currently bundled pairs until the
native build completes. The refresh then updates the manifests, payloads,
helper versions, hashes and release table together. Build results and Android
unit tests do not constitute an on-device test.
