package dev.experimental.rmgu.bootprobe;

/** Only the installed APK path is variable; the helper accepts no component or shell command. */
final class RootWallpaperCommand {
    private RootWallpaperCommand() {}

    static String forApk(String path) {
        if (path == null || !path.startsWith("/data/app/") || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Expected an installed APK path");
        return "CLASSPATH=" + quote(path)
                + " /system/bin/app_process /system/bin dev.experimental.rmgu.bootprobe.WallpaperRootMain";
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
