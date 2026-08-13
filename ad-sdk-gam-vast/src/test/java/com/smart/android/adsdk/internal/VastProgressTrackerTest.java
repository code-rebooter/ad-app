package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VastProgressTrackerTest {
    @Test
    public void matchesAbsoluteAndPercentageOffsets() {
        VastProgressTracker fiveSeconds = VastProgressTracker.absolute(
            "https://track.test/5",
            5_000L
        );
        VastProgressTracker seventyFivePercent = VastProgressTracker.percentage(
            "https://track.test/75",
            0.75f
        );

        assertFalse(fiveSeconds.shouldFire(10_000L, 4_900L));
        assertTrue(fiveSeconds.shouldFire(10_000L, 5_000L));
        assertFalse(seventyFivePercent.shouldFire(10_000L, 7_400L));
        assertTrue(seventyFivePercent.shouldFire(10_000L, 7_500L));
    }
}
