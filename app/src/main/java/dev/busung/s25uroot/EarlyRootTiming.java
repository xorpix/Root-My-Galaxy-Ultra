package dev.busung.s25uroot;

/** A negative storage-unlock reading alone does not establish that the first PIN was not entered. */
final class EarlyRootTiming {
    static final String BEFORE = "before_unlock";
    static final String AFTER = "after_unlock";
    static final String UNKNOWN = "unknown";

    static String classify(String event, Boolean helperRequestUnlocked, Boolean userUnlocked, Boolean deviceLocked) {
        if ("android.intent.action.BOOT_COMPLETED".equals(event) ||
                Boolean.TRUE.equals(helperRequestUnlocked) || Boolean.TRUE.equals(userUnlocked) ||
                Boolean.FALSE.equals(deviceLocked)) return AFTER;
        if ("android.intent.action.LOCKED_BOOT_COMPLETED".equals(event) &&
                Boolean.FALSE.equals(helperRequestUnlocked) && Boolean.FALSE.equals(userUnlocked) &&
                Boolean.TRUE.equals(deviceLocked)) return BEFORE;
        return UNKNOWN;
    }
}
