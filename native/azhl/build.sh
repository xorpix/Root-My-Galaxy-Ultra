#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28c}"
cc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang"
mkdir -p build
"$cc" -O2 -g0 -Wall -Wextra -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function \
  -Isrc -fPIC -shared src/main.c src/util.c src/bootclaim.c src/slide.c src/fops.c \
  src/attr.c src/root.c src/params.c src/params_table.c src/preload.c -llog -pthread -o build/loader.so
"$cc" -O2 -g0 -Wall -Wextra -Isrc -fPIE -pie rmg_helper.c -ldl -o build/libcve43499root.so
