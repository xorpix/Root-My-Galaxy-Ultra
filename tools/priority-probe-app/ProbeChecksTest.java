// SPDX-License-Identifier: GPL-2.0
package dev.experimental.rmgu.priorityprobe;
public final class ProbeChecksTest {
    private static void check(String expected, boolean caller, boolean complete,
                              boolean self, int gp, int io) {
        String result=ProbeChecks.classify(caller,complete,self,gp,io);
        if (!expected.equals(result)) throw new AssertionError(result);
    }
    public static void main(String[] args) {
        check("HIDDEN_RESULTS_NEED_LIVE_ROOT_REFERENCE",true,true,true,-3,-3);
        check("CALLER_NOT_CONFIRMED_UNPRIVILEGED",false,true,true,-3,-3);
        check("NATIVE_QUERY_INCOMPLETE",true,false,true,-3,-3);
        check("SELF_QUERY_FAILED",true,true,false,-3,-3);
        check("TARGET_VISIBLE",true,true,true,20,-3);
        check("TARGET_VISIBLE",true,true,true,-3,16384);
        check("TARGET_QUERY_OTHER_ERROR",true,true,true,-13,-3);
        check("TARGET_QUERY_OTHER_ERROR",true,true,true,-3,-1);
        System.out.println("PASS eight verdict cases; missing-target ambiguity remains explicit");
    }
}
