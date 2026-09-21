package com.smart.android.adsdk.internal;

import android.content.Context;
import android.os.Build;
import com.google.gson.Gson;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import com.smart.android.adsdk.internal.tclcmp.TclCmpBridge;
import com.smart.android.adsdk.internal.tclcmp.TclCmpManager;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;

/** Adapts the main application's complete TCL CMP flow to one SDK channel session. */
final class TclConsentResolver implements ConsentResolver {
    private static final String TAG = "AdSdkTclCmp";
    private final TclCmpBackendClient backend;
    private final Manager manager;
    private final CallbackDispatcher dispatcher;
    private TclCmpBridge startupBridge;

    interface Manager {
        void initialize(Context context, TclCmpBridge bridge);
        void whenReady(Runnable callback);
        void applyDecision(Context context, Runnable callback);
        boolean canContinue();
        String consentString();
        default void restoreBridge(TclCmpBridge bridge) {}
    }

    TclConsentResolver(Context context, OkHttpClient http, Gson gson, String apiBaseUrl,
                       CallbackDispatcher dispatcher) {
        this(new TclCmpBackendClient(http, gson, apiBaseUrl,
            () -> DeviceInfo.collect(context), () -> Build.VERSION.SDK_INT), new NativeManager(), dispatcher);
    }

    TclConsentResolver(TclCmpBackendClient backend, Manager manager, CallbackDispatcher dispatcher) {
        this.backend = backend;
        this.manager = manager;
        this.dispatcher = dispatcher;
    }

    /** The main app initializes native CMP at startup, including when a later flow skips CMP. */
    void initialize(Context context, String channelId) {
        startupBridge = new Operation(context, channelId, new Callback() {
            @Override public void onAllowed() {}
            @Override public void onBlocked(String reason) {}
            @Override public void onError(AdError error) {}
        });
        manager.initialize(context, startupBridge);
    }

    @Override public Cancellable resolve(Context context, String channelId, Callback callback) {
        Operation operation = new Operation(context, channelId, callback);
        dispatcher.dispatch(operation::start);
        return operation::cancel;
    }

    private final class Operation implements TclCmpBridge {
        private final Context context;
        private final String channel;
        private final Callback callback;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final CopyOnWriteArrayList<Cancellable> calls = new CopyOnWriteArrayList<>();

        Operation(Context context, String channel, Callback callback) {
            this.context = context;
            this.channel = channel;
            this.callback = callback;
        }

        void start() {
            if (cancelled.get()) return;
            try {
                trace("CMP_PROVIDER", "provider=TCL channel=" + channel, null);
                manager.initialize(context, this);
                manager.whenReady(() -> dispatcher.dispatch(() -> {
                    if (cancelled.get() || completed.get()) return;
                    try {
                        manager.applyDecision(context, () -> dispatcher.dispatch(this::finish));
                    } catch (RuntimeException | LinkageError error) { fail(error); }
                }));
            } catch (RuntimeException | LinkageError error) { fail(error); }
        }

        void finish() {
            if (cancelled.get() || !completed.compareAndSet(false, true)) return;
            String consent = manager.consentString();
            trace("CMP_GATE_FINISH", "provider=TCL consentLength="
                + (consent == null ? 0 : consent.length()), null);
            if (manager.canContinue()) callback.onAllowed();
            else callback.onBlocked("cmp_decision_blocked");
        }

        void fail(Throwable error) {
            if (cancelled.get() || !completed.compareAndSet(false, true)) return;
            SdkLog.e(TAG, "CMP failed channel=" + channel, error);
            callback.onError(AdErrors.from(AdErrorCode.INTERNAL_ERROR, AdErrorStage.INITIALIZATION, error, null));
        }

