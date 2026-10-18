# Boot root wait behavior in 1.2.14

The DirtyFrag trigger waits until its child has actually been adopted by init.
It can finish as soon as adoption is observed, replacing the fixed one-second
wait. If adoption or the monotonic clock is unavailable, the original bounded
one-second fallback remains.

The readiness probe can run immediately after that handoff. Only a completed,
pre-authorized command returning UID 0 can end the readiness window early.
Denied or stalled commands retain the five-second deadline. Native cleanup and
final backend verification still finish before root setup, SusFS activation or
an automatic restart proceeds.

Temporary boot-timing fields and elapsed-time logging are removed from the
release. No benchmark results are embedded in the app.

## Automated validation

Host tests execute real double-fork adoption with immediate and delayed parent
exit, verify the one-second adoption fallback, and cover immediate/delayed root,
denied or stalled commands, clock failure and the readiness deadline.
Existing Odin, watchdog, subscriber and pipe/deadline cases remain.

The signed build checks Android unit tests, native compilation, matched backend
bundles, embedded companion identity and signatures, repository-key APK signing
and packaged payload integrity.

Backend, SusFS and boot helper payloads are unchanged from 1.2.13. The existing
boot helper does not need replacement. Install the update and perform a full
reboot to exercise the updated automatic root path.
