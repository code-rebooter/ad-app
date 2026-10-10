package com.smart.android.tclad;

public interface MediaAdInitListener {
    void onInitComplete();
    default void onInitError(int code) {}
}
