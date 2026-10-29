package com.smart.android.googleima;

final class GoogleImaAdPodPolicy {
    enum CompletedAction {
        COMPLETE_SINGLE,
        SKIP_SINGLE,
        WAIT_FOR_NEXT_AD,
        WAIT_FOR_AD_BREAK_END,
        WAIT_FOR_METADATA
    }

    private GoogleImaAdPodPolicy() {}

    static CompletedAction onCurrentAdTerminal(
            int totalAds,
            int adPosition,
            boolean skipped) {
        if (totalAds == 1 && adPosition == 1) {
            return skipped ? CompletedAction.SKIP_SINGLE : CompletedAction.COMPLETE_SINGLE;
        }
        if (totalAds > 1 && adPosition > 0 && adPosition < totalAds) {
            return CompletedAction.WAIT_FOR_NEXT_AD;
        }
        if (totalAds > 1 && adPosition == totalAds) {
            return CompletedAction.WAIT_FOR_AD_BREAK_END;
        }
        return CompletedAction.WAIT_FOR_METADATA;
    }
}
