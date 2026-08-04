package com.smart.android.hq008flow;

/** Called when flow-control and authorize allow one ad playback attempt. */
public interface Hq008AdCallback {
    void onAdAuthorized(Hq008AdSession session);
}
