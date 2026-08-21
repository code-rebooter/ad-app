package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class DeviceInfoCacheTest {

    @Test
    public void collectsDeviceInfoOnlyOnce() {
        DeviceInfoCache cache = new DeviceInfoCache();
        AtomicInteger calls = new AtomicInteger();
        DeviceInfo expected = new DeviceInfo(
            "pkg",
            "1.0",
            7L,
            "android-id",
            "uuid",
            "11:22:33:44:55:66",
            "192.168.0.2",
            "ua",
            "make",
            "model",
            "14",
            "zh-CN",
            1,
            2,
            3,
            4
        );

        DeviceInfo first = cache.getOrCreate(null, context -> {
            calls.incrementAndGet();
            return expected;
        });
        DeviceInfo second = cache.getOrCreate(null, context -> {
            calls.incrementAndGet();
            return DeviceInfo.empty();
        });

        assertSame(expected, first);
        assertSame(expected, second);
        assertEquals(1, calls.get());
    }

    @Test
    public void skipsNetworkIdentityCollectionForSystemUid() {
        assertEquals(false, DeviceInfo.shouldCollectNetworkIdentity(true));
        assertEquals(true, DeviceInfo.shouldCollectNetworkIdentity(false));
    }
}
