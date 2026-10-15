#!/system/bin/sh
# Restart Samsung's already-running composers while the Android framework is stopped.
# Uses shell builtins plus Android getprop/setprop/sleep; no awk or BusyBox dependency.
case "$(getprop ro.product.manufacturer)" in
    [Ss][Aa][Mm][Ss][Uu][Nn][Gg]) ;;
    *) exit 0 ;;
esac

# Common lifecycle hooks run for manager-owned daemons too. Arm only in the
# explicit soft-reboot stage; ordinary late-load and a real boot must not cycle
# the display. The daemon PID and start time scope completion to one transition.
rmgu_mode=${1:-native}
rmgu_transition=/data/adb/rmgu/composer/transition
rmgu_key=
rmgu_phase=
rmgu_recorded_key=
rmgu_make_key() {
    case "$PPID" in ''|*[!0-9]*) return 1 ;; esac
    IFS= read -r rmgu_boot < /proc/sys/kernel/random/boot_id || return 1
    case "$rmgu_boot" in ''|*[!0-9a-fA-F-]*) return 1 ;; esac
    IFS= read -r rmgu_parent_stat < "/proc/$PPID/stat" || return 1
    # proc stat's comm may contain spaces or parentheses; fields after its last
    # closing parenthesis are fixed, and field 22 is the process start time.
    set -- ${rmgu_parent_stat##*) }
    [ "$#" -ge 20 ] || return 1
    shift 19
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    rmgu_key="$rmgu_boot:$PPID:$1"
}
rmgu_read_transition() {
    [ -f "$rmgu_transition" ] && [ ! -L "$rmgu_transition" ] || return 1
    { IFS= read -r rmgu_recorded_key; IFS= read -r rmgu_phase; } < "$rmgu_transition"
}
rmgu_write_transition() {
    [ -n "$rmgu_key" ] || return 1
    [ -d "${rmgu_transition%/*}" ] && [ ! -L "${rmgu_transition%/*}" ] || return 1
    [ ! -L "$rmgu_transition" ] || return 1
    if [ -e "$rmgu_transition" ]; then [ -f "$rmgu_transition" ] || return 1; fi
    rmgu_temporary="$rmgu_transition.$$.tmp"
    (umask 077; set -C; printf '%s\n%s\n%s\n' "$rmgu_key" "$1" "$rmgu_mode" > "$rmgu_temporary") || return 1
    if ! mv -f "$rmgu_temporary" "$rmgu_transition"; then
        rm -f "$rmgu_temporary"
        return 1
    fi
}

rmgu_make_key || rmgu_key=
rmgu_read_transition || :
case "$rmgu_mode" in
    arm)
        [ "$(getprop sys.boot_completed)" = 0 ] || exit 0
        rmgu_write_transition armed
        exit "$?"
        ;;
    post-mount)
        [ -n "$rmgu_key" ] && [ "$rmgu_recorded_key" = "$rmgu_key" ] || exit 0
        [ "$rmgu_phase" = armed ] || exit 0
        [ "$(getprop sys.boot_completed)" = 0 ] || exit 0
        [ "$(getprop init.svc.zygote)" = stopped ] || exit 0
        case "$(getprop init.svc.zygote_secondary)" in ''|stopped) ;; *) exit 0 ;; esac
        ;;
    native)
        # The common hook ran before the bundled native fallback. Do not cycle
        # twice; absent/failed hooks still leave the existing native path intact.
        if [ -n "$rmgu_key" ] && [ "$rmgu_recorded_key" = "$rmgu_key" ] && [ "$rmgu_phase" = done ]; then
            exit 0
        fi
        ;;
    *) exit 64 ;;
esac

