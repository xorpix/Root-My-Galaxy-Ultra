# Boot root speed test 1

This candidate targets the remaining fixed one-second DirtyFrag trigger wait.
The trigger child now observes its actual adoption by init before exiting.
If that evidence or the clock is unavailable, it retains the old one-second
fallback. The root readiness probe can run immediately; only a completed
saved-grant command returning UID 0 can shorten its five-second fallback.
Native cleanup and the final backend verification still finish before root
setup, SusFS activation or an automatic restart proceeds.

The earlier accepted Test6 phone timings were:

| Milestone | Boot uptime |
| --- | --- |
| Locked-boot broadcast received | 48.643 s |
| Helper requested the root worker | 49.345 s |
| Worker started | 49.573 s |
| Exploit started | 49.698 s |
| Root verified | 53.729 s |

The intended saving is roughly one second in that final stage. This is an
estimate, not a new phone measurement. It does not move Android's boot event.
The boot result now retains broadcast, request, worker and exploit uptimes;
the existing readiness log reports its elapsed milliseconds.

Host tests execute real double-fork adoption with immediate and delayed parent
exit, verify the bounded adoption fallback, and cover immediate/delayed root,
denied or stalled commands and the unchanged five-second readiness deadline.
The existing Odin, watchdog, subscriber and pipe/deadline cases remain.

The candidate is 1.2.13-boot-speed-test1 and installs over 1.2.13 using the same
signing key. Backend and SusFS payloads are unchanged. The boot helper source
and version are unchanged, so this experiment does not require its replacement.

## Phone comparison

Install the candidate, fully reboot and leave the phone locked until automatic
root and setup finish. Check root before unlock, SusFS, normal apps/modules,
Odin protection and the automatic soft restart as before. After unlocking,
collect the current boot result and the Boot log. For example, from a shell
with its existing root grant:

```sh
su -c 'cat /data/user_de/0/dev.experimental.azhlroot/files/early-root-result.json'
su -c 'tail -n 100 /data/user_de/0/dev.experimental.azhlroot/files/app-log.txt'
```

Compare root uptime with broadcast uptime, along with the readiness duration.
Use comparable boot settings and repeat the full boot if a single reading is
noisy. Stable 1.2.13 remains the published release while this is evaluated.
