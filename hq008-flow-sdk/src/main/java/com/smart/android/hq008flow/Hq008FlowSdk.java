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
        FlowRuntime next = createRuntime(context, config);
        replaceRuntime(next);
        next.start();
    }

    /** Convenience entry for integrations that register a process-wide callback. */
    public static void init(Context context, String channelId, Hq008AdCallback callback) {
        init(context, new Hq008FlowConfig(channelId), callback);
    }

    /** Convenience entry for integrations that register a process-wide callback. */
    public static void init(Context context, Hq008FlowConfig config, Hq008AdCallback callback) {
        FlowRuntime next = createRuntime(context, config);
        replaceRuntime(next);
        if (callback != null) {
            next.setAdCallback(callback);
        }
        next.start();
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

    private static FlowRuntime requireRuntime() {
        FlowRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException(
                    "Hq008FlowSdk.init(context, channelId) must be called first"
            );
        }
        return current;
    }

    private static FlowRuntime createRuntime(Context context, Hq008FlowConfig config) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        return new FlowRuntime(context.getApplicationContext(), config);
    }

    private static void replaceRuntime(FlowRuntime next) {
        synchronized (LOCK) {
            if (runtime != null) {
                runtime.stop();
            }
            runtime = next;
        }
    }
}
