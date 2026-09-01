package com.smart.android.adsdk.internal;

import android.view.ViewGroup;

interface AdPlaybackEngine {
    void play(
        ViewGroup container,
        AdPlaybackConfig config,
        boolean soundEnabled,
        Listener listener
    );

    void pause(Listener listener);

    void resume(Listener listener);

    void setSoundEnabled(Listener listener, boolean enabled);

    void detach(Listener listener);

    void release();

    interface Listener {
        void onLoaded();

        void onContentPauseRequested();

        void onStarted();

        void onCompleted();

        void onSkipped();

        void onContentResumeRequested();

        void onPlaybackEnded();

        void onAdError(String message);

        void onPlayerError(String message, Throwable cause);
    }
}
