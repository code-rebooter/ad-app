package com.smart.android.ad_app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Hq008StressRunStateTest {

    @Test
    public void requestCannotOverlapAndStopsAtLimit() {
        Hq008StressRunState state = new Hq008StressRunState(
                Hq008StressRunState.Mode.IMMEDIATE_RELEASE,
                2
        );

        assertTrue(state.start());
        assertTrue(state.tryStartRequest());
        assertFalse(state.tryStartRequest());

        assertTrue(state.finishRequest());
        assertTrue(state.canStartRequest());
        assertTrue(state.tryStartRequest());
        assertTrue(state.finishRequest());

        assertFalse(state.isRunning());
        assertFalse(state.canStartRequest());
        assertFalse(state.tryStartRequest());
    }

    @Test
    public void terminalCallbackIsAcceptedOnlyOnce() {
        Hq008StressRunState state = new Hq008StressRunState(
                Hq008StressRunState.Mode.CUSTOMER_LIFECYCLE,
                0
        );

        state.start();
        state.tryStartRequest();

        assertTrue(state.finishRequest());
        assertFalse(state.finishRequest());
    }

    @Test
    public void onlyImmediateModeReleasesOnTerminal() {
        Hq008StressRunState immediate = new Hq008StressRunState(
                Hq008StressRunState.Mode.IMMEDIATE_RELEASE,
                0
        );
        Hq008StressRunState customer = new Hq008StressRunState(
                Hq008StressRunState.Mode.CUSTOMER_LIFECYCLE,
                0
        );
        Hq008StressRunState uiOnly = new Hq008StressRunState(
                Hq008StressRunState.Mode.UI_ONLY,
                0
        );

        assertTrue(immediate.shouldReleaseOnTerminal());
        assertFalse(customer.shouldReleaseOnTerminal());
        assertFalse(uiOnly.shouldReleaseOnTerminal());
    }
}
