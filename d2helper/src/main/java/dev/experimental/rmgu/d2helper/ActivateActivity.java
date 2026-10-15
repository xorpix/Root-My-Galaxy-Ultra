package dev.experimental.rmgu.d2helper;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import dev.experimental.rmgu.d2.D2Protocol;

/** Explicitly leaves the newly installed package's stopped state so boot delivery is possible. */
public final class ActivateActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            if (!D2Protocol.CONTROLLER.equals(getCallingPackage()) ||
                    getPackageManager().checkSignatures(D2Protocol.CONTROLLER, D2Protocol.HELPER) != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException("Activation must be requested by RMGU");
            }
            if (!D2Store.activate(this)) throw new IllegalStateException("Activation was not saved");
            D2Runtime.request(this, "activated", false, () -> {});
            setResult(RESULT_OK);
        } catch (Exception error) {
            setResult(RESULT_CANCELED);
            Log.w("RMGUD2", "Helper activation refused: " + error.getClass().getSimpleName());
        } finally { finish(); }
    }
}
