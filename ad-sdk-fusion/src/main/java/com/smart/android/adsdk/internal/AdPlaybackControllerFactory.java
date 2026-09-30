package com.smart.android.adsdk.internal;

import android.content.Context;
import android.view.ViewGroup;

final class AdPlaybackControllerFactory implements AdPlayerFactory {
    private final Context appContext;

    AdPlaybackControllerFactory(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public AdPlayer create(ViewGroup container, AdPlayer.Listener listener) {
        return new AdPlaybackController(appContext, container, listener);
    }
}
