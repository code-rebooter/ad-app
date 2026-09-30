package com.smart.android.adsdk.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SystemMaintenanceBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SystemMaintenanceGuard.start(context, intent == null ? "boot" : intent.getAction());
    }
}
