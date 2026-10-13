package dev.experimental.rmgu.bootprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.view.WindowInsets;
import android.widget.*;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int SAVE_REPORT = 31;
    private static final java.util.concurrent.ExecutorService exports = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView selection;
    private Button rootApply;
    private boolean applying;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.rgb(245, 247, 250));
        int pad = dp(22);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        addText(content, "RMGU Boot Probe", 27, true);
        addText(content, "Diagnostic prototype · 0.1.1", 14, false);
        addText(content, "Check whether Android starts our component while Samsung's boot animation is still running. This app records timing and tests IPsec readiness; it does not root or delay boot.", 16, false);
        addText(content, "1. Select the temporary test wallpaper.\n2. Fully reboot the phone.\n3. Leave it locked for about 90 seconds.\n4. Unlock, open this app, and share the report.", 16, false);
        addText(content, "The test temporarily replaces your wallpaper with a simple dark gradient. Choose your regular wallpaper again when finished. If RMGU autoroot is enabled, turn it off for this baseline test.", 14, false);
        addText(content, "If Samsung's wallpaper picker is unavailable, use Apply with existing root. Run root in RMGU first and allow RMGU Boot Probe in your root manager. This is only needed once to select the wallpaper.", 14, false);
        button(content, "Select test wallpaper", this::selectWallpaper);
        rootApply = button(content, "Apply with existing root", this::confirmRootApply);
        selection = addText(content, "Checking wallpaper selection…", 14, false);
        selection.setTextIsSelectable(true);
        button(content, "Refresh observations", this::refresh);
        button(content, "Share report", this::share);
        button(content, "Save report", () -> withReport(report -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "RMGU-early-boot-report.txt");
            try { startActivityForResult(intent, SAVE_REPORT); }
            catch (ActivityNotFoundException error) { message("No document picker is available. Use Share report instead."); }
        }));
        button(content, "Wallpaper settings", this::wallpaperSettings);
        addText(content, "Latest observations", 19, true);
        status = addText(content, "Loading…", 14, false);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        refreshSelection();
        ProbeRuntime.readReport(this, report -> runOnUiThread(() -> {
            if (!isDestroyed()) status.setText(ProbeReport.summary(report));
        }));
    }

    private ComponentName probeComponent() {
        return new ComponentName(this, ProbeWallpaperService.class);
    }

    private void refreshSelection() {
        if (applying) return;
        try {
            WallpaperManager manager = WallpaperManager.getInstance(this);
            WallpaperInfo home = manager.getWallpaperInfo();
            boolean homeSelected = home != null && probeComponent().equals(home.getComponent());
            String text = "Home: " + (homeSelected ? "test wallpaper selected" : "test wallpaper not selected");
            if (Build.VERSION.SDK_INT >= 34) {
                WallpaperInfo lock = manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
                boolean lockSelected = lock != null && probeComponent().equals(lock.getComponent());
                text += "\nLock: " + (lockSelected ? "test wallpaper selected" : "test wallpaper not selected");
            }
            selection.setText(text);
        } catch (RuntimeException error) {
            selection.setText("Could not check wallpaper selection: " + error.getClass().getSimpleName());
        }
    }

    private boolean tryLaunch(Intent intent) {
        try { startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException error) { return false; }
    }

    private void selectWallpaper() {
        if (tryLaunch(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, probeComponent()))) return;
        if (tryLaunch(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))) {
            message("Choose RMGU Boot Probe — temporary wallpaper, then apply it to both screens.");
            return;
        }
        confirmRootApply();
    }

    private void wallpaperSettings() {
        if (tryLaunch(new Intent(Intent.ACTION_SET_WALLPAPER))) return;
        if (tryLaunch(new Intent(Settings.ACTION_SETTINGS))) {
            message("Open Wallpaper and style to choose your regular wallpaper.");
            return;
        }
        message("Open Wallpaper and style from the phone's Settings app.");
    }

    private void confirmRootApply() {
        if (applying) return;
        new AlertDialog.Builder(this).setTitle("Apply test wallpaper using existing root?")
                .setMessage("This temporarily replaces both your home and lock wallpapers with the test gradient. Run root in RMGU first, then allow RMGU Boot Probe in your root manager. The helper uses root only to select the wallpaper and then exits. After a full reboot, the timing test runs without root. Choose your regular wallpaper in Settings when finished.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> applyWithRoot()).show();
    }

    private void applyWithRoot() {
        if (Process.myUid() / 100_000 != 0) {
            message("The root selection helper supports the phone's primary user only. Use the system picker for other users.");
            return;
        }
        applying = true;
        rootApply.setEnabled(false);
        selection.setText("Applying test wallpaper… Allow RMGU Boot Probe in your root manager if prompted.");
        final String apkPath = getApplicationInfo().sourceDir;
        final File cache = createDeviceProtectedStorageContext().getCacheDir();
        exports.execute(() -> {
            java.lang.Process child = null;
            File log = null;
            boolean success = false;
            String detail;
            try {
                log = File.createTempFile("wallpaper-activation-", ".txt", cache);
                child = new ProcessBuilder("su", "-c", RootWallpaperCommand.forApk(apkPath))
                        .redirectErrorStream(true).redirectOutput(log).start();
                if (!child.waitFor(60, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    throw new IllegalStateException("Root approval or wallpaper activation timed out. Check the root manager.");
                }
                detail = TextFiles.read(log);
                success = child.exitValue() == 0 && detail.contains("RMGU_WALLPAPER_APPLIED_BOTH");
                if (!success && detail.isBlank()) detail = "Root access was denied or unavailable. Run root in RMGU and allow this app in your root manager.";
            } catch (Exception error) {
                detail = error.getClass().getSimpleName() + ": " + error.getMessage()
                        + "\nRun root in RMGU and allow RMGU Boot Probe in your root manager.";
            } finally {
                if (child != null && child.isAlive()) child.destroyForcibly();
                if (log != null) log.delete();
            }
            final boolean applied = success;
            final String output = detail.length() > 8000 ? detail.substring(0, 8000) : detail;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                applying = false;
                rootApply.setEnabled(true);
                refreshSelection();
                if (applied) message("Both test wallpapers are selected. Turn RMGU autoroot off, fully reboot, and leave the phone locked for 90 seconds.");
                else new AlertDialog.Builder(this).setTitle("Wallpaper was not verified on both screens")
                        .setMessage(output).setPositiveButton("OK", null)
                        .setNeutralButton("Copy details", (dialog, which) -> {
                            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                            clipboard.setPrimaryClip(ClipData.newPlainText("Wallpaper activation", output));
                        }).show();
            });
        });
    }

    private void withReport(java.util.function.Consumer<String> consumer) {
        ProbeRuntime.readReport(this, report -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            status.setText(ProbeReport.summary(report));
            if (!report.contains("--- RAW OBSERVATIONS")) { message("There is no report to export yet."); return; }
            consumer.accept(report);
        }));
    }

    private void share() {
        withReport(report -> exports.execute(() -> {
            try {
                File dir = new File(createDeviceProtectedStorageContext().getCacheDir(), "reports");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create export");
                File[] old = dir.listFiles();
                if (old != null) for (File file : old)
                    if (file.lastModified() < System.currentTimeMillis() - 86_400_000L) file.delete();
                String name = "report-" + UUID.randomUUID() + ".txt";
                TextFiles.write(new File(dir, name), report);
                Uri uri = new Uri.Builder().scheme("content").authority(ReportProvider.AUTHORITY)
                        .appendPath("reports").appendPath(name).build();
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newUri(getContentResolver(), "Boot diagnostic report", uri));
                runOnUiThread(() -> launch(Intent.createChooser(send, "Share boot report")));
            } catch (Exception error) { runOnUiThread(() -> message("Export failed: " + error.getMessage())); }
        }));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_REPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        // Re-read after the picker returns; activity recreation cannot lose the report body.
        ProbeRuntime.readReport(this, report -> exports.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Cannot open destination");
                out.write(report.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> message("Report saved."));
            } catch (Exception error) { runOnUiThread(() -> message("Save failed: " + error.getMessage())); }
        }));
    }

    private TextView addText(LinearLayout parent, String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(23, 38, 53));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(15);
        parent.addView(view, params);
        return view;
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(button, params);
        return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void launch(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException error) { message("This action is unavailable on this phone."); }
    }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
