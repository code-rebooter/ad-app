package com.smart.android.googleima.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GoogleImaStartHandshakeStateTest {
    @Test
    public void startIsBlockedUntilRealMediaEvidenceArrives() {
        GoogleImaStartHandshakeState state = new GoogleImaStartHandshakeState();
        Object mediaInfo = new Object();

        state.onPlay(mediaInfo, false);

        assertEquals(
                GoogleImaStartHandshakeState.ACTION_BLOCK_START,
                state.prepareStart(mediaInfo, 0.2f, 15f));
        assertTrue(state.markMediaEvidence());
        assertEquals(
                GoogleImaStartHandshakeState.ACTION_RELEASE_START,
                state.prepareStart(mediaInfo, 0.4f, 15f));
        assertTrue(state.onCoreStartSent(mediaInfo));
        assertEquals(
                GoogleImaStartHandshakeState.ACTION_NONE,
                state.prepareStart(mediaInfo, 0.6f, 15f));
    }

    @Test
    public void missingStartedCanReleaseExactlyOneRecoveryStart() {
        GoogleImaStartHandshakeState state = new GoogleImaStartHandshakeState();
        Object mediaInfo = new Object();

        state.onPlay(mediaInfo, false);
        state.markMediaEvidence();
        state.onCoreStartSent(mediaInfo);

        assertTrue(state.requestRecovery());
        assertEquals(
                GoogleImaStartHandshakeState.ACTION_RELEASE_START,
                state.prepareStart(mediaInfo, 4f, 16f));
        assertTrue(state.onCoreStartSent(mediaInfo));
        assertEquals(2, state.getCoreStartSendCount());
        assertEquals(
                GoogleImaStartHandshakeState.ACTION_NONE,
                state.prepareStart(mediaInfo, 4.2f, 16f));
    }

    @Test
    public void measurementRequiresBothImaStartedAndOmidSession() {
        GoogleImaStartHandshakeState state = new GoogleImaStartHandshakeState();
        Object mediaInfo = new Object();

        state.onPlay(mediaInfo, false);
        assertFalse(state.isMeasurementReady());
        assertTrue(state.markImaStarted());
        assertFalse(state.isMeasurementReady());
        assertTrue(state.markOmidStarted());
        assertTrue(state.isMeasurementReady());
    }

    @Test
    public void newPlayResetsPreviousAdMeasurementState() {
        GoogleImaStartHandshakeState state = new GoogleImaStartHandshakeState();
        Object first = new Object();
        Object second = new Object();

        state.onPlay(first, false);
        state.markImaStarted();
        state.markOmidStarted();
        assertTrue(state.isMeasurementReady());

        state.onPlay(second, false);

        assertFalse(state.isImaStarted());
        assertFalse(state.isOmidStarted());
        assertEquals(
                GoogleImaStartHandshakeState.ACTION_NONE,
                state.prepareStart(first, 1f, 10f));
    }

    @Test
    public void activeOmidSessionBeforePlayIsPreservedForTheNewAd() {
        GoogleImaStartHandshakeState state = new GoogleImaStartHandshakeState();

        state.onPlay(new Object(), true);

        assertTrue(state.isOmidStarted());
        assertFalse(state.isMeasurementReady());
    }
}
