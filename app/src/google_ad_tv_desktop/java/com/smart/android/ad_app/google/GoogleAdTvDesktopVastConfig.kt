package com.smart.android.ad_app.google

object GoogleAdTvDesktopVastConfig {
    const val AD_TAG_URL =
        "https://pubads.g.doubleclick.net/gampad/ads?iu=/23334778486/TVDesktop/video-1&description_url=https%3A%2F%2Fghtfor.cc&tfcd=0&npa=0&ad_type=audio_video&sz=1x1%7C300x250%7C320x480%7C400x300%7C640x360%7C640x430%7C640x480&gdfp_req=1&unviewed_position_start=1&output=vast&env=vp&impl=s&plcmt=1&vpmute=0&app_package=io.android.launcher.tv.desktop&correlator="

    const val AD_LOAD_TIMEOUT_MS = 20_000
    const val AD_STARTUP_TIMEOUT_MS = 35_000L
    const val SILENCE_CONTENT_DURATION_US = 60_000_000L

    fun resolveAdTagUrl(soundEnabled: Boolean): String {
        val muteValue = if (soundEnabled) "0" else "1"
        return AD_TAG_URL.replace(Regex("([?&])vpmute=[^&]*")) { match ->
            "${match.groupValues[1]}vpmute=$muteValue"
        }
    }
}
