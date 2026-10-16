package com.smart.android.adsdk.universal;

public interface AdListener {
    void onLoaded(AdSession session);

    void onStarted(AdSession session);

    void onFinished(AdSession session, AdResult result);
}
