package com.smart.android.adsdk.universal.internal;

import android.content.Context;
import android.os.Build;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import java.util.concurrent.atomic.AtomicBoolean;

/** CMP adapter for the current no-UMP phase; no SDK consent success is synthesized. */
final class GoogleConsentResolver implements ConsentResolver {
    private final TclCmpBackendClient backend;
    GoogleConsentResolver(Context context, OkHttpClient http, Gson gson, String base) {
        backend = TclCmpBackendClient.forGoogle(http, gson, base, () -> DeviceInfo.collect(context), () -> Build.VERSION.SDK_INT);
    }
    GoogleConsentResolver(TclCmpBackendClient backend) { this.backend = backend; }
    @Override public Cancellable resolve(Context context, String provider, Callback callback) {
        AtomicBoolean cancelled = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Cancellable> active = new java.util.concurrent.atomic.AtomicReference<>();
        Cancellable request = backend.request(provider, true, (decision, error) -> {
            if (cancelled.get()) return;
            if (error != null) { callback.onError(error); return; }
            String action = decision == null ? null : decision.action;
            if (!"MAYBE_LATER".equals(action)) {
                callback.onTrace("CMP_DECISION_FAIL", "action=" + action + ",reason=ump_unavailable,sdkActionExecuted=false");
                callback.onBlocked("SKIP_ALREADY_DECIDED".equals(action) ? "CONSENT_STATE_UNAVAILABLE"
                    : ("ACCEPT_ALL".equals(action) || "SAVE_SETTINGS".equals(action) || "REJECT".equals(action))
                        ? "UMP_UNAVAILABLE" : "DECISION_UNAVAILABLE"); return;
            }
            callback.onTrace("CMP_DECISION_SKIPPED", "action=MAYBE_LATER,sdkActionExecuted=false");
            Cancellable report = backend.report(provider, action, failure -> { if (!cancelled.get()) callback.onAllowed(); });
            active.set(report); if (cancelled.get()) report.cancel();
        });
        active.compareAndSet(null, request);
        return () -> { cancelled.set(true); request.cancel(); Cancellable value = active.get(); if (value != null) value.cancel(); };
    }
}
