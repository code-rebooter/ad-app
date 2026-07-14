package com.smart.android.googlevideoad.internal;

import android.content.Context;
import android.view.ViewGroup;

final class ImaAdPlayerFactory implements AdPlayerFactory {
    private final Context appContext;

    ImaAdPlayerFactory(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public AdPlayer create(ViewGroup container, AdPlayer.Listener listener) {
        return new ImaAdPlayer(appContext, container, listener);
    }
}
