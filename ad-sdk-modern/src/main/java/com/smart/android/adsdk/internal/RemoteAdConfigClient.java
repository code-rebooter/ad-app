package com.smart.android.adsdk.internal;

import android.content.Context;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import okhttp3.Call;
import okhttp3.HttpUrl;
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
    private final String resolveUrl;
    private final boolean legacyResolveOnly;

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

    /**
     * Kept for source compatibility with the old internal unit-test constructor.
     * Runtime SDK construction uses the full flow constructor above.
     */
    RemoteAdConfigClient(
        OkHttpClient okHttpClient,
        Gson gson,
        RemoteAdConfigParser parser,
        String resolveUrl
    ) {
        this(
            () -> null,
            okHttpClient,
            gson,
            parser,
            resolveApiBaseUrl(resolveUrl),
            true
        );
    }

    private RemoteAdConfigClient(
        DeviceInfoProvider deviceInfoProvider,
        OkHttpClient okHttpClient,
        Gson gson,
        RemoteAdConfigParser parser,
        String apiBaseUrl,
        boolean legacyResolveOnly
    ) {
        this.deviceInfoProvider = deviceInfoProvider;
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        this.parser = parser;
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";
        this.resolveUrl = this.apiBaseUrl + "api/v2/ad/google-gam/resolve";
        this.legacyResolveOnly = legacyResolveOnly;
    }

    @Override
    public Cancellable resolve(String channelId, String requestId, RemoteAdConfigResolver.Callback callback) {
        FlowCallSequence sequence = new FlowCallSequence();
        String localRequestId = requestId == null || requestId.trim().isEmpty()
            ? generateRequestId()
            : requestId.trim();
        if (legacyResolveOnly) {
            requestGamConfig(channelId, null, localRequestId, callback, sequence);
        } else {
            requestAuthorize(channelId, localRequestId, callback, sequence);
        }
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
                    callback.onResolved(RemoteAdConfigResult.skipped("AUTHORIZE_FAIL"));
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeableResponse = response) {
                    if (sequence.isCancelled()) {
                        return;
                    }
                    if (!closeableResponse.isSuccessful()) {
                        callback.onResolved(RemoteAdConfigResult.skipped("AUTHORIZE_FAIL"));
                        return;
                    }
                    ResponseBody responseBody = closeableResponse.body();
                    JsonObject data = parseDataObject(
                        responseBody == null ? "" : responseBody.string(),
                        "authorize"
                    );
                    String resolvedRequestId = readString(data, "request_id");
                    if (resolvedRequestId.isEmpty()) {
                        resolvedRequestId = requestId;
                    }
                    if (!readBoolean(data, "authorized", false)) {
                        callback.onResolved(RemoteAdConfigResult.skipped("AUTHORIZE_DENIED"));
                        return;
                    }
                    FlowAuthorizedConfig flowConfig = new FlowAuthorizedConfig(
                        resolvedRequestId,
                        readBoolean(data, "hidden_mode", true),
                        readNullableBoolean(data, "sound_mode"),
                        readLong(data, "next_request_seconds", 0L)
                    );
                    callback.onAuthorized(flowConfig);
                    requestGamConfig(channelId, flowConfig, resolvedRequestId, callback, sequence);
                } catch (Throwable error) {
                    if (!sequence.isCancelled()) {
                        callback.onResolved(RemoteAdConfigResult.skipped("AUTHORIZE_FAIL"));
                    }
                }
            }
        });
    }

    private void requestGamConfig(
        String channelId,
        FlowAuthorizedConfig flowConfig,
        String requestId,
        RemoteAdConfigResolver.Callback callback,
        FlowCallSequence sequence
    ) {
        DeviceInfo deviceInfo = deviceInfoOrEmpty();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", channelId);
        body.put("mac", deviceInfo.mac.isEmpty() ? "00:00:00:00:00:00" : deviceInfo.mac);
        Request request = buildJsonPost(resolveUrl, body);
        Call call = okHttpClient.newCall(request);
        sequence.setActiveCall(call);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                if (!sequence.isCancelled()) {
                    callback.onError(new AdError(
                        AdErrorCode.CONFIG_NETWORK_ERROR,
                        AdErrorStage.CONFIG,
                        "Unable to request ad config",
                        error
                    ));
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeableResponse = response) {
                    if (sequence.isCancelled()) {
                        return;
                    }
                    if (!closeableResponse.isSuccessful()) {
                        callback.onError(new AdError(
                            AdErrorCode.CONFIG_HTTP_ERROR,
                            AdErrorStage.CONFIG,
                            "ad config HTTP status " + closeableResponse.code(),
                            null
                        ));
                        return;
                    }
                    ResponseBody responseBody = closeableResponse.body();
                    if (responseBody == null) {
                        callback.onError(parseError("ad config response body was empty", null));
                        return;
                    }
                    try {
                        callback.onResolved(parser.parse(responseBody.string(), flowConfig));
                    } catch (RemoteAdConfigParseException | IOException error) {
                        callback.onError(parseError("Unable to parse ad config response", error));
                    }
                }
            }
        });
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
        body.put("ad_version", deviceInfo.versionCode);
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
        body.put("mac", deviceInfo.mac);
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
                throw new IOException(label + " business code was " + code);
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

    private AdError parseError(String message, Throwable cause) {
        return new AdError(
            AdErrorCode.CONFIG_PARSE_ERROR,
            AdErrorStage.CONFIG,
            message,
            cause
        );
    }

    private static String resolveApiBaseUrl(String resolveUrl) {
        HttpUrl url = HttpUrl.parse(resolveUrl);
        if (url == null) {
            return resolveUrl;
        }
        String path = url.encodedPath();
        int apiIndex = path.indexOf("/api/");
        String basePath = apiIndex >= 0 ? path.substring(0, apiIndex + 1) : "/";
        return url.newBuilder()
            .encodedPath(basePath)
            .query(null)
            .fragment(null)
            .build()
            .toString();
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
