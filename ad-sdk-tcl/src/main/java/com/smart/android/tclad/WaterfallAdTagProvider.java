package com.smart.android.tclad;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** POSTs the captured request schema to the configured host backend and resolves data.adsUrl. */
public final class WaterfallAdTagProvider implements AdTagProvider {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final HttpUrl endpoint;
    private final WaterfallRequest template;
    private final OkHttpClient client;

    public WaterfallAdTagProvider(String endpoint, WaterfallRequest template) {
        this(endpoint, template, new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build());
    }
    /** The supplied client can add the host backend's authentication headers. */
    public WaterfallAdTagProvider(String endpoint, WaterfallRequest template, OkHttpClient client) {
        this.endpoint = HttpUrl.get(endpoint);
        if (template == null || client == null) throw new IllegalArgumentException("Template and HTTP client required");
        this.template = template;
        this.client = client;
    }
    @Override public void resolve(RequestParams params, AdTagProvider.Callback callback) {
        resolveCancellable(params, callback);
    }
    @Override public Cancellable resolveCancellable(RequestParams params, AdTagProvider.Callback callback) {
        WaterfallRequest payload = template.withParams(params);
        String requestId = UUID.randomUUID().toString();
        Request.Builder request = new Request.Builder().url(endpoint)
            .header("Accept", "application/json").post(RequestBody.create(payload.toJson(), JSON));
        String agent = payload.getValues().get("userAgent").toString();
        if (!agent.isEmpty()) request.header("User-Agent", agent);
        Call call = client.newCall(request.build());
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call ignored, IOException error) {
                if (!call.isCanceled()) callback.onError(301);
            }
            @Override public void onResponse(Call ignored, Response response) {
                String resolved;
                int errorCode = 301;
                try (Response closeable = response) {
                    if (!closeable.isSuccessful() || closeable.body() == null) throw new IOException("Backend HTTP failure");
                    JsonObject root = JsonParser.parseString(closeable.body().string()).getAsJsonObject();
                    errorCode = 900;
                    if (!root.has("code") || root.get("code").getAsInt() != 100000)
                        throw new IllegalArgumentException("Backend rejected request");
                    JsonObject data = root.getAsJsonObject("data");
                    HttpUrl tag = data == null || !data.has("adsUrl") ? null : HttpUrl.parse(data.get("adsUrl").getAsString());
                    if (tag == null) throw new IllegalArgumentException("Invalid data.adsUrl");
                    // Original VastRequest.getVastUrl replaces/adds this one client-generated parameter.
                    resolved = tag.newBuilder().setQueryParameter("requestId", requestId).build().toString();
                } catch (IOException | RuntimeException error) {
                    if (!call.isCanceled()) callback.onError(errorCode);
                    return;
                }
                if (!call.isCanceled()) callback.onResolved(resolved);
            }
        });
        return call::cancel;
    }
}