        @Override public void requestDecision(Context ignored, boolean consentExpired,
                                               DecisionCallback listener) {
            if (cancelled.get()) return;
            trace("POPUP_REQUEST_START", "consent_expired=" + consentExpired, null);
            track(backend.request(channel, consentExpired, (decision, error) -> dispatcher.dispatch(() -> {
                if (cancelled.get()) return;
                if (error != null) {
                    traceError("POPUP_REQUEST_FAIL", error);
                    fallback(listener, "request_error:" + error.getMessage());
                    return;
                }
                String action = decision == null ? null : decision.action;
                trace("POPUP_REQUEST_SUCCESS", "action=" + action, null);
                if ("SAVE_SETTINGS".equals(action)) {
                    TclCmpBackendClient.Payload payload = decision.payload;
                    if (payload == null) {
                        trace("POPUP_ACTION_INVALID", "action=SAVE_SETTINGS,reason=payload_missing", null);
                        fallback(listener, "payload_missing");
                        return;
                    }
                    trace("POPUP_ACTION_SAVE_SETTINGS", "purpose=" + payload.purposeConsentIds.size()
                        + ",vendor=" + payload.vendorConsentIds.size(), null);
                    listener.onDecision(new TclCmpManager.RemoteCmpDecision(action,
                        new TclCmpManager.SaveSettingsPayload(payload.purposeConsentIds,
                            payload.purposeLiIds, payload.customPurposeConsentIds,
                            payload.customPurposeLiIds, payload.specialFeatureIds,
                            payload.vendorConsentIds, payload.vendorLiIds)));
                } else if ("ACCEPT_ALL".equals(action) || "REJECT".equals(action)
                    || "MAYBE_LATER".equals(action) || "SKIP_ALREADY_DECIDED".equals(action)) {
                    trace("POPUP_ACTION_" + action, "payload=false", null);
                    listener.onDecision(new TclCmpManager.RemoteCmpDecision(action));
                } else {
                    trace("POPUP_ACTION_UNKNOWN", String.valueOf(action), null);
                    fallback(listener, "unknown_action:" + action);
                }
            })));
        }

        private void fallback(DecisionCallback listener, String reason) {
            // Matches AdConfigManager.POPUP_FALLBACK_ACTION in the main application.
            trace("POPUP_ACTION_FALLBACK", "fallback=MAYBE_LATER,reason=" + reason, null);
            listener.onDecision(new TclCmpManager.RemoteCmpDecision("MAYBE_LATER"));
        }

        @Override public void reportConsent(String action, ReportCallback listener) {
            if (cancelled.get()) {
                listener.onResult("Canceled"); // Keep the native manager's persisted retry entry.
                return;
            }
            track(backend.report(channel, action, error -> dispatcher.dispatch(() -> {
                if (cancelled.get()) {
                    listener.onResult("Canceled");
                } else if (error != null) {
                    traceError("CONSENT_REPORT_FAIL", error);
                    listener.onResult(error.getMessage());
                } else {
                    listener.onResult(null);
                }
            })));
        }

        @Override public void trace(String eventType, String message, String data) {
            if (cancelled.get()) return;
            String detail = "channel=" + channel + " " + message;
            SdkLog.i(TAG, eventType + " " + detail);
            if (data != null && !data.isEmpty()) SdkLog.i(TAG, eventType + " data=" + data);
            callback.onTrace(eventType, detail, data);
        }

        private void traceError(String eventType, AdError error) {
            trace(eventType, "source=" + error.getSource() + ",code=" + error.getOriginalCode()
                + ",message=" + error.getMessage(), error.getResponseBody());
        }

        @Override public boolean isDebugLoggingEnabled() { return SdkLog.isEnabled(); }

        private void track(Cancellable call) {
            if (call == null) return;
            calls.add(call);
            if (cancelled.get()) { calls.remove(call); call.cancel(); }
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            for (Cancellable call : calls) call.cancel();
            calls.clear();
            // Native state synchronization can finish after the ad session. Its persisted
            // reports must retain a live adapter, without advancing the cancelled session.
            if (startupBridge != null) manager.restoreBridge(startupBridge);
        }
    }

    private static final class NativeManager implements Manager {
        @Override public void initialize(Context context, TclCmpBridge bridge) {
            TclCmpManager.configure(bridge);
            TclCmpManager.initialize(context);
        }
        @Override public void whenReady(Runnable callback) {
            TclCmpManager.runWhenReady(1500L, callback);
        }
        @Override public void applyDecision(Context context, Runnable callback) {
            TclCmpManager.applyRemoteDecisionIfNeeded(context, callback);
        }
        @Override public boolean canContinue() { return TclCmpManager.canContinueAfterRemoteDecision(); }
        @Override public String consentString() { return TclCmpManager.getConsentString(); }
        @Override public void restoreBridge(TclCmpBridge bridge) { TclCmpManager.configure(bridge); }
    }
}
