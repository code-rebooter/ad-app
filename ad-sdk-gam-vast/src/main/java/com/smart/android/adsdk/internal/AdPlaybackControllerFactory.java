package com.smart.android.adsdk.internal;

import android.content.Context;
import android.view.ViewGroup;
import okhttp3.OkHttpClient;

final class AdPlaybackControllerFactory implements AdPlayerFactory {
    private final Context appContext;
    private final OkHttpClient okHttpClient;

    AdPlaybackControllerFactory(Context context, OkHttpClient okHttpClient) {
        this.appContext = context.getApplicationContext();
        this.okHttpClient = okHttpClient;
    }

    @Override
    public AdPlayer create(ViewGroup container, AdPlayer.Listener listener) {
        return new AdPlaybackController(appContext, container, listener, okHttpClient);
    }
}
