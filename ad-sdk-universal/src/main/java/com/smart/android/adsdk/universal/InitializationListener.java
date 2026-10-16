package com.smart.android.adsdk.universal;

public interface InitializationListener {
    void onInitialized();

    void onError(AdError error);
}
