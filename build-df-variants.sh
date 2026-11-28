#!/usr/bin/env bash
# Build the android16-6.12 DirtyFrag LKM variants.
#
# The real build logic lives in tools/build_df_lkm.py: it renders the vendored
# upstream module with RMGU's staged-ksud bridge, builds each flavor in the
# DDK container, strips it, and records sizes/hashes in
# app/src/main/cpp/dirtyfrag/ko/provenance.json.
#
# Run from Git Bash:
#   ENGINE=docker ./build-df-variants.sh
#   ./build-df-variants.sh --check
set -euo pipefail

cd "$(dirname "$0")"
exec "${PYTHON:-python}" tools/build_df_lkm.py "$@"
