package dev.busung.s25uroot.dirtyfrag;

public interface DfReporter {
    void report(String msg);
    default boolean rootReady(long timeoutMillis) { return false; }
    default void readyWaitFinished(long elapsedMillis, boolean ready) {}
}
