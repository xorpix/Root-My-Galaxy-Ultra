package dev.experimental.rmgu.bootprobe;

import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SAVE_REPORT = 31;
    private static final java.util.concurrent.ExecutorService exports = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView selection;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.setBackgroundColor(Color.rgb(245, 247, 250));
        int pad = dp(22);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        addText(content, "RMGU Boot Probe", 27, true);
        addText(content, "Accessibility test · " + ProbeReport.VERSION, 14, false);
        addText(content, "Find out whether Android can start our service while Samsung’s original boot animation is still running. The probe records timing and checks IPsec readiness. It does not root the phone or delay boot.", 16, false);
        selection = addText(content, "Checking service…", 15, true);
        button(content, "Open accessibility settings", this::accessibilitySettings);
        addText(content, "Choose Installed apps → RMGU Boot Probe and enable it. The probe does not read your screen or control gestures.", 14, false);
        addText(content, "If Android blocks the switch, open App info below, use its ⋮ menu to allow restricted settings if offered, then return to Accessibility.", 14, false);
        button(content, "Open app info", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        addText(content, "Run the test", 19, true);
        addText(content, "1. Enable the service and return here to check its status.\n2. Turn off RMGU’s Root on boot for this test.\n3. Fully restart the phone, then leave it locked for 90 seconds.\n4. Unlock, open this app, and share the report.", 16, false);
        addText(content, "Your wallpaper stays as it is. Opening this screen does not start a test. After sharing the report, turn off the probe in Accessibility.", 14, false);
        button(content, "Refresh observations", this::refresh);
        button(content, "Share report", this::share);
        button(content, "Save report", () -> withReport(report -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "RMGU-accessibility-boot-report.txt");
            try { startActivityForResult(intent, SAVE_REPORT); }
            catch (ActivityNotFoundException error) { message("No document picker is available. Use Share report instead."); }
        }));
        addText(content, "Latest observations", 19, true);
        status = addText(content, "Loading…", 14, false);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        setContentView(scroll);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(appearance, appearance);
        }
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        refreshSelection();
        ProbeRuntime.readReport(this, report -> runOnUiThread(() -> {
            if (!isDestroyed()) status.setText(ProbeReport.summary(report));
        }));
    }

    private void refreshSelection() {
        try {
            AccessibilityManager manager = getSystemService(AccessibilityManager.class);
            ComponentName expected = new ComponentName(this, ProbeAccessibilityService.class);
            boolean enabled = false;
            String configured = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (configured != null) for (String component : configured.split(":"))
                if (expected.equals(ComponentName.unflattenFromString(component))) enabled = true;
            if (manager != null) for (AccessibilityServiceInfo info :
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getResolveInfo() == null) continue;
                ServiceInfo service = info.getResolveInfo().serviceInfo;
                if (service != null && expected.equals(new ComponentName(service.packageName, service.name)))
                    enabled = true;
            }
            selection.setText(ProbeRuntime.isAccessibilityConnected()
                    ? "Service connected — ready for a full reboot test."
                    : enabled ? "Service enabled; waiting for its connection. Tap Refresh in a moment."
                    : "Service is off — enable RMGU Boot Probe in Accessibility.");
        } catch (RuntimeException error) {
            selection.setText("Could not check service state. Check RMGU Boot Probe in Accessibility.");
        }
    }

    private void accessibilitySettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (ActivityNotFoundException | SecurityException error) {
            message("Open Settings → Accessibility → Installed apps → RMGU Boot Probe.");
        }
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
