#!/system/bin/sh
# Invoked only through the original M3Q helper after verified temporary root.
set -eu
backend=$1
source_daemon=$2
expected=$3
disable_requested=$4
wipe_requested=$5
case "$backend" in kernelsu|kernelsu-next|resukisu) ;; *) exit 64 ;; esac
case "$disable_requested" in 0|1) ;; *) exit 64 ;; esac
case "$wipe_requested" in 0|1) ;; *) exit 64 ;; esac
[ "$(id -u)" = 0 ] || exit 65
# Verify before changing module state. An old preparation receipt alone cannot
# identify a backend that another rooting app installed in the meantime.
actual=$(sha256sum "$source_daemon"); actual=${actual%% *}
[ "$actual" = "$expected" ] || exit 67
[ ! -L /data/adb ] || exit 66
mkdir -p /data/adb
[ -d /data/adb ] || exit 66
# DEFEX pathname dodge: this bootstrap root can stat /data/adb but can neither
# list nor modify beneath it (measured: ls returns nothing, receipt writes get
# EPERM), while /data/local/tmp is fully usable. Work through a bind alias
# whose path is not policed. Best-effort unless wipe demands certainty below.
ADB_ALIAS=/data/local/tmp/.rmgnext-adb-alias
alias_usable=0
# A stale bind cannot survive reboot, but its empty dir can.
/system/bin/toybox umount "$ADB_ALIAS" >/dev/null 2>&1 || true
rmdir "$ADB_ALIAS" >/dev/null 2>&1 || true
if [ -x /system/bin/toybox ] && mkdir -p "$ADB_ALIAS" 2>/dev/null; then
    if /system/bin/toybox mount -o bind /data/adb "$ADB_ALIAS" 2>/dev/null && \
       /system/bin/toybox grep -F " $ADB_ALIAS " /proc/self/mountinfo >/dev/null 2>&1 && \
       ls -A "$ADB_ALIAS" >/dev/null 2>&1; then
        alias_usable=1
    else
        /system/bin/toybox umount "$ADB_ALIAS" >/dev/null 2>&1 || true
        rmdir "$ADB_ALIAS" >/dev/null 2>&1 || true
    fi
fi
umask 077
receipt=/data/adb/azhl-last-prepared-backend
[ ! -L "$receipt" ] || exit 66
previous=
if [ -e "$receipt" ]; then previous=$(cat "$receipt") || exit 66; fi
installed_matches=0
if [ -f /data/adb/ksud ] && [ ! -L /data/adb/ksud ]; then
    # The installed daemon may be unreadable to this root (restricted
    # capabilities / foreign ownership from another installer) while the
    # check itself must not fail a run. Unknown means mismatch: that only
    # disables modules and restages, strictly the safer direction.
    if installed=$(sha256sum /data/adb/ksud 2>/dev/null); then
        installed=${installed%% *}
        if [ "$installed" = "$expected" ]; then installed_matches=1; fi
    else
        echo 'installed ksud unreadable; treating as mismatch' >&2
    fi
fi
# Read-only inventory of /data/adb for late-load panic forensics. Unconditional:
# when a run panics at late-load, the exported log is cut short, so the state
# the load saw must already be on screen. Names only, never contents; bounded,
# and never fails the run.
if [ -d /data/adb ] && [ ! -L /data/adb ]; then
    adb_state_count=0
    for entry in /data/adb/* /data/adb/.[!.]* /data/adb/..?*; do
        if [ ! -e "$entry" ] && [ ! -L "$entry" ]; then continue; fi
        if [ -L "$entry" ]; then
            echo "ADB_TOP $entry symlink -> $(readlink "$entry")"
        elif [ -d "$entry" ]; then
            child_count=0
            for child in "$entry"/*; do
                if [ ! -e "$child" ] && [ ! -L "$child" ]; then continue; fi
                child_count=$((child_count + 1))
                if [ "$child_count" -le 12 ]; then
                    echo "ADB_CHILD ${entry}/${child##*/}"
                fi
            done
            echo "ADB_TOP $entry dir children=$child_count"
        else
            echo "ADB_TOP $entry file"
        fi
        adb_state_count=$((adb_state_count + 1))
        if [ "$adb_state_count" -ge 40 ]; then
            echo "ADB_TOP inventory truncated"
            break
        fi
    done
    find /data/adb -maxdepth 4 -iname '*mountify*' -o -iname '*metamount*' -o -iname '*meta*' -o -iname '*.img' 2>/dev/null | head -20 | while IFS= read -r hit; do
        echo "ADB_HUNT $hit"
    done
