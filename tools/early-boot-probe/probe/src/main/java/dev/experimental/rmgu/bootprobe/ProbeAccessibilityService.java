package dev.experimental.rmgu.bootprobe;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** The user enables this service in Settings. It subscribes to no accessibility events. */
public final class ProbeAccessibilityService extends AccessibilityService {
    @Override public void onCreate() {
        super.onCreate();
        ProbeRuntime.event(this, "accessibility_service_created");
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        ProbeRuntime.accessibilityConnection(this, true, "accessibility_service_connected");
    }

    @Override public boolean onUnbind(Intent intent) {
        ProbeRuntime.accessibilityConnection(this, false, "accessibility_service_unbound");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        ProbeRuntime.accessibilityConnection(this, false, "accessibility_service_destroyed");
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // No event subscriptions and no inspection of event, text, package, or window content.
    }

    @Override public void onInterrupt() {
        // No spoken feedback or UI interaction to interrupt.
    }
}
