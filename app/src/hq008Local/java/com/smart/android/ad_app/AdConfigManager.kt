package com.smart.android.ad_app

import android.provider.Settings
import android.util.Log

object AdConfigManager {
    private const val TAG = "AdConfigManager"

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

    fun getAdConfig(adType: AdType) {
        val channel = AdChannelResolver.resolve()
        Log.i(
            TAG,
            "Requesting TCL ad, type=$adType, package=${appContext.packageName}, channel=${channel.value}"
        )

        when (adType) {
            AdType.SPLASH -> requestSplashAd()
            AdType.FLOATING -> requestFloatingAd(channel.value)
        }
    }

    private fun requestSplashAd() {
        Hq008CmpManager.ensureConsent { cmpReady ->
            if (!cmpReady) {
                Log.w(TAG, "CMP did not complete; continuing this splash request")
            }
            AdRenderer.showAd(AdType.SPLASH)
        }
    }

    private fun requestFloatingAd(channelId: String) {
        if (!hasOverlayPermission()) {
            Log.w(TAG, "Skip HQ008 FLOATING request: overlay permission missing")
            return
        }

        val flowToken = Hq008FloatingFlowGuard.tryEnter(channelId) ?: run {
            Log.i(TAG, "A visible HQ008 floating-ad request is already in progress")
            return
        }

        Hq008SdkFlowControlClient.request(appContext, channelId) { dto, error ->
            if (error != null || dto?.enabled != true) {
                Log.w(TAG, "flow-control stopped this request, error=${error.orEmpty()}")
                finishFloatingFlow(flowToken, if (error != null) "flow_control_fail" else "flow_control_disabled")
                return@request
            }

            Hq008CmpManager.ensureConsent { cmpReady ->
                if (!cmpReady) {
                    Log.w(TAG, "CMP did not complete; continuing to authorize")
                }
                requestAuthorize(flowToken)
            }
        }
    }

    private fun requestAuthorize(flowToken: Hq008FloatingFlowGuard.Token) {
        Hq008SdkAuthorizeClient.request(appContext, flowToken.channelId) { dto, error ->
            if (error != null || dto == null) {
                Log.w(TAG, "authorize failed, error=${error.orEmpty()}")
                finishFloatingFlow(flowToken, "authorize_fail")
                return@request
            }

            val nextPollingSeconds = Hq008LocalSchedulePolicy.normalizeServerPollingSeconds(
                dto.next_request_seconds
            )
            if (nextPollingSeconds != null) {
                Hq008LocalSchedulePolicy.updateServerPollingSeconds(flowToken.channelId, nextPollingSeconds)
                HandlerAdTaskScheduler.startOrUpdateTask(nextPollingSeconds)
            } else {
                Hq008LocalSchedulePolicy.clearServerPollingSeconds(flowToken.channelId)
                HandlerAdTaskScheduler.startOrUpdateTask(ScheduleManagerImpl.handlerScheduleTime())
            }

            if (!dto.authorized) {
                Log.i(TAG, "authorize denied request_id=${dto.request_id}")
                finishFloatingFlow(flowToken, "authorize_denied")
                return@request
            }

            Log.i(TAG, "authorize allowed request_id=${dto.request_id}")
            AdRenderer.showAd(
                adType = AdType.FLOATING,
                adId = dto.request_id,
                onFloatingFlowFinished = {
                    finishFloatingFlow(flowToken, "floating_ad_finished")
                }
            )
        }
    }

    private fun finishFloatingFlow(flowToken: Hq008FloatingFlowGuard.Token, reason: String) {
        Log.i(TAG, "HQ008 floating flow finished, reason=$reason")
        Hq008FloatingFlowGuard.finish(flowToken, reason)
    }
}
