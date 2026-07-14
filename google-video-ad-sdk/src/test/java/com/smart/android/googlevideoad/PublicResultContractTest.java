package com.smart.android.googlevideoad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class PublicResultContractTest {

    @Test
    public void adErrorExposesStableDetails() {
        RuntimeException cause = new RuntimeException("offline");
        AdError error = new AdError(
            AdErrorCode.CONFIG_NETWORK_ERROR,
            AdErrorStage.CONFIG,
            "network unavailable",
            cause
        );

        assertEquals(AdErrorCode.CONFIG_NETWORK_ERROR, error.getCode());
        assertEquals(AdErrorStage.CONFIG, error.getStage());
        assertEquals("network unavailable", error.getMessage());
        assertSame(cause, error.getCause());
    }

    @Test
    public void completedResultHasNoErrorOrSkipReason() {
        AdResult result = AdResult.completed();

        assertEquals(AdResultStatus.COMPLETED, result.getStatus());
        assertNull(result.getError());
        assertNull(result.getReason());
    }

    @Test
    public void skippedResultPreservesReason() {
        AdResult result = AdResult.skipped("NO_AD_TAG");

        assertEquals(AdResultStatus.SKIPPED, result.getStatus());
        assertEquals("NO_AD_TAG", result.getReason());
    }

    @Test
    public void errorResultPreservesError() {
        AdError error = new AdError(
            AdErrorCode.IMA_PLAYBACK_ERROR,
            AdErrorStage.PLAYER,
            "playback failed",
            null
        );

        AdResult result = AdResult.error(error);

        assertEquals(AdResultStatus.ERROR, result.getStatus());
        assertSame(error, result.getError());
    }

    @Test
    public void cancelledResultUsesCancelledStatus() {
        assertEquals(AdResultStatus.CANCELLED, AdResult.cancelled().getStatus());
    }
}
