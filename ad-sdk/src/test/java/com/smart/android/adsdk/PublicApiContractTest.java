package com.smart.android.adsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PublicApiContractTest {

    @Test
    public void sdkConfigDefaultsToStandardRuntimeOptions() {
        SdkConfig config = new SdkConfig.Builder()
            .setDebugLogging(true)
            .build();

        assertTrue(config.isDebugLogging());
        assertEquals(180_000L, config.getAdCallbackTimeoutMs());
    }

    @Test
    public void adRequestDefaultsToMutedPlayback() {
        assertFalse(new AdRequest.Builder().build().isSoundEnabled());
    }

    @Test
    public void adRequestCanEnableSound() {
        AdRequest request = new AdRequest.Builder()
            .setSoundEnabled(true)
            .build();

        assertTrue(request.isSoundEnabled());
    }

    @Test
    public void adRequestCarriesTrimmedRequestIdWhenProvided() {
        AdRequest request = new AdRequest.Builder()
            .setRequestId("  request-123  ")
            .build();

        assertEquals("request-123", request.getRequestId());
    }

    @Test
    public void sdkConfigCanOverrideCallbackTimeout() {
        SdkConfig config = new SdkConfig.Builder()
            .setAdCallbackTimeoutMs(12_345L)
            .build();

        assertEquals(12_345L, config.getAdCallbackTimeoutMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void sdkConfigRejectsNonPositiveCallbackTimeout() {
        new SdkConfig.Builder()
            .setAdCallbackTimeoutMs(0L)
            .build();
    }
}
