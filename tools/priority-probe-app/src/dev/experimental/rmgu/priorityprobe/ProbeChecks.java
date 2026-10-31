// SPDX-License-Identifier: GPL-2.0
package dev.experimental.rmgu.priorityprobe;

final class ProbeChecks {
    static String classify(boolean normalCaller, boolean nativeComplete, boolean selfOk,
                           int targetGetpriority, int targetIoprioGet) {
        if (!normalCaller) return "CALLER_NOT_CONFIRMED_UNPRIVILEGED";
        if (!nativeComplete) return "NATIVE_QUERY_INCOMPLETE";
        if (!selfOk) return "SELF_QUERY_FAILED";
        if (targetGetpriority>=0 || targetIoprioGet>=0) return "TARGET_VISIBLE";
        if (targetGetpriority!=-3 || targetIoprioGet!=-3) return "TARGET_QUERY_OTHER_ERROR";
        // ESRCH also describes an absent UID. Do not turn this into an unconditional
        // pass: a separate live root reference is needed to establish hiding.
        return "HIDDEN_RESULTS_NEED_LIVE_ROOT_REFERENCE";
    }
    private ProbeChecks() {}
}
