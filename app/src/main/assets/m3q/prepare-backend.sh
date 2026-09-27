#!/system/bin/sh
# Invoked only through the original M3Q helper after verified temporary root.
set -eu
backend=$1
source_daemon=$2
expected=$3
disable_requested=$4
case "$backend" in kernelsu|kernelsu-next|resukisu) ;; *) exit 64 ;; esac
case "$disable_requested" in 0|1) ;; *) exit 64 ;; esac
[ "$(id -u)" = 0 ] || exit 65
# Verify before changing module state. An old preparation receipt alone cannot
# identify a backend that another rooting app installed in the meantime.
actual=$(sha256sum "$source_daemon"); actual=${actual%% *}
[ "$actual" = "$expected" ] || exit 67
[ ! -L /data/adb ] || exit 66
mkdir -p /data/adb
[ -d /data/adb ] || exit 66
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
echo "M3Q_STAGE_OK:$backend:$expected"
