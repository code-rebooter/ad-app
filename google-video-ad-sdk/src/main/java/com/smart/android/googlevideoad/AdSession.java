package com.smart.android.googlevideoad;

public interface AdSession {
    AdState getState();

    void pause();

    void resume();

    void setSoundEnabled(boolean enabled);

    void release();
}
