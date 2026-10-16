package com.smart.android.adsdk.universal.internal;

import android.content.Context;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.android.adsdk.universal.AdError;
import com.smart.android.adsdk.universal.AdErrorCode;
import com.smart.android.adsdk.universal.AdErrorStage;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class RemoteAdConfigClient implements RemoteAdConfigResolver {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int SUCCESS_CODE = 100_000;
    private static final int HTTP_STYLE_SUCCESS_CODE = 200;

    private final DeviceInfoProvider deviceInfoProvider;
    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final RemoteAdConfigParser parser;
    private final String apiBaseUrl;
    private final boolean authorizationOnly;
    interface IdentifierSource { String rdid(); String androidId(); }
    private IdentifierSource identifiers;

    RemoteAdConfigClient(
        Context context,
        OkHttpClient okHttpClient,
        Gson gson,
        RemoteAdConfigParser parser,
        String apiBaseUrl
    ) {
        this(
            () -> DeviceInfo.collect(context),
            okHttpClient,
            gson,
            parser,
            apiBaseUrl,
            false
        );
    }

    RemoteAdConfigClient(
        DeviceInfoProvider deviceInfoProvider,
        OkHttpClient okHttpClient,
        Gson gson,
        RemoteAdConfigParser parser,
        String apiBaseUrl
    ) {
        this(deviceInfoProvider, okHttpClient, gson, parser, apiBaseUrl, false);
    }

    RemoteAdConfigClient(DeviceInfoProvider deviceInfoProvider, OkHttpClient client, Gson gson,
                         RemoteAdConfigParser parser, String apiBaseUrl, boolean authorizationOnly) {
        this.authorizationOnly = authorizationOnly;
        this.deviceInfoProvider = deviceInfoProvider;
        this.okHttpClient = client;
        this.gson = gson;
        this.parser = parser;
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";
    }

    void setIdentifierContext(Context context) {
        identifiers = context == null ? null : new IdentifierSource() {
            @Override public String rdid() { return IdentifierStore.rdid(context); }
            @Override public String androidId() { return IdentifierStore.androidId(context); }
        };
    }
    void setIdentifierSource(IdentifierSource source) { identifiers = source; }

    @Override
    public Cancellable resolve(String channelId, String requestId, RemoteAdConfigResolver.Callback callback) {
        FlowCallSequence sequence = new FlowCallSequence();
        String localRequestId = requestId == null || requestId.trim().isEmpty()
            ? generateRequestId()
            : requestId.trim();
        requestAuthorize(channelId, localRequestId, callback, sequence);
        return sequence::cancel;
    }

    private void requestAuthorize(
        String channelId,
        String requestId,
        RemoteAdConfigResolver.Callback callback,
        FlowCallSequence sequence
    ) {
        DeviceInfo deviceInfo = deviceInfoOrEmpty();
        Map<String, Object> body = buildAuthorizeBody(channelId, requestId, deviceInfo);
        Request request = buildJsonPost(this.apiBaseUrl + "api/v2/ad/sdk/authorize", body);
        Call call = okHttpClient.newCall(request);
        sequence.setActiveCall(call);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                if (!sequence.isCancelled()) {
                    finishAuthorizeFailure(callback, AdErrorCode.CONFIG_NETWORK_ERROR, error, null);
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                String raw = null;
                try (Response closeableResponse = response) {
                    if (sequence.isCancelled()) {
                        return;
                    }
                    ResponseBody responseBody = closeableResponse.body();
                    raw = responseBody == null ? "" : responseBody.string();
                    if (!closeableResponse.isSuccessful()) {
                        finishAuthorizeFailure(callback, AdErrorCode.CONFIG_HTTP_ERROR,
                            AdResponseException.http(closeableResponse.code(), closeableResponse.message(), raw), raw);
                        return;
                    }
                    JsonObject data = parseDataObject(raw, "authorize");
                    String resolvedRequestId = readString(data, "request_id");
                    if (resolvedRequestId.isEmpty()) {
                        resolvedRequestId = requestId;
                    }
                    Long callbackTimeoutSeconds = readNullableLong(
                        data,
                        "ad_callback_timeout_seconds"
                    );
                    if (callbackTimeoutSeconds == null) {
                        callbackTimeoutSeconds = readNullableLong(
                            data,
                            "callback_timeout_seconds"
                        );
                    }
                    FlowAuthorizedConfig flowConfig = new FlowAuthorizedConfig(
                        resolvedRequestId,
                        readBoolean(data, "hidden_mode", true),
                        readNullableBoolean(data, "sound_mode"),
                        readLong(data, "next_request_seconds", 0L),
                        AdCallbackTimeoutPolicy.resolveOverrideMs(callbackTimeoutSeconds),
                        readString(data, "client_ip")
                    );
                    callback.onAuthorizationResponse(flowConfig);
                    if (sequence.isCancelled()) return;
                    if (!readBoolean(data, "authorized", false)) {
                        AdError error = AdErrors.from(AdErrorCode.CONFIG_HTTP_ERROR, AdErrorStage.CONFIG,
                            AdResponseException.api(raw, "AUTHORIZE_DENIED"), raw);
                        callback.onTrace("AUTHORIZE_DENIED", error.toString());
                        callback.onResolved(RemoteAdConfigResult.skipped(
                            AdResponseException.reason(raw, "AUTHORIZE_DENIED"), error));
                        return;
                    }
                    callback.onAuthorized(flowConfig);
                    if (sequence.isCancelled()) return;
                    if (authorizationOnly) {
                        // TCL retains its own native ad-fetch protocol after our authorization.
                        callback.onResolved(RemoteAdConfigResult.withAd(new AdPlaybackConfig(
                            "", 20_000, 35_000L, resolvedRequestId, flowConfig.isHiddenMode(),
                            flowConfig.getSoundEnabled(), flowConfig.getNextRequestSeconds())));
                    } else {
                        if (sequence.isCancelled()) return;
                        RemoteAdConfigResult result = parser.parse(gson.toJson(data), flowConfig);
                        if (!result.hasAd()) {
                            AdError error = AdErrors.from(AdErrorCode.CONFIG_PARSE_ERROR, AdErrorStage.CONFIG,
                                AdResponseException.api(raw, result.getSkipReason()), raw);
                            result = RemoteAdConfigResult.skipped(
                                AdResponseException.reason(raw, result.getSkipReason()), error);
                        }
                        callback.onTrace("GAM_CONFIG_RESULT", "hasAd=" + result.hasAd());
                        callback.onResolved(result);
                    }
                } catch (Throwable error) {
                    if (!sequence.isCancelled()) {
                        finishAuthorizeFailure(callback, AdErrorCode.CONFIG_PARSE_ERROR, error, raw);
                    }
                }
            }
        });
    }

    private void finishAuthorizeFailure(
        RemoteAdConfigResolver.Callback callback, AdErrorCode category, Throwable cause, String raw
    ) {
        AdError error = AdErrors.from(category, AdErrorStage.CONFIG, cause, raw);
        callback.onTrace("AUTHORIZE_CALLBACK_FAIL", error.toString());
        callback.onResolved(RemoteAdConfigResult.skipped(error.getMessage(), error));
    }

    private Map<String, Object> buildAuthorizeBody(
        String channelId,
        String requestId,
        DeviceInfo deviceInfo
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("uuid", deviceInfo.uuid);
        body.put("channel_id", channelId);
        RequestVersions.appendTo(body, deviceInfo);
        body.put("app_id", deviceInfo.packageName);
        body.put("app_name", "hq008");
        body.put("bundle", deviceInfo.packageName);
        body.put("ua", deviceInfo.userAgent);
        body.put("ifa", deviceInfo.uuid);
        body.put("make", deviceInfo.make);
        body.put("model", deviceInfo.model);
        body.put("os", "Android");
        body.put("osv", deviceInfo.osVersion);
        body.put("language", deviceInfo.language);
        body.put("video_w", deviceInfo.screenWidth);
        body.put("video_h", deviceInfo.screenHeight);
        body.put("screen_w", deviceInfo.realScreenWidth);
        body.put("screen_h", deviceInfo.realScreenHeight);
        body.put("local_ip", deviceInfo.localIp);
        body.put("mac", !authorizationOnly && deviceInfo.mac.isEmpty() ? "00:00:00:00:00:00" : deviceInfo.mac);
        body.put("androidid", deviceInfo.androidIdForRequest());
        if (!authorizationOnly && identifiers != null) {
            body.put("rdid", identifiers.rdid());
            body.put("androidid", identifiers.androidId());
        }
        return body;
    }

    private Request buildJsonPost(String url, Map<String, Object> body) {
        return new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .header("Accept", "application/json")
            .build();
    }

    private JsonObject parseDataObject(String raw, String label) throws IOException {
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw.trim())) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) {
            throw new IOException(label + " response is not a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement codeElement = root.get("code");
        if (codeElement != null && !codeElement.isJsonNull()) {
            int code = codeElement.getAsInt();
            if (code != SUCCESS_CODE && code != HTTP_STYLE_SUCCESS_CODE) {
                throw AdResponseException.api(raw, String.valueOf(code));
            }
        }
        JsonElement result = root.get("result");
        if (result != null && result.isJsonObject()) {
            return result.getAsJsonObject();
        }
        JsonElement data = root.get("data");
        if (data != null && data.isJsonObject()) {
            return data.getAsJsonObject();
        }
        return root;
    }

    private boolean readBoolean(JsonObject object, String fieldName, boolean fallback) {
        try {
            JsonElement element = object.get(fieldName);
            return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private Boolean readNullableBoolean(JsonObject object, String fieldName) {
        try {
            JsonElement element = object.get(fieldName);
            return element == null || element.isJsonNull() ? null : element.getAsBoolean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private long readLong(JsonObject object, String fieldName, long fallback) {
        try {
            JsonElement element = object.get(fieldName);
            return element == null || element.isJsonNull() ? fallback : element.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private Long readNullableLong(JsonObject object, String fieldName) {
        try {
            JsonElement element = object.get(fieldName);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                return null;
            }
            if (!element.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            BigDecimal value = element.getAsBigDecimal();
            return value.longValueExact();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String readString(JsonObject object, String fieldName) {
        try {
            JsonElement element = object.get(fieldName);
            return element == null || element.isJsonNull() ? "" : element.getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private DeviceInfo deviceInfoOrEmpty() {
        DeviceInfo info = deviceInfoProvider.collect();
        return info == null ? DeviceInfo.empty() : info;
    }

    private String generateRequestId() {
        return "client-"
            + System.currentTimeMillis()
            + "-"
            + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.US);
    }

    interface DeviceInfoProvider {
        DeviceInfo collect();
    }

    private static final class FlowCallSequence {
        private boolean cancelled;
        private Call activeCall;

        synchronized void setActiveCall(Call call) {
            if (cancelled) {
                call.cancel();
                return;
            }
            activeCall = call;
        }

        synchronized void cancel() {
            cancelled = true;
            if (activeCall != null) {
                activeCall.cancel();
                activeCall = null;
            }
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }
    }
}
