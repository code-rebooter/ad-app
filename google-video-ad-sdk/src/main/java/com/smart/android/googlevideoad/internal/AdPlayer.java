package com.smart.android.googlevideoad.internal;

import com.smart.android.googlevideoad.AdError;

interface AdPlayer {
    void play(GamPlaybackConfig config, boolean soundEnabled);

    void pause();

    void resume();

    void setSoundEnabled(boolean enabled);

    void release();

    interface Listener {
        void onLoaded();

        void onStarted();

        void onCompleted();

        void onSkipped(String reason);

        void onError(AdError error);
    }
}