rmgu_seen=
rmgu_composers=
for rmgu_rc_file in \
    /vendor/etc/init/*.rc /vendor/etc/init/hw/*.rc \
    /odm/etc/init/*.rc /odm/etc/init/hw/*.rc \
    /system/etc/init/*.rc /system/etc/init/hw/*.rc \
    /system_ext/etc/init/*.rc /product/etc/init/*.rc /init*.rc; do
    [ -r "$rmgu_rc_file" ] || continue
    while read -r rmgu_keyword rmgu_name rmgu_binary rmgu_args || [ -n "$rmgu_keyword" ]; do
        [ "$rmgu_keyword" = service ] || continue
        case "$rmgu_binary" in
            *hwcomposer*|*graphics*composer*|*display*composer*|*/hwc|*/hwc[._@-]*|*/composer|*/composer[._@-]*) ;;
            *) continue ;;
        esac
        case " $rmgu_seen " in
            *" $rmgu_name "*) continue ;;
        esac
        rmgu_seen="$rmgu_seen $rmgu_name"
        [ "$(getprop "init.svc.$rmgu_name")" = running ] || continue
        rmgu_composers="$rmgu_composers $rmgu_name"
    done < "$rmgu_rc_file"
done

if [ -z "$rmgu_composers" ]; then
    printf '%s\n' 'RMGU: no running Samsung hardware composer was discovered' >&2
    exit 1
fi

# Remember the complete active set before stopping anything. Coupled services may
# stop together; they must still all receive a start request.
rmgu_restore_needed=1
rmgu_restore_composers() {
    [ "$rmgu_restore_needed" = 1 ] || return 0
    for rmgu_restore_service in $rmgu_composers; do
        setprop ctl.start "$rmgu_restore_service" || :
    done
}
trap rmgu_restore_composers 0
trap 'exit 1' 1 2 15

rmgu_result=0
for rmgu_service in $rmgu_composers; do
    if ! setprop ctl.stop "$rmgu_service"; then
        printf 'RMGU: failed to stop %s\n' "$rmgu_service" >&2
        rmgu_result=1
    fi
done

# Each phase has a shared five-second polling budget, regardless of service count.
rmgu_ticks=0
for rmgu_service in $rmgu_composers; do
    rmgu_state="$(getprop "init.svc.$rmgu_service")"
    while [ "$rmgu_state" != stopped ] && [ "$rmgu_ticks" -lt 50 ]; do
        sleep 0.1
        rmgu_ticks=$((rmgu_ticks + 1))
        rmgu_state="$(getprop "init.svc.$rmgu_service")"
    done
    if [ "$rmgu_state" != stopped ]; then
        printf 'RMGU: stop timeout for %s (%s)\n' "$rmgu_service" "$rmgu_state" >&2
        rmgu_result=1
    fi
done

for rmgu_service in $rmgu_composers; do
    if ! setprop ctl.start "$rmgu_service"; then
        printf 'RMGU: failed to start %s\n' "$rmgu_service" >&2
        rmgu_result=1
    fi
done

rmgu_ticks=0
rmgu_all_running=1
for rmgu_service in $rmgu_composers; do
    rmgu_state="$(getprop "init.svc.$rmgu_service")"
    while [ "$rmgu_state" != running ] && [ "$rmgu_ticks" -lt 50 ]; do
        sleep 0.1
        rmgu_ticks=$((rmgu_ticks + 1))
        rmgu_state="$(getprop "init.svc.$rmgu_service")"
    done
    if [ "$rmgu_state" != running ]; then
        printf 'RMGU: start timeout for %s (%s)\n' "$rmgu_service" "$rmgu_state" >&2
        rmgu_result=1
        rmgu_all_running=0
    fi
done

if [ "$rmgu_all_running" = 1 ]; then
    rmgu_restore_needed=0
fi
if [ "$rmgu_result" = 0 ] && [ -n "$rmgu_key" ] && [ "$rmgu_recorded_key" = "$rmgu_key" ] && [ "$rmgu_phase" = armed ]; then
    rmgu_write_transition done || printf '%s\n' 'RMGU: composer completion could not be recorded' >&2
fi
exit "$rmgu_result"
