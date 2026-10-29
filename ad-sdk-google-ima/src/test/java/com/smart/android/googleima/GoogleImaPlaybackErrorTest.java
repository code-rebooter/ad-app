package com.smart.android.googleima;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.ads.interactivemedia.v3.api.AdError;

import org.junit.Test;

public final class GoogleImaPlaybackErrorTest {
    @Test
    public void vastLoadTimeoutKeepsImaDetailsAndUsesTimeoutCategory() {
        GoogleImaPlaybackError error = GoogleImaAdPlayerView.buildImaError(
                new AdError(
                        AdError.AdErrorType.LOAD,
                        AdError.AdErrorCode.VAST_LOAD_TIMEOUT,
                        "VAST load timed out"));

        assertTrue(error.isTimeout());
        assertEquals("ima_vast_load_timeout", error.getReason());
        assertEquals("ima", error.getDetails().get("error_source"));
        assertEquals(
                AdError.AdErrorCode.VAST_LOAD_TIMEOUT.toString(),
                error.getDetails().get("ima_error_code"));
    }

    @Test
    public void vastMediaLoadTimeoutUsesTimeoutCategory() {
        GoogleImaPlaybackError error = GoogleImaAdPlayerView.buildImaError(
                new AdError(
                        AdError.AdErrorType.LOAD,
                        AdError.AdErrorCode.VAST_MEDIA_LOAD_TIMEOUT,
                        "VAST media load timed out"));

        assertTrue(error.isTimeout());
        assertEquals("ima_vast_media_load_timeout", error.getReason());
        assertEquals("ima", error.getDetails().get("error_source"));
    }

    @Test
    public void nonTimeoutImaErrorKeepsNormalImaCategory() {
        GoogleImaPlaybackError error = GoogleImaAdPlayerView.buildImaError(
                new AdError(
                        AdError.AdErrorType.PLAY,
                        AdError.AdErrorCode.VIDEO_PLAY_ERROR,
                        "Video playback failed"));

        assertFalse(error.isTimeout());
        assertEquals(GoogleImaPlaybackError.CATEGORY_IMA, error.getCategory());
        assertEquals("ima_sdk_error", error.getReason());
        assertEquals("ima", error.getDetails().get("error_source"));
    }
}
