package dev.experimental.rmgu.d2;

/** Validate a fixed control request before any setting or vault operation is considered. */
public final class D2ControlPolicy {
    private D2ControlPolicy() {}
    public static void validate(int api, String method, Boolean enabled, String source, int version,
            String runningSource, int runningVersion, long deadline, long now) {
        if (api != D2Protocol.API) throw new IllegalArgumentException("Unsupported D2 protocol");
        // An abandoned or queued request must not turn into a later settings change.
        if (deadline <= now || deadline - now > 8000) {
            throw new IllegalStateException("D2 request expired; check status before retrying");
        }
        if ("configure".equals(method)) {
            if (enabled == null) throw new IllegalArgumentException("Missing switch value");
            if (enabled && (!runningSource.equals(source) || version != runningVersion)) {
                throw new IllegalStateException("Fully reboot to load the matching helper before enabling D2");
            }
        } else if (!("status".equals(method) || "refresh".equals(method)) || enabled != null) {
            throw new IllegalArgumentException("Unknown D2 operation or unexpected switch value");
        }
    }
}
