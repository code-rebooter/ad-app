package com.smart.android.googlevideoad;

public interface AdListener {
    void onLoaded(AdSession session);

    void onStarted(AdSession session);

    void onFinished(AdSession session, AdResult result);
}
