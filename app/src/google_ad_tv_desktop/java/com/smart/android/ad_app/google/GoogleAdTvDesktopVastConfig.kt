package com.smart.android.ad_app.google

object GoogleAdTvDesktopVastConfig {
    const val AD_TAG_URL =
        "https://pubads.g.doubleclick.net/gampad/ads?iu=/23334778486/TVDesktop/video-1&description_url=https%3A%2F%2Fdraxgr.cc&tfcd=0&sz=640x480&npa=0&gdfp_req=1&unviewed_position_start=1&output=vast&env=vp&impl=s&correlator="

    const val AD_LOAD_TIMEOUT_MS = 20_000
    const val AD_STARTUP_TIMEOUT_MS = 35_000L
    const val SILENCE_CONTENT_DURATION_US = 60_000_000L
}
