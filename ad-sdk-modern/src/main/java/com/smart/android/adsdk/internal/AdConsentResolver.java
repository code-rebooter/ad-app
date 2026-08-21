package com.smart.android.adsdk.internal;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class AdConsentResolver implements ConsentResolver {
    private static final String TAG = "AdConsentResolver";
    private static final String ACTION_ACCEPT_ALL = "ACCEPT_ALL";
    private static final String ACTION_REJECT = "REJECT";
    private static final String ACTION_SAVE_SETTINGS = "SAVE_SETTINGS";
    private static final String ACTION_MAYBE_LATER = "MAYBE_LATER";
    private static final String ACTION_SKIP_ALREADY_DECIDED = "SKIP_ALREADY_DECIDED";
    private static final int SUCCESS_CODE = 100_000;
    private static final int HTTP_STYLE_SUCCESS_CODE = 200;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final CmpDecisionClient decisionClient;

    AdConsentResolver(
        OkHttpClient okHttpClient,
        Gson gson,
        String consentPopupUrl,
        String consentReportUrl
    ) {
        decisionClient = new CmpDecisionClient(okHttpClient, gson, consentPopupUrl, consentReportUrl);
    }

    @Override
    public Cancellable resolve(Context context, String channelId, ConsentResolver.Callback callback) {
        Context appContext = applicationContext(context);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Cancellable> activeNetworkCall = new AtomicReference<>();

        if (SystemUidStorageCompat.isSystemUid()) {
            Log.w(TAG, "System uid detected, skip UMP consent flow for customer compatibility validation");
            callback.onAllowed();
            return () -> {};
        }

        NetworkCallTracker networkCallTracker = call -> {
            if (cancelled.get()) {
                call.cancel();
                return;
            }
            Cancellable previousCall = activeNetworkCall.getAndSet(call);
            if (previousCall != null) {
                previousCall.cancel();
            }
            if (cancelled.get()) {
                Cancellable activeCall = activeNetworkCall.getAndSet(null);
                if (activeCall != null) {
                    activeCall.cancel();
                }
            }
        };

        Completion completion = action -> {
            Cancellable activeCall = activeNetworkCall.getAndSet(null);
            if (activeCall != null) {
                activeCall.cancel();
            }
            if (!cancelled.get() && completed.compareAndSet(false, true)) {
                action.run();
            }
        };

        try {
            AdConsentManager.requestConsent(
                appContext,
                AdConsentManager.ConsentAction.CHECK_ONLY,
                result -> {
                    if (cancelled.get() || completed.get()) {
                        return;
                    }
                    if (result.canRequestAds) {
                        completion.complete(callback::onAllowed);
                        return;
                    }
                    if (!result.canRequestAds && !result.formAvailable) {
                        completion.complete(() -> callback.onBlocked(
                            result.errorMessage == null ? "UMP_CONSENT_FORM_UNAVAILABLE" : result.errorMessage
                        ));
                        return;
                    }
                    requestRemoteDecision(
                        appContext,
                        channelId,
                        result,
                        cancelled,
                        completed,
                        networkCallTracker,
                        completion,
                        callback
                    );
                }
            );
        } catch (RuntimeException error) {
            completion.complete(() -> callback.onError(new AdError(
                AdErrorCode.INTERNAL_ERROR,
                AdErrorStage.INTERNAL,
                "Unable to start silent UMP consent flow",
                error
            )));
        }

        return () -> {
            cancelled.set(true);
            Cancellable activeCall = activeNetworkCall.getAndSet(null);
            if (activeCall != null) {
                activeCall.cancel();
            }
        };
    }

    private void requestRemoteDecision(
        Context context,
        String channelId,
        AdConsentManager.Result initialResult,
        AtomicBoolean cancelled,
        AtomicBoolean completed,
        NetworkCallTracker networkCallTracker,
        Completion completion,
        ConsentResolver.Callback callback
    ) {
        Cancellable call = decisionClient.requestDecision(
            context,
            channelId,
            isBlank(AdConsentManager.getConsentString(context)),
            (decision, error) -> {
                if (cancelled.get() || completed.get()) {
                    return;
                }
                if (error != null || isBlank(decision)) {
                    Log.w(TAG, "CMP decision unavailable, error=" + valueOrEmpty(error));
                    if (initialResult.canRequestAds) {
                        completion.complete(callback::onAllowed);
                    } else {
                        completion.complete(() -> callback.onBlocked("CMP_DECISION_UNAVAILABLE"));
                    }
                    return;
                }
                applyDecision(initialResult, decision, context, channelId, networkCallTracker, completion, callback);
            }
        );
        networkCallTracker.track(call);
    }

    private void applyDecision(
        AdConsentManager.Result initialResult,
        String decision,
        Context context,
        String channelId,
        NetworkCallTracker networkCallTracker,
        Completion completion,
        ConsentResolver.Callback callback
    ) {
        String normalizedDecision = decision.trim().toUpperCase(Locale.US);
        switch (normalizedDecision) {
            case ACTION_ACCEPT_ALL:
            case ACTION_SAVE_SETTINGS:
                requestUmpAction(
                    context,
                    channelId,
                    AdConsentManager.ConsentAction.ACCEPT_ALL,
                    normalizedDecision,
                    networkCallTracker,
                    completion,
                    callback
                );
                break;
            case ACTION_REJECT:
                requestUmpAction(
                    context,
                    channelId,
                    AdConsentManager.ConsentAction.REJECT,
                    normalizedDecision,
                    networkCallTracker,
                    completion,
                    callback
                );
                break;
            case ACTION_MAYBE_LATER:
                if (initialResult.canRequestAds) {
                    completion.complete(callback::onAllowed);
                } else {
                    completion.complete(() -> callback.onBlocked("UMP_CONSENT_DEFERRED_BY_REMOTE_DECISION"));
                }
                break;
            case ACTION_SKIP_ALREADY_DECIDED:
                if (initialResult.canRequestAds) {
                    completion.complete(callback::onAllowed);
                } else {
                    completion.complete(() -> callback.onBlocked("UMP_CONSENT_NOT_READY"));
                }
                break;
            default:
                if (initialResult.canRequestAds) {
                    completion.complete(callback::onAllowed);
                } else {
                    completion.complete(() -> callback.onBlocked("UNKNOWN_CMP_DECISION"));
                }
                break;
        }
    }

    private void requestUmpAction(
        Context context,
        String channelId,
        AdConsentManager.ConsentAction action,
        String reportAction,
        NetworkCallTracker networkCallTracker,
        Completion completion,
        ConsentResolver.Callback callback
    ) {
        AdConsentManager.requestConsent(
            context,
            action,
            result -> {
                if (result.canRequestAds) {
                    if (isBlank(result.errorMessage) && isReportableAction(reportAction)) {
                        Cancellable reportCall = decisionClient.reportConsentResult(
                            context,
                            channelId,
                            reportAction,
                            error -> completion.complete(callback::onAllowed)
                        );
                        networkCallTracker.track(reportCall);
                    } else {
                        completion.complete(callback::onAllowed);
                    }
                } else {
                    completion.complete(() -> callback.onBlocked(
                        result.errorMessage == null ? "UMP_DID_NOT_ALLOW_AD_REQUEST" : result.errorMessage
                    ));
                }
            }
        );
    }

    private static boolean isReportableAction(String action) {
        return ACTION_ACCEPT_ALL.equals(action)
            || ACTION_REJECT.equals(action)
            || ACTION_SAVE_SETTINGS.equals(action);
    }

    private static Context applicationContext(Context context) {
        Context appContext = context.getApplicationContext();
        return appContext == null ? context : appContext;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private interface NetworkCallTracker {
        void track(Cancellable call);
    }

    private interface Completion {
        void complete(Runnable action);
    }

    private interface DecisionCallback {
        void onResult(String decision, String error);
    }

    private interface ReportCallback {
        void onResult(String error);
    }

    private static final class CmpDecisionClient {
        private final OkHttpClient okHttpClient;
        private final Gson gson;
        private final String consentPopupUrl;
        private final String consentReportUrl;

        CmpDecisionClient(
            OkHttpClient okHttpClient,
            Gson gson,
            String consentPopupUrl,
            String consentReportUrl
        ) {
            this.okHttpClient = okHttpClient;
            this.gson = gson;
            this.consentPopupUrl = consentPopupUrl;
            this.consentReportUrl = consentReportUrl;
        }

        Cancellable requestDecision(
            Context context,
            String channelId,
            boolean consentExpired,
            DecisionCallback onResult
        ) {
            DeviceInfo deviceInfo = DeviceInfo.collect(context);
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("channel_id", channelId);
            requestBody.put("mac", safeMac(deviceInfo));
            requestBody.put("ad_version", deviceInfo.versionCode);
            requestBody.put("consent_expired", consentExpired);
            Request request = new Request.Builder()
                .url(consentPopupUrl)
                .post(RequestBody.create(JSON, gson.toJson(requestBody)))
                .build();
            Call call = okHttpClient.newCall(request);
            call.enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException error) {
                    onResult.onResult(null, error.getMessage() == null ? "network error" : error.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closeableResponse = response) {
                        if (!closeableResponse.isSuccessful()) {
                            onResult.onResult(null, "HTTP " + closeableResponse.code());
                            return;
                        }
                        ResponseBody responseBody = closeableResponse.body();
                        String body = responseBody == null ? "" : responseBody.string();
                        try {
                            onResult.onResult(parseDecisionAction(body), null);
                        } catch (RuntimeException error) {
                            onResult.onResult(null, error.getMessage() == null ? "parse error" : error.getMessage());
                        }
                    } catch (IOException error) {
                        onResult.onResult(null, error.getMessage() == null ? "network error" : error.getMessage());
                    }
                }
            });
            return call::cancel;
        }

        Cancellable reportConsentResult(
            Context context,
            String channelId,
            String consentAction,
            ReportCallback onResult
        ) {
            DeviceInfo deviceInfo = DeviceInfo.collect(context);
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("channel_id", channelId);
            requestBody.put("mac", safeMac(deviceInfo));
            requestBody.put("ad_version", deviceInfo.versionCode);
            requestBody.put("android_sdk_version", Build.VERSION.SDK_INT);
            requestBody.put("consent_action", consentAction);
            Request request = new Request.Builder()
                .url(consentReportUrl)
                .post(RequestBody.create(JSON, gson.toJson(requestBody)))
                .build();
            Call call = okHttpClient.newCall(request);
            call.enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException error) {
                    onResult.onResult(error.getMessage() == null ? "network error" : error.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closeableResponse = response) {
                        if (!closeableResponse.isSuccessful()) {
                            onResult.onResult("HTTP " + closeableResponse.code());
                            return;
                        }
                        onResult.onResult(null);
                    }
                }
            });
            return call::cancel;
        }

        private String parseDecisionAction(String responseBody) {
            JsonElement root = JsonParser.parseString(responseBody);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject data = resolveDataObject(root.getAsJsonObject());
            if (data == null) {
                return null;
            }
            JsonElement action = data.get("consent_action");
            if (action == null || action.isJsonNull()) {
                return null;
            }
            String value = action.getAsString();
            return isBlank(value) ? null : value.trim();
        }

        private JsonObject resolveDataObject(JsonObject root) {
            if (!root.has("code")) {
                return root;
            }
            int code = root.get("code").getAsInt();
            if (code != SUCCESS_CODE && code != HTTP_STYLE_SUCCESS_CODE) {
                throw new IllegalStateException("CMP decision business code was " + code);
            }
            JsonElement dataElement = root.get("data");
            if (dataElement == null || dataElement.isJsonNull()) {
                return null;
            }
            if (!dataElement.isJsonObject()) {
                throw new IllegalStateException("CMP decision data must be an object");
            }
            return dataElement.getAsJsonObject();
        }

        private String safeMac(DeviceInfo deviceInfo) {
            if (deviceInfo == null || deviceInfo.mac == null || deviceInfo.mac.isBlank()) {
                return "00:00:00:00:00:00";
            }
            return deviceInfo.mac;
        }
    }
}
