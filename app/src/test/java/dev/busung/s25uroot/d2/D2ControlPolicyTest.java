package dev.busung.s25uroot.d2;

import dev.experimental.rmgu.d2.D2ControlPolicy;
import dev.experimental.rmgu.d2.D2Protocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class D2ControlPolicyTest {
    private static void request(String method, Boolean enabled, String source, int version, long deadline, long now) {
        D2ControlPolicy.validate(D2Protocol.API, method, enabled, source, version, "current", 1, deadline, now);
    }
    @Test public void statusAndRefreshDoNotRequireASettingsValue() {
        request("status", null, "old", 0, 5000, 1000);
        request("refresh", null, "current", 1, 5000, 1000);
    }
    @Test public void enablingRequiresTheRunningSourceNotJustTheInstalledApk() {
        assertThrows(IllegalStateException.class, () -> request("configure", true, "old", 1, 5000, 1000));
        assertThrows(IllegalStateException.class, () -> request("configure", true, null, 1, 5000, 1000));
        request("configure", true, "current", 1, 5000, 1000);
    }
    @Test public void enablingRequiresTheRunningVersion() {
        assertThrows(IllegalStateException.class, () -> request("configure", true, "current", 0, 5000, 1000));
    }
    @Test public void disablingIsAllowedForAnOlderRunningHelper() {
        request("configure", false, "old", 0, 5000, 1000);
    }
    @Test public void missingSwitchValueCannotDefaultToDisabling() {
        assertThrows(IllegalArgumentException.class, () -> request("configure", null, "current", 1, 5000, 1000));
    }
    @Test public void readRequestsCannotCarryASettingsChange() {
        assertThrows(IllegalArgumentException.class, () -> request("status", true, "current", 1, 5000, 1000));
        assertThrows(IllegalArgumentException.class, () -> request("refresh", false, "current", 1, 5000, 1000));
    }
    @Test public void noOtherOperationsAreAccepted() {
        for (String method : new String[]{null, "", "activate", "shell", "write", "remove"}) {
            assertThrows(IllegalArgumentException.class, () -> request(method, null, "current", 1, 5000, 1000));
        }
    }
    @Test public void wrongProtocolIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> D2ControlPolicy.validate(
            99, "configure", true, "current", 1, "current", 1, 5000, 1000));
    }
    @Test public void queuedOrAbandonedChangesExpireBeforeDispatch() {
        assertThrows(IllegalStateException.class, () -> request("configure", true, "current", 1, 1000, 1000));
        assertThrows(IllegalStateException.class, () -> request("configure", false, "current", 1, 999, 1000));
    }
    @Test public void missingOrUnboundedDeadlineIsRejected() {
        assertThrows(IllegalStateException.class, () -> request("configure", true, "current", 1, 0, 1000));
        assertThrows(IllegalStateException.class, () -> request("configure", true, "current", 1, 9001, 1000));
        assertThrows(IllegalStateException.class, () -> request("configure", true, "current", 1, Long.MAX_VALUE, 1000));
    }
}
