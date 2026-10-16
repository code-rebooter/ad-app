package com.smart.android.adsdk.universal.internal;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.Gson;
import okhttp3.*;

final class KeyConfigClient {
    static final String DEFAULT_BASE_URL = "https://api.kytira.cc/";
    interface Callback { void onResult(KeyConfiguration configuration, Throwable error); }
    interface VersionSupplier { RequestVersions get(); }
    private final OkHttpClient client;
    private final String baseUrl;
    private final VersionSupplier versions;
    private final Gson gson = new Gson();
    KeyConfigClient(VersionSupplier versions, OkHttpClient client, String baseUrl) {
        this.versions = versions;
        this.client = client;
        this.baseUrl = baseUrl;
    }
    Cancellable request(String sdkKey, Callback callback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sdk_key", sdkKey);
        try {
            versions.get().appendTo(body);
        } catch (RuntimeException | LinkageError failure) {
            callback.onResult(null, failure);
            return () -> {};
        }
        Call call = client.newCall(new Request.Builder().url(baseUrl + "api/v2/ad/sdk/config")
            .post(RequestBody.create(gson.toJson(body), MediaType.get("application/json; charset=utf-8"))).build());
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException error) { if (!call.isCanceled()) callback.onResult(null, error); }
            @Override public void onResponse(Call call, Response response) {
                KeyConfiguration configuration = null; Throwable error = null;
                try (Response closed = response) {
                    if (!closed.isSuccessful()) throw new IOException("SDK key configuration HTTP " + closed.code());
                    if (closed.body() == null) throw new IOException("SDK key configuration is empty");
                    configuration = KeyConfiguration.parse(closed.body().string());
                } catch (IOException | RuntimeException failure) { error = failure; }
                if (!call.isCanceled()) callback.onResult(configuration, error);
            }
        });
        return call::cancel;
    }
}
