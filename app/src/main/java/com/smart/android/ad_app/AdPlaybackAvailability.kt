package com.smart.android.ad_app

object AdPlaybackAvailability {
    const val ACTION_PLAYBACK_STATUS = "io.permission.AD_PLAYBACK_STATUS"
    const val EXTRA_CAN_PLAY_AD = "canPlayAd"
    const val EXTRA_REASON = "reason"
    const val EXTRA_EVENT_TIME = "eventTime"

    private const val TARGET_FLAVOR = "ad_ytx01_jx"
    private val lock = Any()

    @Volatile
    private var state = State.UNKNOWN

    @Volatile
    private var latestEventTime = Long.MIN_VALUE

    @Volatile
    private var latestReason = "status_unknown"

    @JvmStatic
    fun isGateEnabled(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == TARGET_FLAVOR
    }

    @JvmStatic
    fun isPlaybackAllowed(flavor: String = BuildConfig.FLAVOR): Boolean {
        if (Hq002SignalStatusGate.isEnabled(flavor)) {
            return Hq002SignalStatusGate.isPlaybackAllowed(flavor)
        }
        return !isGateEnabled(flavor) || state == State.ALLOWED
    }

    @JvmStatic
    fun updateStatus(canPlayAd: Boolean, reason: String, eventTime: Long): Boolean {
        synchronized(lock) {
            if (eventTime < latestEventTime) {
                return false
            }
            latestEventTime = eventTime
            latestReason = reason
            state = if (canPlayAd) State.ALLOWED else State.DENIED
            return true
        }
    }

    fun currentReason(): String = if (Hq002SignalStatusGate.isEnabled()) {
        Hq002SignalStatusGate.currentReason()
    } else {
        latestReason
    }

    @JvmStatic
    fun resetForTest() {
        synchronized(lock) {
            state = State.UNKNOWN
            latestEventTime = Long.MIN_VALUE
            latestReason = "status_unknown"
        }
    }

    private enum class State {
        UNKNOWN,
        ALLOWED,
        DENIED
    }
}
