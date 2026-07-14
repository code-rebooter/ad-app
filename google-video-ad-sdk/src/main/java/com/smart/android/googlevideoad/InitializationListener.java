package com.smart.android.googlevideoad;

public interface InitializationListener {
    void onInitialized();

    void onError(AdError error);
}
