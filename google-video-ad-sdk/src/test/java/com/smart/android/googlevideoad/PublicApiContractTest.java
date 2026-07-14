package com.smart.android.googlevideoad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PublicApiContractTest {

    @Test
    public void sdkConfigRequiresTrimmedChannelId() {
        SdkConfig config = new SdkConfig.Builder()
            .setChannelId("  GOOGLE_AD_TV_DESKTOP  ")
            .setDebugLogging(true)
            .build();

        assertEquals("GOOGLE_AD_TV_DESKTOP", config.getChannelId());
        assertTrue(config.isDebugLogging());
    }

    @Test(expected = IllegalArgumentException.class)
    public void sdkConfigRejectsBlankChannelId() {
        new SdkConfig.Builder()
            .setChannelId("   ")
            .build();
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
}