fi
# Recovery reset: when the wipe setting is on, remove every root leftover under
# /data/adb itself - not just the module directories. A metamodule staged copy
# outside modules/ re-runs and re-materializes modules after this script, and
# that survives anything narrower. Contents only: the directory inode, owner
# and SELinux context stay untouched. Fail closed: a busy mount or an
# undeletable entry stops the run instead of loading on top of half-removed
# state.
if [ "$wipe_requested" = 1 ]; then
    for entry in /data/adb/* /data/adb/.[!.]* /data/adb/..?*; do
        if [ ! -e "$entry" ] && [ ! -L "$entry" ]; then continue; fi
        rm -rf "$entry" || exit 66
    done
    echo "MODULE_STATE_WIPED /data/adb"
fi
# Same temporary Mountify block as below, through the alias when the direct path
# is policed. Best-effort; the truth check at the end reports what worked.
if [ "$alias_usable" = 1 ]; then
    for directory in "$ADB_ALIAS/modules" "$ADB_ALIAS/modules_update"; do
        [ ! -L "$directory" ] || continue
        [ ! -e "$directory" ] && continue
        [ -d "$directory" ] || continue
        for entry in "$directory"/[Mm][Oo][Uu][Nn][Tt][Ii][Ff][Yy]*; do
            if [ ! -e "$entry" ] && [ ! -L "$entry" ]; then continue; fi
            [ ! -L "$entry" ] || continue
            if rm -rf "$entry" 2>/dev/null; then
                echo "MOUNTIFY_REMOVED alias:$entry"
            fi
        done
    done
fi
# Recovery certainty: with wipe requested, the alias MUST have delivered a
# readable, writable tree - otherwise loading would panic on dirty state.
# Fail here, loudly, instead of late-loading into a freeze-reboot.
if [ "$wipe_requested" = 1 ]; then
    if [ "$alias_usable" != 1 ]; then
        echo "WIPE_DEBUG alias unusable with wipe requested; refusing dirty load"
        echo 'Alias path unusable with wipe requested; refusing dirty load.' >&2
        exit 72
    fi
    for entry in "$ADB_ALIAS"/* "$ADB_ALIAS"/.[!.]* "$ADB_ALIAS"/..?*; do
        if [ ! -e "$entry" ] && [ ! -L "$entry" ]; then continue; fi
        rm -rf "$entry" || exit 66
    done
    echo "MODULE_STATE_WIPED alias:/data/adb"
fi
# Temporary Mountify guard: a Mountify footprint under the module directories
# hangs this device's late-load, and stamping it disabled does not stop that.
# Strip it on sight until the conflict is understood. Loud by design.
for directory in /data/adb/modules /data/adb/modules_update; do
    [ ! -L "$directory" ] || exit 66
    [ ! -e "$directory" ] && continue
    [ -d "$directory" ] || exit 66
    for entry in "$directory"/[Mm][Oo][Uu][Nn][Tt][Ii][Ff][Yy]*; do
        if [ ! -e "$entry" ] && [ ! -L "$entry" ]; then continue; fi
        [ ! -L "$entry" ] || exit 66
        rm -rf "$entry" || exit 66
        echo "MOUNTIFY_REMOVED $entry"
    done
done
# Opt-in recovery wipe: delete all module state before loading, for a boot
# whose /data/adb state hangs late-load even with every module disabled
# (observed after swapping the magic-mount provider). Off unless the setting
# says so; deleting is one-directional, so this never defaults on. Fail
# closed: a wipe that cannot finish stops the run instead of loading on top
# of half-removed state.
if [ "$wipe_requested" = 1 ]; then
    for directory in /data/adb/modules /data/adb/modules_update; do
        [ ! -L "$directory" ] || exit 66
        [ ! -e "$directory" ] && continue
        [ -d "$directory" ] || exit 66
        for entry in "$directory"/* "$directory"/.[!.]* "$directory"/..?*; do
            if [ ! -e "$entry" ] && [ ! -L "$entry" ]; then continue; fi
            rm -rf "$entry" || exit 66
        done
        echo "MODULE_STATE_WIPED $directory"
    done
fi
if [ "$disable_requested" = 1 ] || [ "$previous" != "$backend" ] || [ "$installed_matches" != 1 ]; then
    for directory in /data/adb/modules /data/adb/modules_update; do
        [ ! -L "$directory" ] || exit 66
        [ ! -e "$directory" ] && continue
        [ -d "$directory" ] || exit 66
        for module in "$directory"/* "$directory"/.[!.]* "$directory"/..?*; do
            if [ ! -e "$module" ] && [ ! -L "$module" ]; then continue; fi
            [ ! -L "$module" ] || exit 66
            [ -d "$module" ] || continue
            [ ! -L "$module/disable" ] || exit 66
            if [ -e "$module/disable" ]; then [ -f "$module/disable" ] || exit 66; fi
            : >> "$module/disable"
        done
    done
    echo 'Existing modules disabled; re-enable compatible modules in the selected manager.'
fi
# Source is an installed, pinned native-library file, as in the original APK.
for destination in /data/local/tmp/ksud-m3q-S948NKSS4AZG3-kdp /data/local/tmp/.ksud-stage; do
    [ ! -d "$destination" ] || exit 66
    temporary=$(mktemp /data/local/tmp/.rmgnext-m3q-stage.XXXXXXXX) || exit 68
    trap 'rm -f "$temporary"' EXIT HUP INT TERM
    cp "$source_daemon" "$temporary"
    chmod 0755 "$temporary"
    actual=$(sha256sum "$temporary"); actual=${actual%% *}
    [ "$actual" = "$expected" ] || exit 67
    mv -f "$temporary" "$destination"
    actual=$(sha256sum "$destination"); actual=${actual%% *}
    [ "$actual" = "$expected" ] || exit 67
    trap - EXIT HUP INT TERM
done
# This records preparation, not proof of a loaded driver. Success is recorded
# separately by the app only after a fresh native control query succeeds.
# The receipt directory may reject new files from this root (observed EPERM
# creating in /data/adb while /data/local/tmp works). Never fail a run over
# bookkeeping: without a receipt the next run simply disables and restages
# again, strictly the safer direction.
temporary=$(mktemp /data/adb/.azhl-prepared.XXXXXXXX 2>/dev/null) || temporary=
if [ -n "$temporary" ]; then
    trap 'rm -f "$temporary"' EXIT HUP INT TERM
    printf '%s\n' "$backend" > "$temporary"
    mv -f "$temporary" "$receipt"
    trap - EXIT HUP INT TERM
else
    echo 'preparation receipt unwritable; continuing without it' >&2
fi
# Truth check, unconditional and last so output trimming cannot eat it: re-read
# exactly what late-load is about to see. -1 means the directory could not even
# be listed (access failure, not emptiness).
nr_modules=-1; nr_update=-1
if ls -A /data/adb/modules >/dev/null 2>&1; then
    nr_modules=$(ls -A /data/adb/modules 2>/dev/null | wc -l)
fi
if ls -A /data/adb/modules_update >/dev/null 2>&1; then
    nr_update=$(ls -A /data/adb/modules_update 2>/dev/null | wc -l)
fi
mm_link=$(readlink /data/adb/metamodule 2>/dev/null || echo none)
mm_persist=$([ -e /data/adb/mountify ] && echo present || echo absent)
al_modules=-1; al_update=-1
if [ "$alias_usable" = 1 ]; then
    if ls -A "$ADB_ALIAS/modules" >/dev/null 2>&1; then
        al_modules=$(ls -A "$ADB_ALIAS/modules" 2>/dev/null | wc -l)
    fi
    if ls -A "$ADB_ALIAS/modules_update" >/dev/null 2>&1; then
        al_update=$(ls -A "$ADB_ALIAS/modules_update" 2>/dev/null | wc -l)
    fi
fi
echo "WIPE_DEBUG modules_remaining=$nr_modules update_remaining=$nr_update metamodule_link=$mm_link mountify_persist=$mm_persist alias_usable=$alias_usable alias_modules=$al_modules alias_update=$al_update"
# Alias served its purpose; unmount on the normal path so no mirror outlives
# this script. A panic-reboot clears mounts anyway; the next run clears stale.
if [ "$alias_usable" = 1 ]; then
    /system/bin/toybox umount "$ADB_ALIAS" >/dev/null 2>&1 || true
    rmdir "$ADB_ALIAS" >/dev/null 2>&1 || true
fi
echo "M3Q_STAGE_OK:$backend:$expected"
