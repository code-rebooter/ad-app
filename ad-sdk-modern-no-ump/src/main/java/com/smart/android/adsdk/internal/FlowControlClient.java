package com.smart.android.adsdk.internal;

import android.content.Context;
import android.os.Build;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

final class FlowControlClient implements FlowControlResolver {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int SUCCESS_CODE = 100_000;
    private static final int HTTP_STYLE_SUCCESS_CODE = 200;

    private final DeviceInfoProvider deviceInfoProvider;
    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final String flowControlUrl;

    FlowControlClient(
        Context context,
        OkHttpClient okHttpClient,
        Gson gson,
        String apiBaseUrl
    ) {
        this(() -> DeviceInfo.collect(context), okHttpClient, gson, apiBaseUrl);
    }

    FlowControlClient(
        DeviceInfoProvider deviceInfoProvider,
        OkHttpClient okHttpClient,
        Gson gson,
        String apiBaseUrl
    ) {
        this.deviceInfoProvider = deviceInfoProvider;
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        String baseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";
        this.flowControlUrl = baseUrl + "api/v2/ad/sdk/flow-control";
    }

    @Override
    public Cancellable resolve(String channelId, Callback callback) {
        DeviceInfo deviceInfo = deviceInfoProvider.collect();
        if (deviceInfo == null) {
            deviceInfo = DeviceInfo.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", channelId);
        body.put("mac", deviceInfo.mac);
        body.put("ad_version", deviceInfo.versionCode);
        body.put("android_sdk_version", Build.VERSION.SDK_INT);
        Request request = new Request.Builder()
            .url(flowControlUrl)
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .header("Accept", "application/json")
            .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                callback.onError(error);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeableResponse = response) {
                    if (!closeableResponse.isSuccessful()) {
                        callback.onError(new IOException("flow-control HTTP status " + closeableResponse.code()));
                        return;
                    }
                    ResponseBody responseBody = closeableResponse.body();
                    JsonObject data = parseDataObject(
                        responseBody == null ? "" : responseBody.string()
                    );
                    if (readBoolean(data, "enabled", false)) {
                        callback.onAllowed(readBoolean(data, "skip_cmp", false));
                    } else {
                        callback.onBlocked("FLOW_CONTROL_DISABLED");
                    }
                } catch (Throwable error) {
                    callback.onError(error);
                }
            }
        });
        return call::cancel;
    }

    private JsonObject parseDataObject(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw.trim())) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) {
            throw new IOException("flow-control response is not a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement codeElement = root.get("code");
        if (codeElement != null && !codeElement.isJsonNull()) {
            int code = codeElement.getAsInt();
            if (code != SUCCESS_CODE && code != HTTP_STYLE_SUCCESS_CODE) {
                throw new IOException("flow-control business code was " + code);
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

    private boolean readBoolean(JsonObject object, String name, boolean fallback) {
        try {
            JsonElement element = object.get(name);
            return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    interface DeviceInfoProvider {
        DeviceInfo collect();
    }
}
