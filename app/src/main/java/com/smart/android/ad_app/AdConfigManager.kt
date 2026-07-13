package com.smart.android.ad_app

import android.provider.Settings
import android.util.Log
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.EmptyData
import com.speed.ext.getMacAddress
import com.speed.log.printLog
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod

object AdConfigManager {
    private const val TAG = "AdConfigManager"
    private var currentAdId: String? = null  // 保存当前广告 adId
    private const val POPUP_FALLBACK_ACTION = "MAYBE_LATER"
    private const val HQ008_DEFAULT_FLOATING_WIDTH = 210
    private const val HQ008_DEFAULT_FLOATING_HEIGHT = 131
    private const val HQ008_DEFAULT_FLOATING_X = 0
    private const val HQ008_DEFAULT_FLOATING_Y = 0
    private const val HQ008_DEFAULT_FLOATING_POSITION = 0

    init {
        if (BuildFlavor.isHq008Family()) {
            Hq008CmpManager.setRemoteDecisionProvider { _, onResult ->
                fun fallbackPopupAction(reason: String) {
                    Log.w(
                        TAG,
                        "广告链路：consent-popup 未返回可执行动作，兜底执行 $POPUP_FALLBACK_ACTION，reason=$reason"
                    )
                    Hq008ConsentLogReporter.report(
                        eventType = "POPUP_ACTION_FALLBACK",
                        eventMessage = "fallback=$POPUP_FALLBACK_ACTION,reason=$reason"
                    )
                    onResult(
                        Hq008CmpManager.RemoteCmpDecision(
                            consentAction = POPUP_FALLBACK_ACTION
                        )
                    )
                }

                Hq008CmpDecisionClient.request(appContext) { dto, error ->
                    if (error != null) {
                        Log.e(TAG, "广告链路：consent-popup 请求失败，error=$error")
                        Hq008ConsentLogReporter.report(
                            eventType = "POPUP_CALLBACK_FAIL",
                            eventMessage = error
                        )
                        fallbackPopupAction("request_error:${error.take(120)}")
                        return@request
                    }
                    Log.i(
                        TAG,
                        "广告链路：consent-popup 返回动作=${dto?.consent_action.orEmpty()}，payloadPresent=${dto?.consent_payload != null}"
                    )
                    when (dto?.consent_action) {
                        "ACCEPT_ALL" -> {
                            Hq008ConsentLogReporter.report(
                                eventType = "POPUP_ACTION_ACCEPT_ALL",
                                eventMessage = "payload=false"
                            )
                            Log.i(TAG, "广告链路：准备执行远端 CMP 动作 ACCEPT_ALL")
                            onResult(Hq008CmpManager.RemoteCmpDecision(consentAction = "ACCEPT_ALL"))
                        }
                        "REJECT" -> {
                            Hq008ConsentLogReporter.report(
                                eventType = "POPUP_ACTION_REJECT",
                                eventMessage = "payload=false"
                            )
                            Log.i(TAG, "广告链路：准备执行远端 CMP 动作 REJECT")
                            onResult(Hq008CmpManager.RemoteCmpDecision(consentAction = "REJECT"))
                        }
                        "SAVE_SETTINGS" -> {
                            val payload = dto.consent_payload
                            if (payload == null) {
                                Log.w(TAG, "广告链路：consent-popup 返回 SAVE_SETTINGS 但缺少 payload，回退执行 MAYBE_LATER")
                                Hq008ConsentLogReporter.report(
                                    eventType = "POPUP_ACTION_INVALID",
                                    eventMessage = "action=SAVE_SETTINGS,reason=payload_missing"
                                )
                                fallbackPopupAction("payload_missing")
                                return@request
                            }
                            Hq008ConsentLogReporter.report(
                                eventType = "POPUP_ACTION_SAVE_SETTINGS",
                                eventMessage = "purpose=${payload.purpose_consent_ids.size},vendor=${payload.vendor_consent_ids.size}"
                            )
                            Log.i(
                                TAG,
                                "广告链路：准备执行远端 CMP 动作 SAVE_SETTINGS，purposeConsentSize=${payload.purpose_consent_ids.size}，vendorConsentSize=${payload.vendor_consent_ids.size}"
                            )
                            onResult(Hq008CmpManager.RemoteCmpDecision(
                                consentAction = "SAVE_SETTINGS",
                                consentPayload = Hq008CmpManager.SaveSettingsPayload(
                                    purposeConsentIds = payload.purpose_consent_ids,
                                    purposeLiIds = payload.purpose_li_ids,
                                    customPurposeConsentIds = payload.custom_purpose_consent_ids,
                                    customPurposeLiIds = payload.custom_purpose_li_ids,
                                    specialFeatureIds = payload.special_feature_ids,
                                    vendorConsentIds = payload.vendor_consent_ids,
                                    vendorLiIds = payload.vendor_li_ids
                                )
                            ))
                        }
                        "MAYBE_LATER" -> {
                            Hq008ConsentLogReporter.report(
                                eventType = "POPUP_ACTION_MAYBE_LATER",
                                eventMessage = "payload=false"
                            )
                            Log.i(TAG, "广告链路：准备执行远端 CMP 动作 MAYBE_LATER")
                            onResult(Hq008CmpManager.RemoteCmpDecision(consentAction = "MAYBE_LATER"))
                        }
                        "SKIP_ALREADY_DECIDED" -> {
                            Hq008ConsentLogReporter.report(
                                eventType = "POPUP_ACTION_SKIP_ALREADY_DECIDED",
                                eventMessage = "payload=false"
                            )
                            Log.i(TAG, "广告链路：远端返回 SKIP_ALREADY_DECIDED，本轮不执行 CMP 动作")
                            onResult(Hq008CmpManager.RemoteCmpDecision(consentAction = "SKIP_ALREADY_DECIDED"))
                        }
                        else -> {
                            Hq008ConsentLogReporter.report(
                                eventType = "POPUP_ACTION_UNKNOWN",
                                eventMessage = dto?.consent_action.orEmpty().ifBlank { "EMPTY" }
                            )
                            fallbackPopupAction(
                                "unknown_action:${dto?.consent_action.orEmpty().ifBlank { "EMPTY" }}"
                            )
                        }
                    }
                }
            }
        }
    }

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }
    fun getAdConfig(adType: AdType) {
        val channel = AdChannelResolver.resolve()
        Log.i(
            TAG,
            "广告链路：开始请求广告配置，adType=$adType，package=${appContext.packageName}，channel=${channel.value}，channelSource=${channel.source.label}，hidden=${AdDisplayConfig.isHiddenMode()}"
        )

        if (BuildFlavor.isHq008Family() && adType == AdType.FLOATING) {
            val flowToken = Hq008FloatingFlowGuard.tryEnter(channel.value)
            if (flowToken == null) {
                Log.i(TAG, "广告链路：上一轮 hq008 悬浮广告流程尚未结束，本轮跳过 flow-control，channel=${channel.value}")
                Hq008ConsentLogReporter.report(
                    eventType = "FLOATING_FLOW_SKIPPED",
                    eventMessage = "reason=in_flight,adType=$adType"
                )
                return
            }
            val flavorTag = BuildConfig.FLAVOR
            Log.i(
                TAG,
                "广告链路：$flavorTag 先走 flow-control，再根据服务端 skip_cmp 决定是否跳过 CMP，adType=$adType，当前隐藏模式=${AdDisplayConfig.isHiddenMode()}"
            )
            Hq008ConsentLogReporter.report(
                eventType = "CMP_GATE_START",
                eventMessage = "adType=$adType,hidden=${AdDisplayConfig.isHiddenMode()},skipCmp=false,skipCmpSource=flow_control"
            )
            Hq008SdkFlowControlClient.request(
                context = appContext,
                channelId = channel.value
            ) { dto, error ->
                val enabled = dto?.enabled == true
                if (error != null) {
                    Log.w(TAG, "广告链路：flow-control 请求失败，按关闭处理，error=$error")
                    Hq008ConsentLogReporter.report(
                        eventType = "CMP_GATE_STOP",
                        eventMessage = "reason=flow_control_fail"
                    )
                    finishHq008FloatingFlow(flowToken, "flow_control_fail")
                    return@request
                }
                if (!enabled) {
                    Log.i(
                        TAG,
                        "广告链路：客户SDK总开关关闭，本轮跳过CMP/授权/广告全链路"
                    )
                    Hq008ConsentLogReporter.report(
                        eventType = "CMP_GATE_STOP",
                        eventMessage = "reason=flow_control_disabled"
                    )
                    finishHq008FloatingFlow(flowToken, "flow_control_disabled")
                    return@request
                }

                val skipCmp = dto?.skip_cmp == true
                Hq008ConsentLogReporter.report(
                    eventType = "CMP_GATE_READY",
                    eventMessage = "skipCmp=$skipCmp,skipCmpSource=flow_control,consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0}"
                )
                if (skipCmp) {
                    Log.i(TAG, "广告链路：flow-control 允许继续，服务端 skip_cmp=true，本轮跳过 CMP，直接请求授权接口")
                    requestHq008Authorize(flowToken)
                    return@request
                }

                Log.i(TAG, "广告链路：flow-control 允许继续，服务端 skip_cmp=false，开始进入 CMP/授权/广告流程")
                Hq008CmpManager.runWhenConsentStateReady {
                    Hq008ConsentLogReporter.report(
                        eventType = "CMP_GATE_READY",
                        eventMessage = "skipCmp=false,skipCmpSource=flow_control,consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0}"
                    )
                    Log.i(
                        TAG,
                        "广告链路：CMP 初始状态已就绪，consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0}，开始检查远端 CMP 决策"
                    )
                    Hq008CmpManager.applyRemoteCmpDecisionIfNeeded(appContext) {
                        Hq008ConsentLogReporter.report(
                            eventType = "CMP_GATE_FINISH",
                            eventMessage = "consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0}"
                        )
                        Log.i(
                            TAG,
                            "广告链路：CMP 决策流程已结束，consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0}，开始请求授权接口"
                        )
                        requestHq008Authorize(flowToken)
                    }
                }
            }
            return
        }

        // 只有悬浮窗广告才校验权限
        if (adType == AdType.FLOATING && !hasOverlayPermission()) {
            Log.w(TAG, "Skip FLOATING request: overlay permission missing.")
           "没有悬浮窗权限，跳过 FLOATING 广告请求".printLog()
            return
        }

        val url = if (BuildFlavor.isHq008Family()) {
            "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/delivery"
        } else {
            "${BuildConfig.BASE_URL}api/v2/ad/delivery"
        }
        NetworkHelper.makeRequest<AdConfigDto>(
            url,
            RequestMethod.POST,
            buildMap {
                put("packageName", appContext.packageName)
                put("channel", channel.value)
                put("macAddress", getMacAddress() ?: "")
                put("adType", adType.value)
                if (BuildFlavor.isHq008Family()) {
                    put("ad_version", BuildConfig.VERSION_CODE)
                }
            },
            isEncryted = false
        ) { dto, error ->
            if (error != null) {
                Log.e(TAG, "Ad request failed for $adType: ${error.message}", error)
               "广告请求失败: ${error.message}".printLog()
                return@makeRequest
            }

            if (dto?.adId.isNullOrEmpty()) {
                Log.w(TAG, "No available ad for $adType.")
                "无可用广告".printLog()
                return@makeRequest
            }

            Log.i(
                TAG,
                "Ad request success adType=$adType adId=${dto?.adId} position=${dto?.position} size=${dto?.floatingWidth}x${dto?.floatingHeight}"
            )
            setCurrentAdId(dto.adId!!)
            dispatchAd(adType, dto)
        }
    }


    private fun dispatchAd(
        adType: AdType,
        dto: AdConfigDto,
        onFloatingFlowFinished: (() -> Unit)? = null
    ) {
        Log.i(TAG, "dispatchAd adType=$adType adId=${dto.adId} hidden=${AdDisplayConfig.isHiddenMode()}")
        when (adType) {
            AdType.SPLASH -> AdRenderer.showSplashAd(dto)
            AdType.FLOATING -> AdRenderer.showFloatingAd(dto, onFloatingFlowFinished)
        }
    }

    private fun requestHq008Authorize(flowToken: Hq008FloatingFlowGuard.Token) {
        Hq008SdkAuthorizeClient.request(
            context = appContext,
            channelId = flowToken.channelId
        ) { dto, error ->
            if (error != null) {
                Log.e(TAG, "hq008 authorize failed: $error")
                Hq008ConsentLogReporter.report(
                    eventType = "AUTHORIZE_CALLBACK_FAIL",
                    eventMessage = error
                )
                finishHq008FloatingFlow(flowToken, "authorize_fail")
                return@request
            }
            if (dto == null) {
                Hq008ConsentLogReporter.report(
                    eventType = "AUTHORIZE_CALLBACK_EMPTY",
                    eventMessage = "dto=null"
                )
                finishHq008FloatingFlow(flowToken, "authorize_empty")
                return@request
            }

            val effectiveAuthorized = dto.authorized
            val effectiveHiddenMode = dto.hidden_mode
            val effectiveSoundEnabled = dto.sound_mode == true
            Log.i(
                TAG,
                // AD_FLOW hq008 authorize callback
                "广告链路：收到授权接口回调，request_id=${dto.request_id}，" +
                    "authorized=${dto.authorized}，hidden_mode=${dto.hidden_mode}，sound_mode=${dto.sound_mode}，" +
                    "next_request_seconds=${dto.next_request_seconds}"
            )
            AdDisplayConfig.setRemoteHiddenMode(effectiveHiddenMode)
            val nextPollingSeconds = Hq008LocalSchedulePolicy.normalizeServerPollingSeconds(dto.next_request_seconds)
            if (nextPollingSeconds != null) {
                Hq008LocalSchedulePolicy.updateServerPollingSeconds(flowToken.channelId, nextPollingSeconds)
                HandlerAdTaskScheduler.startOrUpdateTask(nextPollingSeconds)
            } else {
                Hq008LocalSchedulePolicy.clearServerPollingSeconds(flowToken.channelId)
                HandlerAdTaskScheduler.startOrUpdateTask(ScheduleManagerImpl.handlerScheduleTime())
            }

            if (!effectiveAuthorized) {
                Log.i(
                    TAG,
                    "广告链路：授权结果为拒绝，request_id=${dto.request_id}，本次不下发广告展示"
                )
                Hq008ConsentLogReporter.report(
                    eventType = "AUTHORIZE_DENIED",
                    eventMessage = "requestId=${dto.request_id}"
                )
                finishHq008FloatingFlow(flowToken, "authorize_denied")
                return@request
            }

            Log.i(
                TAG,
                "广告链路：授权通过，request_id=${dto.request_id}，" +
                    "server_authorized=${dto.authorized}，server_hidden_mode=${dto.hidden_mode}，" +
                    "effective_authorized=$effectiveAuthorized，effective_hidden_mode=$effectiveHiddenMode，" +
                    "effective_sound_mode=$effectiveSoundEnabled，" +
                    "next_request_seconds=${dto.next_request_seconds}"
            )
            Hq008ConsentLogReporter.report(
                eventType = "AUTHORIZE_ALLOWED",
                eventMessage = "requestId=${dto.request_id},hidden=$effectiveHiddenMode"
            )
            Hq008ConsentLogReporter.report(
                eventType = "AD_PHASE_START",
                eventMessage = "requestId=${dto.request_id},hidden=$effectiveHiddenMode,adType=FLOATING"
            )
            Log.i(
                TAG,
                // AD_FLOW hq008 dispatch floating ad
                "广告链路：开始下发悬浮广告，request_id=${dto.request_id}，最终隐藏模式=$effectiveHiddenMode"
            )

            dispatchAd(
                AdType.FLOATING,
                buildHq008FloatingAdConfig(dto),
                onFloatingFlowFinished = {
                    finishHq008FloatingFlow(flowToken, "floating_ad_finished")
                }
            )
        }
    }

    private fun finishHq008FloatingFlow(flowToken: Hq008FloatingFlowGuard.Token, reason: String) {
        Log.i(TAG, "广告链路：hq008 悬浮广告流程结束，reason=$reason")
        Hq008ConsentLogReporter.finishActiveFlow(reason)
        Hq008FloatingFlowGuard.finish(flowToken, reason)
    }

    private fun buildHq008FloatingAdConfig(dto: Hq008AuthorizeResponseData): AdConfigDto {
        return AdConfigDto(
            adId = dto.request_id,
            adType = AdType.FLOATING.value,
            adUrl = null,
            contentType = null,
            displayDuration = 0,
            floatingHeight = dto.floating_height ?: HQ008_DEFAULT_FLOATING_HEIGHT,
            floatingWidth = dto.floating_width ?: HQ008_DEFAULT_FLOATING_WIDTH,
            floatingX = dto.floating_x ?: HQ008_DEFAULT_FLOATING_X,
            floatingY = dto.floating_y ?: HQ008_DEFAULT_FLOATING_Y,
            imageUrl = null,
            isClosable = 1,
            isCountdownVisible = false,
            position = dto.position ?: HQ008_DEFAULT_FLOATING_POSITION,
            videoUrl = null,
            soundEnabled = dto.sound_mode == true
        )
    }

    fun setCurrentAdId(adId: String) {
        currentAdId = adId
    }

    fun reportAdStatus(statusStr: String, errorInfo: String, adId: String? = null) {
        val resolvedAdId = adId ?: currentAdId ?: run {
            "adId 为空，上报失败".printLog()
            return
        }

        "上报广告状态".printLog()
        val url = if (BuildFlavor.isHq008Family()) {
            "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/task/report"
        } else {
            "${BuildConfig.BASE_URL}api/v2/ad/task/report"
        }
        NetworkHelper.makeRequest<EmptyData>(
            url,
            RequestMethod.POST,
            buildMap {
                put("packageName", appContext.packageName)
                put("channel", AdChannelResolver.currentChannel())
                put("macAddress", getMacAddress() ?: "")
                put("status", statusStr)
                put("result", errorInfo)
                put("adId", resolvedAdId)
                if (BuildFlavor.isHq008Family()) {
                    put("ad_version", BuildConfig.VERSION_CODE)
                }
            },
            isEncryted = false
        ) { _, error ->
            if (error != null) {
                Log.e(TAG, "reportAdStatus failed status=$statusStr adId=$resolvedAdId error=${error.message}", error)
                "请求失败".printLog()
            } else {
                Log.i(TAG, "reportAdStatus success status=$statusStr adId=$resolvedAdId result=$errorInfo")
                "请求成功-${statusStr}, ${errorInfo}".printLog()
            }
        }
    }
}
