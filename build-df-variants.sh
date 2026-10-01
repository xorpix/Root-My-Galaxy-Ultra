#!/bin/bash
# Per-flavor android16-6.12 dirtyfrag.ko variants for Root My Galaxy Ultra.
# Same source as upstream (diabl0w/DFRoot dirtyfrag-lkm), strings differ per
# variant: staged ksud path, --package-name, plus driver-poll early-dfm0.
#
# Why poll: late-load hangs after the daemon is up (manager says Working,
# Termux su works) but before it exits, so `late-load && touch dfm0` never
# fires within the app's wait and first-try fails without a su grant. The KO
# now backgrounds late-load and touches dfm0 as soon as /sys/module/kernelsu
# appears (root + permissive SELinux in umh context can see it; the app
# cannot due to Samsung policy). 120 s budget matches exp.c marker poll.
#
# Run from Git Bash: ENGINE=docker ANDROID_NDK_ROOT='/c/.../ndk/28.2.13676358' ./build-df-variants.sh
set -euo pipefail

ENGINE="${ENGINE:-docker}"
UPSTREAM='/c/Users/xorpix/AppData/Local/Temp/opencode/dfroot-upstream/dirtyfrag-lkm'
OUT='/c/Users/xorpix/AppData/Local/Temp/opencode/df-variants'
OBJCOPY="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objcopy.exe"
REPO_KO_DIR="$(cd "$(dirname "$0")/app/src/main/cpp/dirtyfrag/ko" && pwd)"

OUR_PATH='/data/user_de/0/dev.experimental.azhlroot/ksud'

flavor_pkg() {
    case "$1" in
        kernelsu)      echo me.weishu.kernelsu ;;
        kernelsu-next) echo com.rifsxd.ksunext ;;
        resukisu)      echo com.resukisu.resukisu ;;
        *) echo "unknown flavor $1" >&2; exit 1 ;;
    esac
}

rm -rf "$OUT"
mkdir -p "$OUT"
for flavor in kernelsu kernelsu-next resukisu; do
    echo "=== variant: $flavor ==="
    d="$OUT/$flavor"
    mkdir -p "$d"
    sed -e "s|/data/user_de/0/df.root/ksud|$OUR_PATH|" \
        -e "s|me.weishu.kernelsu|$(flavor_pkg "$flavor")|" \
        -e "s| --ro-partitions||" \
        -e "s|\"%s late-load|\"mkdir -p /data/adb \&\& cat /data/user_de/0/dev.experimental.azhlroot/ksud > /data/local/tmp/.ksud-stage \&\& %s late-load|" \
        "$UPSTREAM/dirtyfrag.c" > "$d/dirtyfrag.c"
    # 256-byte cmd buffer cannot hold the poll loop; grow it.
    sed -i 's|static char cmd\[256\];|static char cmd[1024];|' "$d/dirtyfrag.c"
    # Late-load hangs after the daemon is up: background it, touch dfm0 when
    # the driver appears, only fall back to wait/exit-code on early exit or
    # 120 s timeout (60 x 2 s). Log late-load output for post-mortem.
    sed -i 's# && touch /dev/dfm0 || touch /dev/dfm1# > /data/local/tmp/.ll.log 2>\&1 \& LL=$!;i=0;while [ $i -lt 60 ];do [ -e /sys/module/kernelsu ]||[ -e /sys/module/ksunext ]||[ -e /sys/module/sukisu ]||[ -e /sys/module/resukisu ]||[ -e /sys/module/ksu ]\&\&touch /dev/dfm0\&\&exit 0;kill -0 $LL 2>/dev/null||break;i=$((i+1));sleep 2;done;if [ $i -ge 60 ];then kill -9 $LL 2>/dev/null;touch /dev/dfm1;else wait $LL\&\&touch /dev/dfm0||touch /dev/dfm1;fi#' "$d/dirtyfrag.c"
    grep -c "$OUR_PATH" "$d/dirtyfrag.c"
    grep -c "$(flavor_pkg "$flavor")" "$d/dirtyfrag.c"
    grep -c "/sys/module/kernelsu" "$d/dirtyfrag.c"
    cp "$UPSTREAM/Makefile" "$d/Makefile"
    # Neuter the rename-path DEFEX hooks the stock pair misses, with the same
    # kprobe pattern the module already uses (spaces, not tabs, keep this sed
    # robust against whitespace).
    sed -i \
      -e '/symbol_name = "get_dc_target_dpath"/a static struct kprobe kp_link_protect = { .symbol_name = "task_defex_link_protection", .pre_handler = defex_pre_handler };\nstatic struct kprobe kp_enforce = { .symbol_name = "task_defex_enforce", .pre_handler = defex_pre_handler };\nstatic struct kprobe kp_syscall_enter = { .symbol_name = "defex_syscall_enter", .pre_handler = defex_pre_handler };' \
      -e '/ret = umh_exec(info, UMH_WAIT_PROC);/i             register_kprobe(\&kp_link_protect);\n            register_kprobe(\&kp_enforce);\n            register_kprobe(\&kp_syscall_enter);' \
      -e '/if (kp_dc_path.addr)/a             if (kp_link_protect.addr) unregister_kprobe(\&kp_link_protect);\n            if (kp_enforce.addr) unregister_kprobe(\&kp_enforce);\n            if (kp_syscall_enter.addr) unregister_kprobe(\&kp_syscall_enter);' \
      "$d/dirtyfrag.c"
    ( cd "$d" && "$ENGINE" run --rm --pid=host --network=none \
        -v "$(cygpath -m "$(pwd)"):/src" -w //src \
        ghcr.io/ylarod/ddk-min:android16-6.12 make )
    cp "$d/dirtyfrag.ko" "$OUT/dirtyfrag-android16-6.12-$flavor.ko"
    "$OBJCOPY" --strip-unneeded \
      -R .comment -R .note.gnu.build-id -R .note.gnu.property -R .note.Linux -R .note.GNU-stack \
      -R .BTF -R .BTF.base -R .llvm_addrsig \
      -R .hyp.text -R .hyp.bss -R .hyp.rodata -R .hyp.event_ids \
      -R .hyp.patchable_function_entries -R .hyp.data \
      "$OUT/dirtyfrag-android16-6.12-$flavor.ko"
    cp "$OUT/dirtyfrag-android16-6.12-$flavor.ko" "$REPO_KO_DIR/"
    echo "copied to $REPO_KO_DIR/dirtyfrag-android16-6.12-$flavor.ko"
done
ls -l "$OUT"/*.ko
ls -l "$REPO_KO_DIR"/dirtyfrag-android16-6.12-*.ko
