package com.smart.android.tclad;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        AtomicBoolean cancelledByCaller = new AtomicBoolean();
        WaterfallRequest payload = template.withParams(params);
        Request.Builder request = new Request.Builder().url(endpoint)
            .header("Accept", "application/json").post(RequestBody.create(payload.toJson(), JSON));
        String agent = payload.getValues().get("userAgent").toString();
        if (!agent.isEmpty()) request.header("User-Agent", agent);
        OkHttpClient observed = client.newBuilder().addInterceptor(chain -> {
            if (cancelledByCaller.get()) throw new IOException("Cancelled by host");
            callback.onHttpRequest();
            return chain.proceed(chain.request());
        }).addNetworkInterceptor(chain -> {
            Response response = chain.proceed(chain.request());
            if (!cancelledByCaller.get()) callback.onHttpResponse(response.code());
            return response;
        }).build();
        Call call = observed.newCall(request.build());
        call.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call ignored, IOException error) {
                // OkHttp also sets isCanceled for its own callTimeout. Only an
                // explicit host cancellation suppresses a terminal callback.
                if (!cancelledByCaller.get()) callback.onError(301);
            }
            @Override public void onResponse(Call ignored, Response response) {
                if (!cancelledByCaller.get()) callback.onHttpResponse(response.code());
                ResolvedAd resolved;
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
                    // Preserve the native response metadata and fallback; TCL adds its requestId later.
                    if (!data.has("beaconingType")) data.addProperty("beaconingType", 2);
                    resolved = ResolvedAd.fromTclResponse(root.toString());
                } catch (IOException | RuntimeException error) {
                    if (!cancelledByCaller.get()) callback.onError(errorCode);
                    return;
                }
                if (!cancelledByCaller.get()) callback.onResolvedAd(resolved);
            }
        });
        return () -> { cancelledByCaller.set(true); call.cancel(); };
    }
}
