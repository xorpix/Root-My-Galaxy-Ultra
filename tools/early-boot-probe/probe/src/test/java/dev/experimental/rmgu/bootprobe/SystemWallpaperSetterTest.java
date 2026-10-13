package dev.experimental.rmgu.bootprobe;

import android.content.ComponentName;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemWallpaperSetterTest {
    public static class ModernManager {
        int which, user, oldCalls;
        public boolean setWallpaperComponentWithFlags(ComponentName component, int flags, int userId) {
            which = flags; user = userId; return true;
        }
        public boolean setWallpaperComponentWithFlags(ComponentName component, int flags) {
            oldCalls++; return true;
        }
    }
    public static class TwoArgumentManager {
        int which;
        public boolean setWallpaperComponentWithFlags(ComponentName component, int flags) {
            which = flags; return true;
        }
    }
    public static class DeniedManager extends ModernManager {
        @Override public boolean setWallpaperComponentWithFlags(ComponentName component, int flags, int userId) {
            throw new SecurityException("Policy denied");
        }
    }
    public static class RejectedManager extends ModernManager {
        @Override public boolean setWallpaperComponentWithFlags(ComponentName component, int flags, int userId) {
            return false;
        }
    }

    @Test public void setsBothScreensForPrimaryUser() throws Exception {
        ModernManager manager = new ModernManager();
        SystemWallpaperSetter.applyBoth(manager, null);
        assertEquals(3, manager.which);
        assertEquals(0, manager.user);
        assertEquals(0, manager.oldCalls);
    }
    @Test public void supportsMissingUserOverload() throws Exception {
        TwoArgumentManager manager = new TwoArgumentManager();
        SystemWallpaperSetter.applyBoth(manager, null);
        assertEquals(3, manager.which);
    }
    @Test public void policyFailureDoesNotUseFallback() throws Exception {
        DeniedManager manager = new DeniedManager();
        try {
            SystemWallpaperSetter.applyBoth(manager, null);
            fail("Denied selection must fail");
        } catch (InvocationTargetException error) {
            assertTrue(error.getCause() instanceof SecurityException);
        }
        assertEquals(0, manager.oldCalls);
    }
    @Test public void falseResultDoesNotBecomeSuccess() throws Exception {
        RejectedManager manager = new RejectedManager();
        try {
            SystemWallpaperSetter.applyBoth(manager, null);
            fail("Rejected selection must fail");
        } catch (IllegalStateException expected) {
            assertEquals(0, manager.oldCalls);
        }
    }
}
