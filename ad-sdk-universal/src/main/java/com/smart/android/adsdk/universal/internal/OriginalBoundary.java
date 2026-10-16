package com.smart.android.adsdk.universal.internal;

import com.google.gson.Gson;

import java.lang.reflect.Field;

/** Version-pinned entry bridge; it never parses VAST or emits playback/tracking events. */
public final class OriginalBoundary {
    private OriginalBoundary() {}
    public static Object field(Object target, Class<?> owner, String name) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("TCL 2.8.02 ABI mismatch: " + name, e); }
    }
    public static void field(Object target, Class<?> owner, String name, Object value) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("TCL 2.8.02 ABI mismatch: " + name, e); }
    }
    /** Configuration is supplied before entering the adurl pipeline, just as a cached TCL config is. */
    public static void configure(String json) {
        if (json == null) throw new IllegalArgumentException("Original configuration snapshot is required");
        com.tcl.ff.component.overseabasebusiness.bean.AdSystemConfig config = new Gson().fromJson(
            json, com.tcl.ff.component.overseabasebusiness.bean.AdSystemConfig.class);
        if (config == null || !config.check()) throw new IllegalArgumentException("Invalid original configuration snapshot");
        field(com.tcl.ff.component.overseabasebusiness.AdConfigFetcher.get(),
            com.tcl.ff.component.overseabasebusiness.AdConfigFetcher.class, "e", config);
    }
    /** The original public API creates every controller, fetcher, timer and listener itself. */
    public static com.tcl.ff.component.vastad.Controller start(com.tcl.ff.component.vastad.core.Config config, ResolvedAd input) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            throw new IllegalStateException("Enter the original API on the main thread");
        var original = com.tcl.ff.component.overseahttp.http.HttpRequester.get();
        var client = (okhttp3.OkHttpClient) field(original, original.getClass(), "c");
        var supplied = new SuppliedAddressTransport(client, input.toJson());
        field(supplied, com.tcl.ff.component.overseahttp.http.HttpRequester.class, "d",
            field(original, original.getClass(), "d"));
        // Ad caches the requester separately from the HTTP singleton. Bind both for this entry.
        return withTransport(com.tcl.ff.component.vastad.Ad.get(),
            com.tcl.ff.component.overseahttp.http.HttpRequester.class, supplied, config::start);
    }
    interface Operation<T> { T get(); }
    static <T> T withTransport(Object ad, Class<?> http, Object supplied, Operation<T> start) {
        Object previousSingleton = field(null, http, "a");
        Object previousTransport = field(ad, ad.getClass(), "b");
        field(null, http, "a", supplied);
        field(ad, ad.getClass(), "b", supplied);
        try { return start.get(); }
        finally {
            field(ad, ad.getClass(), "b", previousTransport);
            field(null, http, "a", previousSingleton);
        }
    }
    private static final class SuppliedAddressTransport extends com.tcl.ff.component.overseahttp.http.HttpRequester {
        private final String response;
        SuppliedAddressTransport(okhttp3.OkHttpClient client, String response) { super(client); this.response = response; }
        @Override public String resolveSuppliedAdAddress(String url, String body) {
            if (url.equals(com.tcl.ff.component.overseabasebusiness.constant.ServerApi.getMixedVastTagApi())
                || url.equals(com.tcl.ff.component.overseabasebusiness.constant.ServerApi.getRealVastTagUrl())) return response;
            return null;
        }
    }
}
