package com.smart.android.adsdk.internal;

import android.content.Context;
import android.view.ViewGroup;

final class AdPlaybackControllerFactory implements AdPlayerFactory {
    private static final SharedEngineHolder PROCESS_SHARED_ENGINE = new SharedEngineHolder();

    private final boolean shareProcessEngine;
    private final EngineFactory engineFactory;
    private final SharedEngineHolder sharedEngineHolder;
    private final TimeoutSchedulerFactory timeoutSchedulerFactory;

    AdPlaybackControllerFactory(Context context) {
        Context resolvedContext = context.getApplicationContext();
        Context appContext = resolvedContext == null ? context : resolvedContext;
        this.shareProcessEngine = SystemUidStorageCompat.isSystemUid();
        this.engineFactory = () -> new ImaAdPlaybackEngine(appContext);
        this.sharedEngineHolder = PROCESS_SHARED_ENGINE;
        this.timeoutSchedulerFactory = MainThreadTimeoutScheduler::new;
    }

    AdPlaybackControllerFactory(
        boolean shareProcessEngine,
        EngineFactory engineFactory,
        SharedEngineHolder sharedEngineHolder,
        TimeoutSchedulerFactory timeoutSchedulerFactory
    ) {
        this.shareProcessEngine = shareProcessEngine;
        this.engineFactory = engineFactory;
        this.sharedEngineHolder = sharedEngineHolder;
        this.timeoutSchedulerFactory = timeoutSchedulerFactory;
    }

    @Override
    public AdPlayer create(ViewGroup container, AdPlayer.Listener listener) {
        if (shareProcessEngine) {
            AdPlaybackEngine engine = sharedEngineHolder.getOrCreate(engineFactory);
            return new AdPlaybackController(
                container,
                listener,
                engine,
                false,
                timeoutSchedulerFactory.create()
            );
        }
        return new AdPlaybackController(
            container,
            listener,
            engineFactory.create(),
            true,
            timeoutSchedulerFactory.create()
        );
    }

    interface EngineFactory {
        AdPlaybackEngine create();
    }

    interface TimeoutSchedulerFactory {
        TimeoutScheduler create();
    }

    static final class SharedEngineHolder {
        private AdPlaybackEngine engine;

        synchronized AdPlaybackEngine getOrCreate(EngineFactory factory) {
            if (engine == null) {
                engine = factory.create();
            }
            return engine;
        }

        synchronized AdPlaybackEngine peek() {
            return engine;
        }
    }
}
