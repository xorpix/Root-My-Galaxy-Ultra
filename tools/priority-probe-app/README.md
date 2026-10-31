# RMGU Priority Probe 1.0

A separate ARM64 Android app (`dev.experimental.rmgu.priorityprobe`) for checking
priority queries from an actual ordinary app UID and SELinux sandbox. It requests
no permissions, never invokes su, and has no network capability. The UI invokes
only the existing client's read-only `query <uid>` command, once for the target
and once for its own UID. It changes no rules, scheduling, credentials or mounts.
The separately bundled freestanding GPL client retains its existing commands;
only query is reachable from this app's UI. Its bytes match the test4 client.

Install the probe while keeping RMGU test4 and SusFS active. Do not grant the
probe root. Open RMGU, return to the probe, leave target UID 10303 (this phone's
RMGU UID), tap Run checks and Copy results. The caller should be a new ordinary
app UID, with an untrusted_app SELinux domain and no effective capabilities.
Target native getpriority/ioprio_get should be -3, with own-UID reads >=0.

ESRCH also describes an absent target. In Termux, run this root reference
immediately before and after the app test, and retain both outputs/PIDs:

```sh
su -c '
RMGU_APK=$(pm path dev.experimental.azhlroot | sed -n "s/^package://p" | head -n 1)
RMGU_TOOL="${RMGU_APK%/*}/lib/arm64/librmgususfs_priority.so"
pidof dev.experimental.azhlroot
"$RMGU_TOOL" query 10303
'
```

The same RMGU PID before/after and successful root queries corroborate that the
UID was live across the check. The app labels hidden-looking results
HIDDEN_RESULTS_NEED_LIVE_ROOT_REFERENCE instead of claiming an unconditional
pass. Root/self/target values are raw kernel returns, not libc's nice conversion.
No invalid or valid write APIs are called by this app; test4's earlier synthetic
probe already checked invalid I/O priority ordering. Compat execution and valid
write suppression are outside this app's scope.

For HMA: first run without applying package filtering to Priority Probe. Then
apply HMA filtering to Priority Probe and hide RMGU from it, leaving RMGU itself
outside HMA's filtered-caller scope. Repeat. The report's
rmgu_package_manager.visible should become false if HMA filters that package;
its target UID field remains usable for direct kernel queries. HMA visibility
and the priority result are reported separately. Copy both reports. This app
cannot itself establish that HMA is installed, enabled or configured correctly.

The app is non-debuggable and signed with a temporary test certificate distinct
from RMGU's release key. It does not update or replace RMGU. Uninstall it after
testing; rebuilding with another temporary key requires uninstalling this probe
before installing that rebuild. No key is uploaded in the artifact.

Build with JDK 21, Python 3, Android platform 36 and build-tools 36.0.0:
`python3 build.py --sdk-root "$ANDROID_HOME" --output ./dist`.
The source ZIP includes the exact native client and its source. To rebuild that
client byte-for-byte, use Android Clang r536225 from the project's pinned DDK:
`python3 build_priority_client.py --clang-bin /opt/ddk/clang/clang-r536225/bin --output ./libpriorityprobe.so`.
The source bundle and manifest pin every source file. Build checks cover the
verdict's ambiguity/error cases, real Android headers, APK manifest, native
client bytes, signature and ZIP alignment. Phone execution is still required.

Native source reference: the complete SusFS source and build evidence in the
app repository at backends/susfs/source.tar.gz (source commit
98b5149536fdc564132ca82b08d3980114be298a). The helper is GPL-2.0; the app source
is supplied under the same license. Android's SDK is not bundled.
