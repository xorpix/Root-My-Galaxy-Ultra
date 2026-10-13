package dev.experimental.rmgu.bootprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public class BootEvidenceTest {
    @Test public void earlyNeedsAllThreeBootSignals() {
        assertEquals(BootEvidence.Window.EARLY, BootEvidence.classify("running", "0", "0"));
    }
    @Test public void inaccessiblePropertiesCannotProveEarlyBoot() {
        assertEquals(BootEvidence.Window.UNKNOWN, BootEvidence.classify("", "", ""));
        assertEquals(BootEvidence.Window.RUNNING_UNCERTAIN, BootEvidence.classify("running", "", ""));
    }
    @Test public void animationMayStillBeRunningAfterExitWasRequested() {
        assertEquals(BootEvidence.Window.EXITING_OR_FINISHED, BootEvidence.classify("running", "1", "0"));
    }
    @Test public void completedBootOverridesRunningSignal() {
        assertEquals(BootEvidence.Window.EXITING_OR_FINISHED, BootEvidence.classify("running", "0", "1"));
    }
    @Test public void stoppedServiceCannotQualify() {
        assertEquals(BootEvidence.Window.EXITING_OR_FINISHED, BootEvidence.classify("stopped", "0", "0"));
    }
    @Test public void wallpaperPickerPreviewCannotQualify() {
        assertFalse(BootEvidence.candidate(true, false, true, BootEvidence.Window.EARLY));
    }
    @Test public void unlockedOrUnreadyOrUncertainCannotQualify() {
        assertFalse(BootEvidence.candidate(false, true, true, BootEvidence.Window.EARLY));
        assertFalse(BootEvidence.candidate(false, false, false, BootEvidence.Window.EARLY));
        assertFalse(BootEvidence.candidate(false, false, true, BootEvidence.Window.RUNNING_UNCERTAIN));
    }
    @Test public void onlyLockedNonPreviewReadyEarlyObservationQualifies() {
        assertTrue(BootEvidence.candidate(false, false, true, BootEvidence.Window.EARLY));
    }
}
