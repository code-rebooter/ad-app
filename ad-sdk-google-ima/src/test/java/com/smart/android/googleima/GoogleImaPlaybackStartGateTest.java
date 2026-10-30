package com.smart.android.googleima;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GoogleImaPlaybackStartGateTest {
    @Test
    public void imaRemainsPreferredInsideTheMediaGraceWindow() {
        GoogleImaPlaybackStartGate gate = new GoogleImaPlaybackStartGate();

        assertEquals(
                GoogleImaPlaybackStartGate.Decision.WAIT_FOR_IMA,
                gate.onMediaPlaybackObserved());
        assertEquals(
                GoogleImaPlaybackStartGate.Decision.START_IMA,
                gate.onImaStarted());
        assertEquals(
                GoogleImaPlaybackStartGate.Decision.IGNORE,
                gate.onMediaFallbackDeadline());
    }

    @Test
    public void mediaFallbackStartsWhenImaNeverReportsStarted() {
        GoogleImaPlaybackStartGate gate = new GoogleImaPlaybackStartGate();

        gate.onMediaPlaybackObserved();
        assertEquals(
                GoogleImaPlaybackStartGate.Decision.START_MEDIA_FALLBACK,
                gate.onMediaFallbackDeadline());
        assertEquals(
                GoogleImaPlaybackStartGate.Decision.IGNORE,
                gate.onImaStarted());
    }

    @Test
    public void terminalCanPromoteProvenPlaybackBeforeLateImaStart() {
        GoogleImaPlaybackStartGate gate = new GoogleImaPlaybackStartGate();

        gate.onMediaPlaybackObserved();
        assertEquals(
                GoogleImaPlaybackStartGate.Decision.START_MEDIA_FALLBACK,
                gate.onTerminalBeforeImaStart());
        assertEquals(
                GoogleImaPlaybackStartGate.Decision.IGNORE,
                gate.onImaStarted());
    }

    @Test
    public void terminalWithoutAnyPlaybackEvidenceDoesNotInventAStart() {
        GoogleImaPlaybackStartGate gate = new GoogleImaPlaybackStartGate();

        assertEquals(
                GoogleImaPlaybackStartGate.Decision.IGNORE,
                gate.onTerminalBeforeImaStart());
    }
}
