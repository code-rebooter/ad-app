package com.smart.android.adsdk.internal;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class SystemMaintenanceGuardService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!SystemMaintenanceGuard.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SystemMaintenanceGuard.beginPolling(getApplicationContext());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
