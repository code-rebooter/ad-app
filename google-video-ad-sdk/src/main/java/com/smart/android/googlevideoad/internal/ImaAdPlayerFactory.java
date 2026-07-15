package com.smart.android.googlevideoad.internal;

import android.content.Context;
import android.view.ViewGroup;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

final class ImaAdPlayerFactory implements AdPlayerFactory {
    private final Context appContext;

    ImaAdPlayerFactory(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    @OptIn(markerClass = UnstableApi.class)
    public AdPlayer create(ViewGroup container, AdPlayer.Listener listener) {
        return new ImaAdPlayer(appContext, container, listener);
    }
}
