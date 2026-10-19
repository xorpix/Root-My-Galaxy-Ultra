package dev.busung.s25uroot;

import java.util.Arrays;

/** Presentation rules only; these do not select or authorize a root method. */
public final class SettingsPresentation {
    private SettingsPresentation() {}

    public enum SusfsState { ACTIVE, ACTIVE_UNTIL_REBOOT, PENDING, OFF }

    public static SusfsState susfsState(boolean automatic, boolean activeThisBoot) {
        if (activeThisBoot) return automatic ? SusfsState.ACTIVE : SusfsState.ACTIVE_UNTIL_REBOOT;
        return automatic ? SusfsState.PENDING : SusfsState.OFF;
    }

    /** An unavailable enable action must not prevent withdrawing an existing preference. */
    public static boolean canChangeSusfs(boolean automatic, boolean busy, boolean compatible) {
        return !busy && (automatic || compatible);
    }

    /** Offer only values the existing manual preference reader can return. */
    public static int[] manualSettleChoices(int[] offered, int minimumSeconds) {
        return Arrays.stream(offered).filter(value -> value >= minimumSeconds).toArray();
    }
}
