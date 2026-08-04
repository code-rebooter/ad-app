package com.smart.android.hq008flow;

import android.content.Context;

/** Entry point for the process-local HQ008 flow controller. */
public final class Hq008FlowSdk {
    private static final Object LOCK = new Object();
    private static volatile FlowRuntime runtime;

    private Hq008FlowSdk() {
    }

    /** Recommended entry: initialize once and start the global flow scheduler. */
    public static void init(Context context, String channelId) {
        init(context, new Hq008FlowConfig(channelId));
    }

    /** Recommended entry when advanced configuration is required. */
    public static void init(Context context, Hq008FlowConfig config) {
        initialize(context, config);
        start();
    }

    /** Convenience entry for integrations that register a process-wide callback. */
    public static void init(Context context, String channelId, Hq008AdCallback callback) {
        init(context, new Hq008FlowConfig(channelId), callback);
    }

    /** Convenience entry for integrations that register a process-wide callback. */
    public static void init(Context context, Hq008FlowConfig config, Hq008AdCallback callback) {
        initialize(context, config);
        if (callback != null) {
            setAdCallback(callback);
        }
        start();
    }

    /** Call once from Application.onCreate(). */
    public static void initialize(Context context, String channelId) {
        initialize(context, new Hq008FlowConfig(channelId));
    }

    /** Call once from Application.onCreate() when advanced configuration is required. */
    public static void initialize(Context context, Hq008FlowConfig config) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        FlowRuntime next = new FlowRuntime(context.getApplicationContext(), config);
        synchronized (LOCK) {
            if (runtime != null) {
                runtime.stop();
            }
            runtime = next;
        }
    }

    /** Compatibility entry. New integrations should use init(...), which starts automatically. */
    public static void start() {
        requireRuntime().start();
    }

    /** Stop future scheduling and close the current flow session. */
    public static void stop() {
        FlowRuntime current = runtime;
        if (current != null) {
            current.stop();
        }
    }

    /** Debug integration entry: trigger the flow immediately. */
    public static void triggerNow() {
        requireRuntime().triggerNow();
    }

    /** Register the current ad display callback from any component that can show an ad. */
    public static void setAdCallback(Hq008AdCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        requireRuntime().setAdCallback(callback);
    }

    /** Clear the current ad display callback. */
    public static void clearAdCallback() {
        FlowRuntime current = runtime;
        if (current != null) {
            current.clearAdCallback();
        }
    }

    /**
     * @deprecated Use {@link #setAdCallback(Hq008AdCallback)}. Kept for source
     * compatibility with existing integrations.
     */
    @Deprecated
    public static void attachAdHost(Hq008AdHost host) {
        if (host == null) {
            throw new IllegalArgumentException("host must not be null");
        }
        requireRuntime().setAdCallback(host);
    }

    /**
     * @deprecated Use {@link #clearAdCallback()}. Kept for source compatibility
     * with existing integrations.
     */
    @Deprecated
    public static void detachAdHost(Hq008AdHost host) {
        FlowRuntime current = runtime;
        if (current != null && host != null) {
            current.clearAdCallback(host);
        }
    }

    private static FlowRuntime requireRuntime() {
        FlowRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException(
                    "Hq008FlowSdk.init(context, channelId) or initialize(context, channelId) must be called first"
            );
        }
        return current;
    }
}
