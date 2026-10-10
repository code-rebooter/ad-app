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
        void onResolved(String vastUrl);
        void onError(int code);
    }
}
