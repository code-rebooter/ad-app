package com.smart.android.adsdk.internal;

/** Resolve the shared API domain from the original host channel exactly once. */
final class FusionChannels {
    final String googleChannel;
    final String tclChannel;
    final String apiBaseUrl;

    FusionChannels(String hostChannel) {
        if (hostChannel == null || hostChannel.trim().isEmpty()) {
            throw new IllegalArgumentException("adChannelId must not be blank");
        }
        googleChannel = hostChannel.trim();
        tclChannel = googleChannel + "_TCL";
        if ("GOOGLE_AD_TV_LOCKSCREEN_HQ002".equalsIgnoreCase(googleChannel)) {
            apiBaseUrl = "https://api.kartna.cc/";
        } else if ("GOOGLE_AD_TV_CVTE".equalsIgnoreCase(googleChannel)) {
            apiBaseUrl = "https://api.xartek.cc/";
        } else {
            apiBaseUrl = "https://api.kytira.cc/";
        }
    }
}
