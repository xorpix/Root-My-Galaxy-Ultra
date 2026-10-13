package dev.experimental.rmgu.bootprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public class WallpaperSelectionStateTest {
    @Test public void sharedHomeWallpaperIsRecognizedOnLock() {
        assertTrue(WallpaperSelectionState.lockSelected(true, false, false, -1));
    }
    @Test public void staticLockWallpaperIsNotMistakenForInheritedHome() {
        assertFalse(WallpaperSelectionState.lockSelected(true, false, false, 123));
    }
    @Test public void unrelatedLiveLockWallpaperIsNotMistakenForProbe() {
        assertFalse(WallpaperSelectionState.lockSelected(true, false, true, 123));
    }
    @Test public void absentLockDoesNotQualifyWithoutProbeOnHome() {
        assertFalse(WallpaperSelectionState.lockSelected(false, false, false, -1));
    }
    @Test public void independentLockProbeQualifies() {
        assertTrue(WallpaperSelectionState.lockSelected(false, true, true, 123));
    }
}
