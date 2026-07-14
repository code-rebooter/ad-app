package com.smart.android.googlevideoad.internal;

import android.view.ViewGroup;

interface AdPlayerFactory {
    AdPlayer create(ViewGroup container, AdPlayer.Listener listener);
}
