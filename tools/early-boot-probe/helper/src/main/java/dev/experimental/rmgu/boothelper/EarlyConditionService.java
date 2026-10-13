package dev.experimental.rmgu.boothelper;

import android.net.Uri;
import android.service.notification.ConditionProviderService;
import android.util.Log;

/** A legacy system-bound service used only to observe its startup timing. */
@SuppressWarnings("deprecation")
public final class EarlyConditionService extends ConditionProviderService {
    @Override public void onCreate() {
        super.onCreate();
        try { HelperRuntime.trigger(this,"condition_service_created"); }
        catch(Throwable error){Log.e("RMGUPrivBoot","Diagnostic initialization failed",error);}
    }
    @Override public void onConnected() {
        try { HelperRuntime.trigger(this,"condition_service_connected"); }
        catch(Throwable error){Log.e("RMGUPrivBoot","Connection observation failed",error);}
    }
    @Override public void onSubscribe(Uri conditionId) { /* No conditions or DND changes. */ }
    @Override public void onUnsubscribe(Uri conditionId) { }
}
