package dev.experimental.rmgu.bootprobe;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.system.Os;
import java.lang.reflect.InvocationTargetException;

/** One-shot selection only. No boot entry point, root exploit, module, or policy modification. */
public final class WallpaperRootMain {
    private static final int SYSTEM_UID = 1000;
    private WallpaperRootMain() {}

    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException("This helper accepts no arguments");
            if (Os.getuid() != 0) throw new SecurityException("Existing root access is required");
            if (Build.VERSION.SDK_INT < 34)
                throw new UnsupportedOperationException("Root-assisted selection requires Android 14 or later");

            // Android's checked wallpaper API verifies that the calling UID owns the context's
            // package. Use the framework's system context as UID 1000 in this short-lived process.
            // Nothing is installed with this UID and the diagnostic service retains its app UID.
            Os.setgid(SYSTEM_UID);
            Os.setuid(SYSTEM_UID);
            if (Os.getuid() != SYSTEM_UID) throw new SecurityException("Cannot establish selection context");
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            WallpaperManager manager = WallpaperManager.getInstance(context);
            if (!manager.isWallpaperSupported() || !manager.isSetWallpaperAllowed())
                throw new SecurityException("Wallpaper changes are not allowed for this user");
            ComponentName component = new ComponentName("dev.experimental.rmgu.bootprobe",
                    "dev.experimental.rmgu.bootprobe.ProbeWallpaperService");
            SystemWallpaperSetter.applyBoth(manager, component);
            WallpaperInfo home = manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM);
            WallpaperInfo lock = manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
            boolean homeSelected = home != null && component.equals(home.getComponent());
            boolean lockSelected = lock != null && component.equals(lock.getComponent());
            System.out.println("Home selected: " + homeSelected + "; lock selected: " + lockSelected);
            if (!homeSelected || !lockSelected)
                throw new IllegalStateException("Selection was not confirmed on both screens. Check Wallpaper and style.");
            System.out.println("RMGU_WALLPAPER_APPLIED_BOTH");
            System.exit(0);
        } catch (Exception error) {
            Throwable cause = error;
            while (cause instanceof InvocationTargetException && cause.getCause() != null)
                cause = cause.getCause();
            System.err.println("Wallpaper activation failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            System.exit(1);
        }
    }
}
