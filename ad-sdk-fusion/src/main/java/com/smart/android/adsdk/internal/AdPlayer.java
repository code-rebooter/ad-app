package com.smart.android.adsdk.internal;

import com.smart.android.adsdk.AdError;

interface AdPlayer {
    void play(AdPlaybackConfig config, boolean soundEnabled);

    void pause();

    void resume();

    void setSoundEnabled(boolean enabled);

    /** Mark intent from any thread; native/UI teardown remains on the main thread. */
    default void requestCancellation() {}

    void release();

    interface Listener {
        default void onTrace(String eventType, String message) {}

        void onLoaded();

        void onStarted();

        void onCompleted();

        void onSkipped(String reason);

        void onError(AdError error);
    }
}
