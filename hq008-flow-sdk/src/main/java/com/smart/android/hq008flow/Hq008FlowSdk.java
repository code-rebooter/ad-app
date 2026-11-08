package com.smart.android.hq008flow;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
        init(context, config, null);
    }

    /** Convenience entry for integrations that register a process-wide callback. */
    public static void init(Context context, String channelId, Hq008AdCallback callback) {
        init(context, new Hq008FlowConfig(channelId), callback);
    }

    /** Convenience entry for integrations that register a process-wide callback. */
    public static void init(Context context, Hq008FlowConfig config, Hq008AdCallback callback) {
        FlowRuntime next = createRuntime(context, config);
        replaceRuntime(next);
        Runnable activate = () -> {
            synchronized (LOCK) {
                // stop() can release host code which installs a newer runtime.
                // Also check when the preceding stop and this activation were queued.
                if (runtime != next) return;
                if (callback != null) next.setAdCallback(callback);
                if (runtime == next) next.start();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) activate.run();
        else new Handler(Looper.getMainLooper()).post(activate);
    }

    /** Initialize the runtime without starting the schedule. */
    public static void initialize(Context context, String channelId) {
        initialize(context, new Hq008FlowConfig(channelId));
    }

    /** Initialize the runtime without starting the schedule. */
    public static void initialize(Context context, Hq008FlowConfig config) {
        replaceRuntime(createRuntime(context, config));
    }

    /** Start the schedule after initialize() or init(...). */
    public static void start() {
        requireRuntime().start();
    }

    /** Stop the schedule and close the active ad attempt if one was dispatched. */
    public static void stop() {
        FlowRuntime current = runtime;
        if (current != null) {
            current.stop();
        }
    }

    /** Trigger the next flow cycle immediately. */
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

    private static FlowRuntime requireRuntime() {
        FlowRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException(
                    "Hq008FlowSdk.initialize(context, channelId) or init(context, channelId) must be called first"
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
        FlowRuntime previous;
        synchronized (LOCK) {
            previous = runtime;
            runtime = next;
        }
        // Host release code may initialize again. Publish our owner first so
        // that a newer reentrant initialize is not overwritten on return.
        if (previous != null) previous.stop();
    }
}
