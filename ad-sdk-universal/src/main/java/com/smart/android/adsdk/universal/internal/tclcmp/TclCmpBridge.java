package com.smart.android.adsdk.universal.internal.tclcmp;

import android.content.Context;

/** Internal adapters supplied by the fusion runtime; hosts do not configure TCL CMP. */
public interface TclCmpBridge {
    /** Receives the original native CMP events; data is optional structured diagnostic JSON. */
    void trace(String eventType, String message, String data);

    /** Requests the backend decision only after native CMP determines a decision is needed. */
    void requestDecision(Context context, boolean consentExpired, DecisionCallback callback);

    /** Null error means acknowledged; any error keeps the persisted report queued for retry. */
    void reportConsent(String consentAction, ReportCallback callback);

    /** Persistent retries retain the channel that originally produced the consent result. */
    default String getChannelId() { return ""; }
    default void reportConsent(String channelId, String consentAction, ReportCallback callback) {
        if (!channelId.isEmpty() && !channelId.equals(getChannelId())) callback.onResult("Consent channel changed");
        else reportConsent(consentAction, callback);
    }

    /** Optional SDK-owned override; null preserves the main app's Android ID default. */
    default String getDeviceId(Context context) { return null; }

    /** Raw local logs are opt-in, matching the main application's debug-only behavior. */
    default boolean isDebugLoggingEnabled() { return false; }

    interface DecisionCallback {
        void onDecision(TclCmpManager.RemoteCmpDecision decision);
    }

    interface ReportCallback {
        void onResult(String error);
    }
}
