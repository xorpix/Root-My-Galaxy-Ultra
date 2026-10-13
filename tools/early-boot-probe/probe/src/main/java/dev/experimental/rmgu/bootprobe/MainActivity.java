package dev.experimental.rmgu.bootprobe;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SAVE_REPORT = 31;
    private static final java.util.concurrent.ExecutorService exports = Executors.newSingleThreadExecutor();
    private TextView status;

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
        addText(content, "Diagnostic prototype · 0.1.0", 14, false);
        addText(content, "Check whether Android starts our component while Samsung's boot animation is still running. This app records timing and tests IPsec readiness; it does not root or delay boot.", 16, false);
        addText(content, "1. Select the temporary test wallpaper.\n2. Fully reboot the phone.\n3. Leave it locked for about 90 seconds.\n4. Unlock, open this app, and share the report.", 16, false);
        addText(content, "The test temporarily replaces your wallpaper with a simple dark gradient. Choose your regular wallpaper again when finished. If RMGU autoroot is enabled, turn it off for this baseline test.", 14, false);
        button(content, "Select test wallpaper", () -> {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, ProbeWallpaperService.class));
            launch(intent);
        });
        button(content, "Refresh observations", this::refresh);
        button(content, "Share report", this::share);
        button(content, "Save report", () -> withReport(report -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "RMGU-early-boot-report.txt");
            try { startActivityForResult(intent, SAVE_REPORT); }
            catch (ActivityNotFoundException error) { message("No document picker is available. Use Share report instead."); }
        }));
        button(content, "Wallpaper settings", () -> launch(new Intent(Intent.ACTION_SET_WALLPAPER)));
        addText(content, "Latest observations", 19, true);
        status = addText(content, "Loading…", 14, false);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        ProbeRuntime.readReport(this, report -> runOnUiThread(() -> {
            if (!isDestroyed()) status.setText(ProbeReport.summary(report));
        }));
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
                Files.writeString(new File(dir, name).toPath(), report, StandardCharsets.UTF_8);
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

    private void button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        parent.addView(button, params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void launch(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException error) { message("This action is unavailable on this phone."); }
    }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
