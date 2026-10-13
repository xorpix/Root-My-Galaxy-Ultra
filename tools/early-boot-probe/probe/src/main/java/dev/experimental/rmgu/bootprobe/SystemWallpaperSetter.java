package dev.experimental.rmgu.bootprobe;

import android.content.ComponentName;
import java.lang.reflect.Method;

/** Called only from the explicit helper process, never from the boot receiver or wallpaper. */
final class SystemWallpaperSetter {
    private SystemWallpaperSetter() {}

    static void applyBoth(Object manager, ComponentName component) throws ReflectiveOperationException {
        Method setter;
        Object[] arguments;
        try {
            setter = manager.getClass().getMethod("setWallpaperComponentWithFlags",
                    ComponentName.class, int.class, int.class);
            arguments = new Object[] {component, 3, 0}; // Home | lock, primary user.
        } catch (NoSuchMethodException missing) {
            setter = manager.getClass().getMethod("setWallpaperComponentWithFlags",
                    ComponentName.class, int.class);
            arguments = new Object[] {component, 3};
        }
        // A permission/policy failure is never retried through an older API.
        Object result = setter.invoke(manager, arguments);
        if (Boolean.FALSE.equals(result)) throw new IllegalStateException("Wallpaper manager rejected the selection");
    }
}
