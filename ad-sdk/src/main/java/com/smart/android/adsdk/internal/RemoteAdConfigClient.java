package com.smart.android.adsdk.internal;

import com.google.gson.Gson;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class RemoteAdConfigClient implements RemoteAdConfigResolver {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final RemoteAdConfigParser parser;
    private final String resolveUrl;

    RemoteAdConfigClient(
        OkHttpClient okHttpClient,
        Gson gson,
        RemoteAdConfigParser parser,
        String resolveUrl
    ) {
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        this.parser = parser;
        this.resolveUrl = resolveUrl;
    }

    @Override
    public Cancellable resolve(String channelId, String requestId, RemoteAdConfigResolver.Callback callback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", channelId);
        if (requestId != null && !requestId.trim().isEmpty()) {
            body.put("request_id", requestId.trim());
        }
        String requestJson = gson.toJson(body);
        Request request = new Request.Builder()
            .url(resolveUrl)
            .post(RequestBody.create(JSON, requestJson))
            .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                callback.onError(new AdError(
                    AdErrorCode.CONFIG_NETWORK_ERROR,
                    AdErrorStage.CONFIG,
                    "Unable to request ad config",
                    error
                ));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeableResponse = response) {
                    if (!closeableResponse.isSuccessful()) {
                        callback.onError(new AdError(
                            AdErrorCode.CONFIG_HTTP_ERROR,
                            AdErrorStage.CONFIG,
                            "ad config HTTP status " + closeableResponse.code(),
                            null
                        ));
                        return;
                    }
                    ResponseBody body = closeableResponse.body();
                    if (body == null) {
                        callback.onError(parseError("ad config response body was empty", null));
                        return;
                    }
                    try {
                        callback.onResolved(parser.parse(body.string()));
                    } catch (RemoteAdConfigParseException | IOException error) {
                        callback.onError(parseError("Unable to parse ad config response", error));
                    }
                }
            }
        });
        return call::cancel;
    }

    private AdError parseError(String message, Throwable cause) {
        return new AdError(
            AdErrorCode.CONFIG_PARSE_ERROR,
            AdErrorStage.CONFIG,
            message,
            cause
        );
    }
}
