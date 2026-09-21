package com.smart.android.adsdk;

public interface AdSession {
    AdState getState();

    /** Current backend request ID, retained after this session finishes. */
    default String getRequestId() { return null; }

    /** Backend interval (10..86400 seconds), or 0 when absent/invalid. No automatic replay. */
    default long getNextRequestSeconds() { return 0L; }

    void pause();

    void resume();

    void setSoundEnabled(boolean enabled);

    void release();
}
