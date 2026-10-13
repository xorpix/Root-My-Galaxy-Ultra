package dev.experimental.rmgu.bootprobe;

final class WallpaperSelectionState {
    private WallpaperSelectionState() {}

    static boolean lockSelected(boolean homeSelected, boolean lockMatches,
            boolean lockInfoPresent, int lockId) {
        // Android can share the home wallpaper with lock without a separate lock record.
        // A null info result alone is insufficient: a separate static/inaccessible lock still
        // has an ID, and must not be reported as the probe.
        return lockMatches || (homeSelected && !lockInfoPresent && lockId < 0);
    }
}
