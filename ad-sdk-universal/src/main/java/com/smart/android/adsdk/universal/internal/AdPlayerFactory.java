package com.smart.android.adsdk.universal.internal;

import android.view.ViewGroup;

interface AdPlayerFactory {
    AdPlayer create(ViewGroup container, AdPlayer.Listener listener);
}
