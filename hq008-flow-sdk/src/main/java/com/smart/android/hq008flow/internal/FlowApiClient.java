package com.smart.android.hq008flow.internal;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class FlowApiClient {
    private static final String TAG = "Hq008FlowApi";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse(
            "application/json; charset=utf-8"
    );

    private final String baseUrl;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .writeTimeout(30L, TimeUnit.SECONDS)
            .build();

    public FlowApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void requestFlowControl(
            Map<String, Object> requestBody,
            ApiCallback<FlowControlData> callback
    ) {
        post("api/v2/ad/sdk/flow-control", requestBody, (data, error) -> {
            if (error != null) {
                callback.onResult(null, error);
                return;
            }
            callback.onResult(
                    new FlowControlData(getBoolean(data, "enabled", false)),
                    null
            );
        });
    }

    public void requestAuthorize(
            Map<String, Object> requestBody,
            ApiCallback<AuthorizeData> callback
    ) {
        post("api/v2/ad/sdk/authorize", requestBody, (data, error) -> {
            if (error != null) {
                callback.onResult(null, error);
                return;
            }
            callback.onResult(
                    new AuthorizeData(
                            getBoolean(data, "authorized", false),
                            getLong(data, "next_request_seconds", 0L),
                            getString(data, "request_id", "")
                    ),
                    null
            );
        });
    }

    public void report(Map<String, Object> requestBody) {
        post("api/v2/ad/report", requestBody, (data, error) -> {
            if (error != null) {
                Log.w(TAG, "广告事件上报失败: " + error.getMessage());
            }
        });
    }

    public void cancelAll() {
        client.dispatcher().cancelAll();
    }

    private void post(
            String path,
            Map<String, Object> params,
            ApiCallback<JsonObject> callback
    ) {
        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, gson.toJson(params));
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(body)
                .header("Accept", "application/json")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                deliver(callback, null, error);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response safeResponse = response) {
                    String raw = safeResponse.body() == null
                            ? ""
                            : safeResponse.body().string();
                    if (!safeResponse.isSuccessful()) {
                        deliver(
                                callback,
                                null,
                                new IOException("HTTP " + safeResponse.code() + ": " + raw)
                        );
                        return;
                    }
                    deliver(callback, parseData(raw), null);
                } catch (Throwable error) {
                    deliver(callback, null, error);
                }
            }
        });
    }

    private JsonObject parseData(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw.trim())) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) {
            throw new IOException("response is not a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement codeElement = root.get("code");
        if (codeElement != null && !codeElement.isJsonNull()) {
            int code;
            try {
                code = codeElement.getAsInt();
            } catch (Exception error) {
                throw new IOException("invalid business code", error);
            }
            if (code != 200 && code != 100000) {
                throw new IOException("business code=" + code);
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

    private boolean getBoolean(JsonObject object, String name, boolean fallback) {
        try {
            JsonElement value = object.get(name);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long getLong(JsonObject object, String name, long fallback) {
        try {
            JsonElement value = object.get(name);
            return value == null || value.isJsonNull() ? fallback : value.getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String getString(JsonObject object, String name, String fallback) {
        try {
            JsonElement value = object.get(name);
            return value == null || value.isJsonNull() ? fallback : value.getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private <T> void deliver(ApiCallback<T> callback, T data, Throwable error) {
        mainHandler.post(() -> callback.onResult(data, error));
    }

    public interface ApiCallback<T> {
        void onResult(T data, Throwable error);
    }

    public static final class FlowControlData {
        public final boolean enabled;

        FlowControlData(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static final class AuthorizeData {
        public final boolean authorized;
        public final long nextRequestSeconds;
        public final String requestId;

        AuthorizeData(boolean authorized, long nextRequestSeconds, String requestId) {
            this.authorized = authorized;
            this.nextRequestSeconds = nextRequestSeconds;
            this.requestId = requestId;
        }
    }
}
