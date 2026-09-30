package com.smart.android.adsdk;

public interface AdListener {
    void onLoaded(AdSession session);

    void onStarted(AdSession session);

    void onFinished(AdSession session, AdResult result);
}
