package com.smart.android.googleima;

import static org.junit.Assert.assertEquals;

import com.google.ads.interactivemedia.v3.impl.AdsRequestImpl;
import com.smart.android.googleima.internal.GoogleImaRuntimeBridge;

import org.junit.Test;

import java.lang.reflect.Field;

public final class GoogleImaSoundControlTest {
    @Test
    public void unmutedRequestNormalizationIgnoresFragmentParameters() {
        assertEquals(
                "https://example.com/ad?foo=1&vpmute=0#route?vpmute=1",
                GoogleImaRuntimeBridge.forceUnmutedAdTagUrl(
                        "https://example.com/ad?foo=1#route?vpmute=1"));
        assertEquals(
                "https://example.com/ad?foo=1&vpmute=0#route?vpmute=1",
                GoogleImaRuntimeBridge.forceUnmutedAdTagUrl(
                        "https://example.com/ad?foo=1&vpmute=1#route?vpmute=1"));
    }

    @Test
    public void adsRequestMuteStateIsExplicitlyUnmuted() throws Exception {
        AdsRequestImpl request = new AdsRequestImpl();
        GoogleImaRuntimeBridge.forceUnmutedAdsRequest(request);
        Field field = AdsRequestImpl.class.getDeclaredField("zze");
        field.setAccessible(true);
        assertEquals("UNMUTED", field.get(request).toString());
    }
}
