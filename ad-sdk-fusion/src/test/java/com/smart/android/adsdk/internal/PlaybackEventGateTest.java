package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackEventGateTest {

    @Test
    public void loadedStartedAndTerminalSignalsAreDeduplicated() {
        PlaybackEventGate gate = new PlaybackEventGate();

        assertFalse(gate.hasStarted());
        assertTrue(gate.markLoaded());
        assertFalse(gate.markLoaded());
        assertTrue(gate.markStarted());
        assertTrue(gate.hasStarted());
        assertFalse(gate.markStarted());
        assertTrue(gate.markTerminal());
        assertFalse(gate.markTerminal());
    }

    @Test
    public void containerBecomesReadyOnlyAfterStartAndFirstFrame() {
        PlaybackEventGate gate = new PlaybackEventGate();

        gate.markFirstFrame();
        assertFalse(gate.consumeRevealReady());
        gate.markStarted();
        assertTrue(gate.consumeRevealReady());
        assertFalse(gate.consumeRevealReady());
    }

    @Test
    public void terminalGateNeverRevealsContainer() {
        PlaybackEventGate gate = new PlaybackEventGate();

        gate.markStarted();
        gate.markFirstFrame();
        gate.markTerminal();

        assertFalse(gate.consumeRevealReady());
    }
}
