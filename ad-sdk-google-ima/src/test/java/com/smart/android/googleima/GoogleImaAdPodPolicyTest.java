package com.smart.android.googleima;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GoogleImaAdPodPolicyTest {
    @Test
    public void singleAdCompletesOnItsCompletedEvent() {
        assertEquals(
            GoogleImaAdPodPolicy.CompletedAction.COMPLETE_SINGLE,
            GoogleImaAdPodPolicy.onCurrentAdTerminal(1, 1, false)
        );
    }

    @Test
    public void singleSkippedAdKeepsSkippedTerminalSemantics() {
        assertEquals(
            GoogleImaAdPodPolicy.CompletedAction.SKIP_SINGLE,
            GoogleImaAdPodPolicy.onCurrentAdTerminal(1, 1, true)
        );
    }

    @Test
    public void intermediatePodAdWaitsForNextAd() {
        assertEquals(
            GoogleImaAdPodPolicy.CompletedAction.WAIT_FOR_NEXT_AD,
            GoogleImaAdPodPolicy.onCurrentAdTerminal(3, 1, false)
        );
    }

    @Test
    public void finalPodAdWaitsForAdBreakTerminalEvent() {
        assertEquals(
            GoogleImaAdPodPolicy.CompletedAction.WAIT_FOR_AD_BREAK_END,
            GoogleImaAdPodPolicy.onCurrentAdTerminal(3, 3, false)
        );
    }

    @Test
    public void missingOrInconsistentPodMetadataUsesDefensivePath() {
        assertEquals(
            GoogleImaAdPodPolicy.CompletedAction.WAIT_FOR_METADATA,
            GoogleImaAdPodPolicy.onCurrentAdTerminal(-1, -1, false)
        );
        assertEquals(
            GoogleImaAdPodPolicy.CompletedAction.WAIT_FOR_METADATA,
            GoogleImaAdPodPolicy.onCurrentAdTerminal(2, 3, false)
        );
    }

    @Test
    public void skippedIntermediateAdUsesTheSameContinuePolicy() {
        assertEquals(
            GoogleImaAdPodPolicy.CompletedAction.WAIT_FOR_NEXT_AD,
            GoogleImaAdPodPolicy.onCurrentAdTerminal(3, 2, true)
        );
    }
}
