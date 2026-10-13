package dev.experimental.rmgu.bootprobe;

/** Classifies observations, never inferring a running animation from an absent property. */
final class BootEvidence {
    enum Window { EARLY, RUNNING_UNCERTAIN, EXITING_OR_FINISHED, UNKNOWN }

    static Window classify(String animation, String exit, String completed) {
        if ("1".equals(exit) || "1".equals(completed)
                || "stopped".equals(animation) || "stopping".equals(animation)) {
            return Window.EXITING_OR_FINISHED;
        }
        if (!"running".equals(animation)) return Window.UNKNOWN;
        if ("0".equals(exit) && "0".equals(completed)) return Window.EARLY;
        return Window.RUNNING_UNCERTAIN;
    }

    static boolean candidate(boolean connected, boolean userLocked, boolean ipsecReady,
            Window window) {
        return connected && userLocked && ipsecReady && window == Window.EARLY;
    }
}
