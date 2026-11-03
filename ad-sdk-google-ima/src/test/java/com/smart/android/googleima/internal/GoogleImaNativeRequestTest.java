package com.smart.android.googleima.internal;

import com.google.ads.interactivemedia.v3.impl.data.zzbx;
import com.google.ads.interactivemedia.v3.impl.data.zzby;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GoogleImaNativeRequestTest {
    @Test public void backendUrlReplacesOnlyTheUrlInThePinnedSdkRequest() {
        zzby original = zzby.create(zzbx.GET, "native-request-7",
                "https://pubads.g.doubleclick.net/gampad/ads?sdkv=core", "host-ua",
                "original-content", 1234, 5678);
        String finalUrl = "https://ads.example.test/ads?x=one&x=two&ms=a%2Bb%2Fc%3D#tail";
        zzby patched = GoogleImaRuntimeBridge.copyNativeRequestUrl(original, finalUrl);
        assertEquals(finalUrl, patched.url());
        assertEquals("native-request-7", patched.id());
        assertEquals("host-ua", patched.userAgent());
        assertEquals("original-content", patched.content());
        assertEquals(zzbx.GET, patched.requestType());
        assertEquals(1234, patched.connectionTimeoutMs());
        assertEquals(5678, patched.readTimeoutMs());
        assertNotEquals(finalUrl, original.url());
    }

    @Test public void omittedCoreUserAgentAndContentRemainOmitted() {
        zzby original = new zzby() {
            @Override public int connectionTimeoutMs() { return 0; }
            @Override public int readTimeoutMs() { return 0; }
            @Override public zzbx requestType() { return zzbx.GET; }
            @Override public String content() { return null; }
            @Override public String id() { return "core-request"; }
            @Override public String url() { return "https://original.test/ads"; }
            @Override public String userAgent() { return null; }
        };
        zzby patched = GoogleImaRuntimeBridge.copyNativeRequestUrl(original, "https://patched.test/ads");
        assertNull(patched.userAgent());
        assertNull(patched.content());
        assertEquals("core-request", patched.id());
        assertEquals("https://patched.test/ads", patched.url());
        assertEquals(0, patched.connectionTimeoutMs());
        assertEquals(0, patched.readTimeoutMs());
    }
}
