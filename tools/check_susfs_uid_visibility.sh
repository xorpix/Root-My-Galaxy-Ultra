#!/system/bin/sh
# Read-only diagnostics for UID/package mismatches. Run as root and pass the
# numeric UIDs reported by the detector. No settings or filter rules are changed.

if [ "$(id -u)" != 0 ]; then
  echo 'Run this script as root.' >&2
  exit 1
fi
if [ "$#" -eq 0 ]; then
  echo 'Usage: sh check_susfs_uid_visibility.sh UID [UID ...]' >&2
  exit 2
fi
for requested_uid do
  case "$requested_uid" in
    ''|*[!0-9]*) echo 'UIDs must be non-negative decimal numbers.' >&2; exit 2 ;;
  esac
  if ! [ "$requested_uid" -le 2147483647 ] 2>/dev/null; then
    echo 'UID is outside the supported range.' >&2
    exit 2
  fi
done

echo 'SusFS priority policy:'
policy=/sys/module/susfs_guard_lkm/parameters/priority_filter
if [ -r "$policy" ]; then
  cat "$policy"
else
  echo 'Priority status is unavailable.'
fi

for requested_uid do
  # Avoid interpreting a leading zero as octal in shell arithmetic.
  uid=$(printf '%s' "$requested_uid" | sed 's/^0*//')
  [ -n "$uid" ] || uid=0
  android_user=$((uid / 100000))
  printf '\nUID %s (Android user %s):\n' "$uid" "$android_user"
  if inventory=$(cmd package list packages -U --user "$android_user" 2>&1); then
    matches=$(printf '%s\n' "$inventory" | while IFS= read -r line; do
      case " $line " in
        *" uid:$uid "*) printf '%s\n' "$line" ;;
      esac
    done)
    if [ -n "$matches" ]; then
      printf '%s\n' "$matches"
    else
      echo 'No installed package association was returned for this UID.'
    fi
  else
    echo 'Package query failed:'
    printf '%s\n' "$inventory"
  fi
done
