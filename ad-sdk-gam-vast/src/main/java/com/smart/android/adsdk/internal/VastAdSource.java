package com.smart.android.adsdk.internal;

final class VastAdSource {
    private final int sequence;
    private final VastParsedResponse response;

    private VastAdSource(int sequence, VastParsedResponse response) {
        this.sequence = sequence;
        this.response = response;
    }

    static VastAdSource inline(int sequence, VastParsedResponse response) {
        return new VastAdSource(sequence, response);
    }

    static VastAdSource wrapper(int sequence, VastParsedResponse response) {
        return new VastAdSource(sequence, response);
    }

    int getSequence() {
        return sequence;
    }

    VastParsedResponse getResponse() {
        return response;
    }

    boolean hasInlineAd() {
        return response != null && response.hasInlineAd();
    }

    boolean hasWrapper() {
        return response != null && response.hasWrapper();
    }
}
