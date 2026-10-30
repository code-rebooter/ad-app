package com.smart.android.googleima;

/** Confirms that the active Media3 ad position really moved before start fallback. */
final class GoogleImaMediaStartProbe {
    private static final long MIN_POSITION_DELTA_MS = 100L;

    private long lastPositionMs = -1L;
    private long forwardProgressMs;
    private boolean armed;

    void arm(long positionMs) {
        lastPositionMs = positionMs;
        forwardProgressMs = 0L;
        armed = true;
    }

    boolean observe(boolean isPlayingAd, boolean isPlaying, long positionMs) {
        if (!armed || !isPlayingAd || !isPlaying || positionMs < 0L) return false;
        if (lastPositionMs < 0L) {
            lastPositionMs = positionMs;
            return false;
        }
        long deltaMs = positionMs - lastPositionMs;
        lastPositionMs = positionMs;
        if (deltaMs < 0L) {
            forwardProgressMs = 0L;
            return false;
        }
        forwardProgressMs += deltaMs;
        return forwardProgressMs >= MIN_POSITION_DELTA_MS;
    }

    void reset() {
        lastPositionMs = -1L;
        forwardProgressMs = 0L;
        armed = false;
    }
}
