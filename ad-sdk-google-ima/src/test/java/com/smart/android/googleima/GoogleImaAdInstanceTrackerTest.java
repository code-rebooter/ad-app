package com.smart.android.googleima;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.ads.interactivemedia.v3.api.Ad;
import com.google.ads.interactivemedia.v3.api.AdPodInfo;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class GoogleImaAdInstanceTrackerTest {
    @Test
    public void duplicateCreativeObjectsRemainSeparateInstances() {
        GoogleImaAdInstanceTracker tracker = new GoogleImaAdInstanceTracker();
        Object firstAd = new Object();
        Object secondAd = new Object();

        String first = tracker.onLoaded(firstAd, "same-creative");
        tracker.markTerminal(first);
        String second = tracker.onLoaded(secondAd, "same-creative");

        assertNotEquals(first, second);
        assertEquals(first, tracker.resolve(firstAd, "same-creative"));
        assertEquals(second, tracker.resolve(secondAd, "same-creative"));
        assertTrue(tracker.isTerminal(first));
        assertFalse(tracker.isTerminal(second));
    }

    @Test
    public void aNewProxyForTheCurrentAdReusesItsFingerprint() {
        GoogleImaAdInstanceTracker tracker = new GoogleImaAdInstanceTracker();
        String loaded = tracker.onLoaded(new Object(), "pod-0-position-1");

        assertEquals(loaded, tracker.resolve(new Object(), "pod-0-position-1"));
    }

    @Test
    public void lateEventProxyResolvesTheTerminalHistoricalInstance() {
        GoogleImaAdInstanceTracker tracker = new GoogleImaAdInstanceTracker();
        String first = tracker.onLoaded(new Object(), "pod-0-position-1");
        tracker.markTerminal(first);
        String second = tracker.onLoaded(new Object(), "pod-0-position-2");

        String lateFirst = tracker.resolve(new Object(), "pod-0-position-1");

        assertEquals(first, lateFirst);
        assertTrue(tracker.isTerminal(lateFirst));
        assertTrue(tracker.isCurrent(second));
    }

    @Test
    public void loadedRepeatedMaterialStillCreatesANewInstance() {
        GoogleImaAdInstanceTracker tracker = new GoogleImaAdInstanceTracker();
        String first = tracker.onLoaded(new Object(), "same-pod-material");
        tracker.markTerminal(first);

        String second = tracker.onLoaded(new Object(), "same-pod-material");

        assertNotEquals(first, second);
        assertTrue(tracker.isCurrent(second));
    }

    @Test
    public void growingTotalAdsDoesNotChangeTheCurrentAdFingerprint() {
        AtomicInteger totalAds = new AtomicInteger(2);
        AdPodInfo podInfo = (AdPodInfo) Proxy.newProxyInstance(
                AdPodInfo.class.getClassLoader(),
                new Class<?>[] {AdPodInfo.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPodIndex":
                            return 0;
                        case "getAdPosition":
                            return 1;
                        case "getTotalAds":
                            return totalAds.get();
                        case "getMaxDuration":
                            return 30d;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        Ad ad = (Ad) Proxy.newProxyInstance(
                Ad.class.getClassLoader(),
                new Class<?>[] {Ad.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAdId":
                            return "ad-1";
                        case "getCreativeId":
                            return "creative-1";
                        case "getCreativeAdId":
                            return "creative-ad-1";
                        case "getUniversalAdIdRegistry":
                            return "ad-id.org";
                        case "getUniversalAdIdValue":
                            return "universal-1";
                        case "getAdPodInfo":
                            return podInfo;
                        case "getDuration":
                            return 15d;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        String beforeGrowth = GoogleImaAdPlayerView.adFingerprint(ad);
        totalAds.set(3);
        String afterGrowth = GoogleImaAdPlayerView.adFingerprint(ad);

        assertEquals(beforeGrowth, afterGrowth);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }
}
