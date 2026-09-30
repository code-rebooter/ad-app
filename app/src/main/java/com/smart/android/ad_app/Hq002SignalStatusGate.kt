package com.smart.android.ad_app

import com.smart.android.ad_app.AdLocalLog as Log

internal object Hq002SignalStatusGate {
    private const val TARGET_FLAVOR = "google_ad_tv_lockscreen_hq002"
    private const val PROPERTY_NAME = "sys.current.signal.status"
    private const val TAG = "Hq002SignalStatus"

    private val propertyGetter by lazy {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
    }

    @Volatile
    private var latestReason = "signal_status_unknown"

    fun isEnabled(flavor: String = BuildConfig.FLAVOR): Boolean = flavor == TARGET_FLAVOR

    fun isPlaybackAllowed(flavor: String = BuildConfig.FLAVOR): Boolean {
        if (!isEnabled(flavor)) return true

        // Never cache the value: the firmware may change it during an ad request or playback.
        val status = try {
            (propertyGetter.invoke(null, PROPERTY_NAME, "") as? String)?.trim()
        } catch (error: Exception) {
            return recordDecision(false, "signal_status_read_failed:${(error.cause ?: error).javaClass.simpleName}")
        }
        return when {
            status.isNullOrEmpty() -> recordDecision(false, "signal_status_unavailable")
            status == "0" -> recordDecision(false, "signal_status=0")
            else -> recordDecision(true, "signal_status=$status")
        }
    }

    fun currentReason(): String = latestReason

    private fun recordDecision(allowed: Boolean, reason: String): Boolean {
        if (latestReason != reason) {
            latestReason = reason
            Log.i(TAG, "广告播放状态：allowed=$allowed，reason=$reason")
        }
        return allowed
    }
}
