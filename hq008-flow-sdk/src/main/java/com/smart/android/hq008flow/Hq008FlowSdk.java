package com.smart.android.hq008flow;

import android.content.Context;

/** Entry point for the process-local HQ008 flow controller. */
public final class Hq008FlowSdk {
    private static final Object LOCK = new Object();
    private static volatile FlowRuntime runtime;

    private Hq008FlowSdk() {
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

    /** Call after the customer's TCL media ad SDK has initialized successfully. */
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

    /** Debug integration entry: trigger immediately when an ad UI host is attached. */
    public static void triggerNow() {
        requireRuntime().triggerNow();
    }

    /** Call from the ad UI's onStart(). */
    public static void attachAdHost(Hq008AdHost host) {
        if (host == null) {
            throw new IllegalArgumentException("host must not be null");
        }
        requireRuntime().attachAdHost(host);
    }

    /** Call from the corresponding ad UI's onStop() with the same host instance. */
    public static void detachAdHost(Hq008AdHost host) {
        FlowRuntime current = runtime;
        if (current != null && host != null) {
            current.detachAdHost(host);
        }
    }

    private static FlowRuntime requireRuntime() {
        FlowRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException(
                    "Hq008FlowSdk.initialize(context, channelId) must be called first"
            );
        }
        return current;
    }
}
