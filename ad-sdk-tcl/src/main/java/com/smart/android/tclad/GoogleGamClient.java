package com.smart.android.tclad;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Adapter for the existing POST /api/v2/ad/google-gam/resolve endpoint. */
public final class GoogleGamClient {
    private final HttpUrl endpoint;
    private final OkHttpClient http;
    public GoogleGamClient(String resolveUrl) {
        this(resolveUrl, new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build());
    }
    public GoogleGamClient(String resolveUrl, OkHttpClient http) {
        endpoint = HttpUrl.get(resolveUrl);
        if (http == null) throw new IllegalArgumentException("HTTP client required");
        this.http = http;
    }
    /** HTTP executor callbacks; onResolved(null) means that the backend supplied no ad. */
    public interface Callback {
        void onResolved(Config config);
        void onError(int code);
    }
    public static final class Config {
        private final String adTagUrl;
        private final int adLoadTimeoutMs;
        private final int adStartupTimeoutMs;
        private Config(String url, int load, int startup) {
            adTagUrl = url; adLoadTimeoutMs = load; adStartupTimeoutMs = startup;
        }
        public String getAdTagUrl() { return adTagUrl; }
        public int getAdLoadTimeoutMs() { return adLoadTimeoutMs; }
        public int getAdStartupTimeoutMs() { return adStartupTimeoutMs; }
    }
    public AdTagProvider.Cancellable resolve(String channelId, String mac, Callback callback) {
        if (channelId == null || channelId.trim().isEmpty()) throw new IllegalArgumentException("Channel required");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("channel_id", channelId);
        body.put("mac", mac == null || mac.trim().isEmpty() ? "00:00:00:00:00:00" : mac);
        Request request = new Request.Builder().url(endpoint).header("Accept", "application/json")
            .post(RequestBody.create(new Gson().toJson(body), MediaType.get("application/json; charset=utf-8"))).build();
        Call call = http.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call ignored, IOException error) { if (!call.isCanceled()) callback.onError(301); }
            @Override public void onResponse(Call ignored, Response response) {
                Config config;
                int failure = 301;
                try (Response closeable = response) {
                    if (!closeable.isSuccessful() || closeable.body() == null) throw new IOException("Resolve HTTP failure");
                    failure = 900;
                    JsonObject root = JsonParser.parseString(closeable.body().string()).getAsJsonObject();
                    if (root.has("code") && root.get("code").getAsInt() != 100000 && root.get("code").getAsInt() != 200)
                        throw new IllegalArgumentException("Resolve business failure");
                    JsonElement data = root.has("result") ? root.get("result") : root.has("data") ? root.get("data") : root;
                    if (data == null || data.isJsonNull()) config = null;
                    else {
                        JsonObject values = data.getAsJsonObject();
                        JsonElement tag = values.get("ad_tag_url");
                        String url = tag == null || tag.isJsonNull() ? "" : tag.getAsString().trim();
                        if (url.isEmpty()) config = null;
                        else {
                            HttpUrl.get(url);
                            config = new Config(url, positive(values, "ad_load_timeout_ms", 20000),
                                positive(values, "ad_startup_timeout_ms", 35000));
                        }
                    }
                } catch (IOException | RuntimeException error) {
                    if (!call.isCanceled()) callback.onError(failure);
                    return;
                }
                if (!call.isCanceled()) callback.onResolved(config);
            }
        });
        return call::cancel;
    }
    private static int positive(JsonObject source, String name, int fallback) {
        try {
            long value = source.get(name).getAsLong();
            return value > 0 && value <= Integer.MAX_VALUE ? (int) value : fallback;
        } catch (RuntimeException error) { return fallback; }
    }
}
