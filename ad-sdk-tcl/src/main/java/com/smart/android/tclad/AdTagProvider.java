package com.smart.android.tclad;

/** Supplies the URL from the host backend. Resolve should return promptly; completion may be asynchronous. */
public interface AdTagProvider {
    void resolve(RequestParams params, Callback callback);
    /** Custom providers can override this to release transport work when a controller stops. */
    default Cancellable resolveCancellable(RequestParams params, Callback callback) {
        resolve(params, callback);
        return () -> {};
    }
    interface Cancellable { void cancel(); }
    interface Callback {
        /** Called when the real address transport starts, before DNS/connection work. */
        default void onHttpRequest() {}
        /** Legacy URL-only consumers retain a per-resolution ID; the native SDK overrides this
         * callback to receive the complete, unchanged response and apply its own requestId. */
        default void onResolvedAd(ResolvedAd input) {
            String url = input.toOriginalResponse().getData().getAdsUrl();
            onResolved(okhttp3.HttpUrl.get(url).newBuilder()
                    .setQueryParameter("requestId", java.util.UUID.randomUUID().toString()).build().toString());
        }
        /** Headers arrived from the real tag endpoint, including unsuccessful/empty responses. */
        default void onHttpResponse(int status) {}
        void onResolved(String vastUrl);
        void onError(int code);
    }
}
