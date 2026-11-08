package com.smart.android.tclad;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.smart.android.tclad.internal.NativeRuntime;
import com.smart.android.tclad.internal.OriginalBoundary;

/** Public API adapter. The adurl pipeline runs exclusively in the original TCL 2.8.02 classes. */
public final class Ad {
    private static final Ad INSTANCE = new Ad();
    private volatile boolean logging;
    private Ad() {}
    public static Ad get() { return INSTANCE; }
    public void setEnableLog(boolean enabled) { logging = enabled; }
    public boolean isLogEnabled() { return logging; }
    public Builder begin(Context context) { return new Builder(context); }
    public static final class Builder {
        private final Context context;
        private boolean lazy;
        private float volume = 1f;
        private int playerType;
        private int viewType;
        private int width;
        private int height;
        private AdStatusListener listener = new AdStatusListener() {};
        private ResolvedAd resolved;
        private RequestParams params;
        private AdTagProvider provider;
        private ExecutionConfig executionConfig;
        private final Handler main = new Handler(Looper.getMainLooper());
        private Builder(Context context) {
            if (context == null) throw new IllegalArgumentException("context required");
            this.context = context;
        }
        public Builder lazyLoad() { lazy = true; return this; }
        public Builder setAdType(AdType value) {
            if (value != AdType.WATERFALL) throw new IllegalArgumentException("This entry is the TCL WATERFALL path");
            return this;
        }
        public Builder setPlayerType(int value) { playerType = value; return this; }
        public Builder setPlayerViewType(int value) { viewType = value; return this; }
        public Builder setVolume(float value) { volume = value; return this; }
        public Builder setMaterialWidth(int value) { width = value; return this; }
        public Builder size(int width, int height) { this.width = width; this.height = height; return this; }
        public Builder listen(AdStatusListener value) { listener = value == null ? new AdStatusListener() {} : value; return this; }
        public Builder setVastUrl(String url) { resolved = new ResolvedAd.Builder(url).build(); return this; }
        public Builder setResolvedAd(ResolvedAd value) { resolved = value; return this; }
        public Builder setRequestParams(RequestParams value) { params = value; return this; }
        public Builder setExecutionConfig(ExecutionConfig value) { executionConfig = value; return this; }
        public Builder setAdTagProvider(AdTagProvider value) { provider = value; return this; }
        public Controller start() {
            if (!lazy || playerType != 0) throw new IllegalArgumentException("Use lazyLoad and original playerType=0");
            Controller handle = new Controller();
            Runnable begin = () -> {
                if (handle.cancelledBeforeEntry()) return;
                handle.begin(executionConfig, listener, volume);
                if (handle.cancelledBeforeEntry()) return;
                Runnable ready = () -> {
                    if (handle.cancelledBeforeEntry()) return;
                    if (resolved != null) enter(handle, resolved);
                    else {
                        AdTagProvider source = provider == null ? Initialization.getAdTagProvider() : provider;
                        if (source == null) { handle.execution().fail(900, "missing_ad_tag_provider"); return; }
                        try {
                            handle.upstream(source.resolveCancellable(params == null ? new RequestParams.Builder().build() : params,
                                new AdTagProvider.Callback() {
                                    @Override public void onHttpRequest() { handle.execution().addressAttempt(); }
                                    @Override public void onHttpResponse(int status) { handle.execution().addressResponse(status); }
                                    @Override public void onResolvedAd(ResolvedAd input) {
                                        main.post(() -> { if (!handle.cancelledBeforeEntry()) enter(handle, input); });
                                    }
                                    @Override public void onResolved(String url) {
                                        main.post(() -> { if (!handle.cancelledBeforeEntry()) enter(handle, new ResolvedAd.Builder(url).build()); });
                                    }
                                    @Override public void onError(int code) {
                                        main.post(() -> {
                                            if (!handle.cancelledBeforeEntry()) {
                                                handle.execution().addressFailure("provider_error_" + code);
                                                handle.execution().fail(code, "tag_request_failed");
                                            }
                                        });
                                    }
                                }));
                        } catch (RuntimeException error) {
                            handle.execution().addressFailure(error.toString());
                            handle.execution().fail(900, "tag_request_failed");
                        }
                    }
                };
                if (NativeRuntime.isInitialized()) ready.run();
                else NativeRuntime.initializeAsync(context, Ad.get().isLogEnabled(), () -> main.post(ready),
                    error -> main.post(() -> handle.execution().fail(900, "sdk_init_failed")));
            };
            if (Looper.myLooper() == Looper.getMainLooper()) begin.run(); else main.post(begin);
            return handle;
        }
        private void enter(Controller handle, ResolvedAd input) {
            if (handle.cancelledBeforeEntry()) return;
            try {
                handle.execution().starting(() -> {
                    var type = com.tcl.ff.component.overseabase.base.constant.AdType.WATERFALL;
                    var callbacks = new com.tcl.ff.component.vastad.core.callbacks.AdStatusListener() {
                        @Override public void onAdLoaded(com.tcl.ff.component.vastad.Controller original) {
                            main.post(() -> {
                                if (handle.cancelledBeforeEntry()) return;
                                handle.bind(original);
                                handle.execution().loaded(() -> listener.onAdLoaded(handle));
                            });
                        }
                        @Override public void onAdStartPlay() { main.post(() -> handle.execution().started(null)); }
                        @Override public void onAdStartPlay(double seconds) { main.post(() -> handle.execution().started(seconds)); }
                        @Override public void onAdFinished() { main.post(() -> handle.execution().completed()); }
                        @Override public void onAdError(int code) { main.post(() -> handle.execution().fail(code, null)); }
                        @Override public void onContainerSizeError() {
                            main.post(() -> {
                                if (handle.cancelledBeforeEntry()) return;
                                listener.onContainerSizeError();
                                handle.execution().fail(903, "container_size_error");
                            });
                        }
                    };
                    var config = com.tcl.ff.component.vastad.Ad.get().begin(context)
                        .lazyLoad().setAdType(type).setVolume(volume)
                        .setPlayerType(playerType).setPlayerViewType(viewType).listen(callbacks);
                    if (width != 0 || height != 0) config.size(width, height);
                    if (params != null) config.setRequestParams(originalParams());
                    handle.bind(OriginalBoundary.start(config, input));
                });
            } catch (RuntimeException | LinkageError error) {
                handle.execution().fail(900, "sdk_request_failed");
            }
        }
        private com.tcl.ff.component.overseabasebusiness.requestparams.RequestParams originalParams() {
            var builder = new com.tcl.ff.component.overseabasebusiness.requestparams.RequestParams.Builder();
            if (params != null) for (var entry : params.getTypedValues().entrySet()) {
                String key = entry.getKey();
                String setter = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
                Object value = entry.getValue();
                try { builder.getClass().getMethod(setter, value instanceof Number ? Integer.class : String.class)
                    .invoke(builder, value instanceof Number ? ((Number) value).intValue() : value); }
                catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Unsupported TCL request field: " + key, error); }
            }
            return builder.build();
        }
    }
}
