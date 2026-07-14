package com.smart.android.googlevideoad.internal;

import com.google.gson.Gson;
import com.smart.android.googlevideoad.AdError;
import com.smart.android.googlevideoad.AdErrorCode;
import com.smart.android.googlevideoad.AdErrorStage;
import java.io.IOException;
import java.util.Collections;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class GamConfigClient implements GamConfigResolver {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final GamConfigParser parser;
    private final String resolveUrl;

    GamConfigClient(
        OkHttpClient okHttpClient,
        Gson gson,
        GamConfigParser parser,
        String resolveUrl
    ) {
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        this.parser = parser;
        this.resolveUrl = resolveUrl;
    }

    @Override
    public Cancellable resolve(String channelId, GamConfigResolver.Callback callback) {
        String requestJson = gson.toJson(Collections.singletonMap("channel_id", channelId));
        Request request = new Request.Builder()
            .url(resolveUrl)
            .post(RequestBody.create(requestJson, JSON))
            .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                callback.onError(new AdError(
                    AdErrorCode.CONFIG_NETWORK_ERROR,
                    AdErrorStage.CONFIG,
                    "Unable to request GAM config",
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
                            "GAM config HTTP status " + closeableResponse.code(),
                            null
                        ));
                        return;
                    }
                    ResponseBody body = closeableResponse.body();
                    if (body == null) {
                        callback.onError(parseError("GAM config response body was empty", null));
                        return;
                    }
                    try {
                        callback.onResolved(parser.parse(body.string()));
                    } catch (GamConfigParseException | IOException error) {
                        callback.onError(parseError("Unable to parse GAM config response", error));
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
