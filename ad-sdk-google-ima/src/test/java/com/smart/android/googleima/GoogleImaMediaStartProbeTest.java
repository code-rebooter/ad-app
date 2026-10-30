package com.smart.android.googleima;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GoogleImaMediaStartProbeTest {
    @Test
    public void requiresActiveAdPlaybackAndRealPositionMovement() {
        GoogleImaMediaStartProbe probe = new GoogleImaMediaStartProbe();
        probe.arm(0L);

        assertFalse(probe.observe(false, true, 500L));
        assertFalse(probe.observe(true, false, 500L));
        assertFalse(probe.observe(true, true, 99L));
        assertTrue(probe.observe(true, true, 100L));
    }

    @Test
    public void positionResetRequiresFreshForwardProgress() {
        GoogleImaMediaStartProbe probe = new GoogleImaMediaStartProbe();
        probe.arm(19_950L);

        assertFalse(probe.observe(true, true, 25L));
        assertFalse(probe.observe(true, true, 75L));
        assertTrue(probe.observe(true, true, 125L));
    }

    @Test
    public void resetDisarmsTheProbe() {
        GoogleImaMediaStartProbe probe = new GoogleImaMediaStartProbe();
        probe.arm(0L);
        probe.reset();

        assertFalse(probe.observe(true, true, 1_000L));
    }
}
