package com.smart.android.ad_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.smart.android.ad_app.AdLocalLog as Log

class AdPlaybackStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (!AdPlaybackAvailability.isGateEnabled() ||
            intent?.action != AdPlaybackAvailability.ACTION_PLAYBACK_STATUS ||
            !intent.hasExtra(AdPlaybackAvailability.EXTRA_CAN_PLAY_AD)
        ) {
            return
        }

        val canPlayAd = intent.getBooleanExtra(
            AdPlaybackAvailability.EXTRA_CAN_PLAY_AD,
            false
        )
        val reason = intent.getStringExtra(AdPlaybackAvailability.EXTRA_REASON)
            .orEmpty()
            .ifBlank { "plugin_status" }
        val eventTime = intent.getLongExtra(
            AdPlaybackAvailability.EXTRA_EVENT_TIME,
            SystemClock.elapsedRealtime()
        )
        if (!AdPlaybackAvailability.updateStatus(canPlayAd, reason, eventTime)) {
            Log.w(TAG, "忽略过期的广告播放状态广播：eventTime=$eventTime，reason=$reason")
            return
        }

        Log.i(
            TAG,
            "收到极鑫广告播放状态广播：canPlayAd=$canPlayAd，reason=$reason，eventTime=$eventTime"
        )
        if (canPlayAd) {
            return
        }

        val cancelled = Hq008FloatingFlowGuard.cancelActive("external_playback_denied:$reason")
        cancelled?.requestId?.let {
            Hq008ConsentLogReporter.finishActiveFlow("external_playback_denied:$reason", it)
            AdManagerImpl.destroyAd(it)
        }
        AdRenderer.stopFloatingAd("external_playback_denied:$reason")
    }

    private companion object {
        const val TAG = "AdPlaybackStatus"
    }
}
