package com.smart.android.adsdk.internal;

import android.view.ViewGroup;

interface AdPlayerFactory {
    AdPlayer create(ViewGroup container, AdPlayer.Listener listener);
}
