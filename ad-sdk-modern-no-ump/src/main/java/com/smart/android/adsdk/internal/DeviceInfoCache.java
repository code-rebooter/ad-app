package com.smart.android.adsdk.internal;

import android.content.Context;

final class DeviceInfoCache {
    private final Object lock = new Object();
    private volatile DeviceInfo cached;

    DeviceInfo getOrCreate(Context context, Collector collector) {
        DeviceInfo current = cached;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            current = cached;
            if (current == null) {
                current = collector.collect(context);
                if (current == null) {
                    current = DeviceInfo.empty();
                }
                cached = current;
            }
            return current;
        }
    }

    interface Collector {
        DeviceInfo collect(Context context);
    }
}
