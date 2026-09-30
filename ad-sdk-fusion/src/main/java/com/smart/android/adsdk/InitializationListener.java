package com.smart.android.adsdk;

public interface InitializationListener {
    void onInitialized();

    void onError(AdError error);
}
