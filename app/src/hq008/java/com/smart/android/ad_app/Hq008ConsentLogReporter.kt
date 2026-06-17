package com.smart.android.ad_app

import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.smart.android.ad_app.bean.EmptyData
import com.speed.ext.getMacAddress
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod

internal object Hq008ConsentLogReporter {
    fun interface DebugTraceListener {
        fun onTraceEvent(eventType: String, rawEventMessage: String)
    }

    private const val TAG = "Hq008ConsentLog"
    private const val MAX_MESSAGE_LENGTH = 512
    private const val MAX_TRACE_STEPS = 80
    private const val MAX_TRACE_LOG_LENGTH = 24_000
    private const val FLOW_SUMMARY_EVENT = "CMP_FLOW_SUMMARY"
    private const val AD_FLOW_SUMMARY_EVENT = "AD_FLOW_SUMMARY"
    private val criticalAdLogEvents = setOf(
        "AD_GDPR_CONSENT_ATTACHED",
        "AD_SDK_HTTP_CAPTURE",
        "USER_ACTION_START",
        "USER_ACTION_SUCCESS",
        "USER_ACTION_FAIL"
    )
    private val consentLogUrl = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/consent-log-report"
    private val gson = Gson()
    private val lock = Any()
    private val traceSession = Hq008ConsentTraceSession(
        maxTraceSteps = MAX_TRACE_STEPS
    )
    private val debugTraceListeners = linkedSetOf<DebugTraceListener>()

    fun addDebugTraceListener(listener: DebugTraceListener) {
        synchronized(lock) {
            debugTraceListeners += listener
        }
    }

    fun removeDebugTraceListener(listener: DebugTraceListener) {
        synchronized(lock) {
            debugTraceListeners -= listener
        }
    }

    fun updatePopupLogEnabled(enabled: Boolean) {
        synchronized(lock) {
            traceSession.updatePopupLogEnabled(enabled)
        }
        Log.i(TAG, "consent-log-report 开关更新，popupLogEnabled=$enabled")
    }

    fun hasActiveFlow(): Boolean {
        return synchronized(lock) {
            traceSession.hasPendingSteps()
        }
    }

    fun finishActiveFlow(reason: String) {
        val uploadPayload = synchronized(lock) {
            val payload = traceSession.forceFinish(
                nowMs = SystemClock.elapsedRealtime(),
                eventType = "FLOW_GUARD_FINISH",
                rawEventMessage = "reason=$reason",
                eventMessage = localizeEventMessage("FLOW_GUARD_FINISH", "reason=$reason"),
                adLog = null
            )?.let(::buildUploadPayload)
            if (payload == null) {
                traceSession.resetPopupLogEnabled()
            }
            payload
        }
        uploadPayload?.let { send(it) }
    }

    fun report(
        eventType: String,
        eventMessage: String,
        adLog: String? = null
    ) {
        val rawMessage = eventMessage.take(MAX_MESSAGE_LENGTH)
        val localizedMessage = localizeEventMessage(eventType, rawMessage)
        val (uploadPayload, listenersSnapshot) = synchronized(lock) {
            val uploadPayload = traceSession.record(
                nowMs = SystemClock.elapsedRealtime(),
                eventType = eventType,
                rawEventMessage = rawMessage,
                eventMessage = localizedMessage,
                adLog = adLog
            )?.let(::buildUploadPayload)
            uploadPayload to debugTraceListeners.toList()
        }

        listenersSnapshot.forEach { listener ->
            runCatching { listener.onTraceEvent(eventType, rawMessage) }
                .onFailure { error ->
                    Log.w(TAG, "debug trace listener failed eventType=$eventType error=${error.message}")
                }
        }
        if (uploadPayload == null) {
            return
        }
        send(uploadPayload)
    }

    private fun send(payload: ConsentLogUploadPayload) {
        if (!payload.popupLogEnabled) {
            Log.i(
                TAG,
                "consent-log-report 开关关闭，本轮不上报 finalEventType=${payload.eventType}"
            )
            return
        }
        NetworkHelper.makeRequest<EmptyData>(
            url = consentLogUrl,
            method = RequestMethod.POST,
            params = linkedMapOf(
                "channel_id" to BuildConfig.CHANNEL,
                "mac" to (safeMacAddress() ?: "00:00:00:00:00:00"),
                "ad_version" to BuildConfig.VERSION_CODE,
                "event_type" to payload.eventType,
                "event_message" to payload.eventMessage,
                "ad_log" to payload.adLog
            ),
            isEncryted = false,
            useDomainSwitch = false,
        ) { _, error ->
            if (error != null) {
                Log.w(TAG, "consent-log-report failed finalEventType=${payload.eventType} error=${error.message}")
            }
        }
    }

    private fun buildUploadPayload(
        snapshot: Hq008ConsentTraceSession.TraceSnapshot
    ): ConsentLogUploadPayload {
        val stepsForUpload = buildStepsForUpload(snapshot)
        val summaryStep = stepsForUpload.lastOrNull {
            it.eventType == FLOW_SUMMARY_EVENT || it.eventType == AD_FLOW_SUMMARY_EVENT
        }
        val finalStep = stepsForUpload.lastOrNull { it.eventType == snapshot.finalEventType }
        val finalEventMessage = finalStep?.eventMessage.orEmpty()
        val summaryStepMessage = summaryStep?.eventMessage.orEmpty()
        val traceSummary = buildTraceSummary(
            finalEventType = snapshot.finalEventType,
            finalEventMessage = finalEventMessage,
            stepsForUpload = stepsForUpload
        )

        var adLogPayload = gson.toJson(traceSummary)
        if (adLogPayload.length > MAX_TRACE_LOG_LENGTH) {
            val compactedTraceSummary = buildTraceSummary(
                finalEventType = snapshot.finalEventType,
                finalEventMessage = finalEventMessage,
                stepsForUpload = stepsForUpload,
                includeAdLog = { step -> step.eventType in criticalAdLogEvents }
            ).apply {
                put("traceCompacted", true)
                put("traceCompactedMode", "critical_ad_log_only")
            }
            adLogPayload = gson.toJson(compactedTraceSummary)
        }
        if (adLogPayload.length > MAX_TRACE_LOG_LENGTH) {
            val compactedTraceSummary = buildTraceSummary(
                finalEventType = snapshot.finalEventType,
                finalEventMessage = finalEventMessage,
                stepsForUpload = stepsForUpload,
                includeAdLog = { false }
            ).apply {
                put("traceCompacted", true)
                put("traceCompactedMode", "steps_only")
            }
            adLogPayload = gson.toJson(compactedTraceSummary)
        }

        val summaryMessage = buildString {
            append("终态=")
            append(describeTerminalEvent(snapshot.finalEventType))
            append("，步骤数=")
            append(stepsForUpload.size)
            val conclusion = summaryStepMessage.ifBlank { finalEventMessage }
            if (conclusion.isNotBlank()) {
                append("，结论=")
                append(conclusion.take(220))
            }
        }.take(MAX_MESSAGE_LENGTH)

        return ConsentLogUploadPayload(
            eventType = snapshot.finalEventType,
            eventMessage = summaryMessage,
            adLog = adLogPayload,
            popupLogEnabled = snapshot.popupLogEnabled
        )
    }

    private fun buildTraceSummary(
        finalEventType: String,
        finalEventMessage: String,
        stepsForUpload: List<TraceStep>,
        includeAdLog: (TraceStep) -> Boolean = { true }
    ): LinkedHashMap<String, Any> {
        return linkedMapOf(
            "traceVersion" to 2,
            "finalEventType" to finalEventType,
            "finalEventMessage" to finalEventMessage.take(MAX_MESSAGE_LENGTH),
            "stepCount" to stepsForUpload.size,
            "steps" to stepsForUpload.map { step ->
                linkedMapOf<String, Any>(
                    "index" to step.index,
                    "elapsedMs" to step.elapsedMs,
                    "eventType" to step.eventType,
                    "eventMessage" to step.eventMessage
                ).apply {
                    if (includeAdLog(step)) {
                        step.adLog?.let { put("adLog", parseAdLog(it)) }
                    }
                }
            }
        )
    }

    private fun buildStepsForUpload(
        snapshot: Hq008ConsentTraceSession.TraceSnapshot
    ): List<TraceStep> {
        if (snapshot.steps.isEmpty()) {
            return emptyList()
        }
        val steps = snapshot.steps.map {
            TraceStep(
                index = it.index,
                elapsedMs = it.elapsedMs,
                eventType = it.eventType,
                rawEventMessage = it.rawEventMessage,
                eventMessage = it.eventMessage,
                adLog = it.adLog
            )
        }
        val summaryStep = if (isAdPhaseFlow(steps)) {
            buildAdFlowSummaryStep(steps, snapshot.finalEventType)
        } else {
            buildFlowSummaryStep(steps, snapshot.finalEventType)
        }
        return if (summaryStep != null) {
            steps + summaryStep
        } else {
            steps
        }
    }

    private fun isAdPhaseFlow(steps: List<TraceStep>): Boolean {
        return steps.any { step ->
            step.eventType == "AD_PHASE_START" ||
                step.eventType == "AD_REQUESTED" ||
                step.eventType == "AD_LOADED" ||
                step.eventType == "AD_STARTED" ||
                step.eventType == "AD_PHASE_COMPLETED" ||
                step.eventType == "AD_PHASE_ERROR" ||
                step.eventType == "AD_PHASE_TIMEOUT" ||
                step.eventType == "AD_PHASE_CANCELLED" ||
                step.eventType == "FLOW_GUARD_FINISH"
        }
    }

    private fun buildAdFlowSummaryStep(
        steps: List<TraceStep>,
        finalEventType: String
    ): TraceStep? {
        if (steps.isEmpty()) {
            return null
        }
        val requestId = findLastValue(steps, "AD_PHASE_START", "requestId")
            ?: findLastValue(steps, "AD_REQUESTED", "requestId")
        val hidden = findLastValue(steps, "AD_PHASE_START", "hidden")
            ?: findLastValue(steps, "AD_REQUESTED", "hidden")
        val stage = findLastValue(steps, "AD_PHASE_ERROR", "stage")
            ?: findLastValue(steps, "AD_PHASE_TIMEOUT", "stage")
            ?: findLastValue(steps, "AD_PHASE_CANCELLED", "stage")
        val errorCode = findLastValue(steps, "AD_PHASE_ERROR", "errorCode")
        val reason = findLastValue(steps, "AD_PHASE_ERROR", "reason")
            ?: findLastValue(steps, "AD_PHASE_TIMEOUT", "reason")
            ?: findLastValue(steps, "AD_PHASE_CANCELLED", "reason")
            ?: findLastValue(steps, "FLOW_GUARD_FINISH", "reason")
        val error = findLastValue(steps, "AD_PHASE_ERROR", "error")

        val requestedSummary = if (hasEvent(steps, "AD_REQUESTED")) "播放请求=已发起" else "播放请求=未发起"
        val loadedSummary = if (hasEvent(steps, "AD_LOADED")) "素材加载=已完成" else "素材加载=未完成"
        val startedSummary = if (hasEvent(steps, "AD_STARTED")) "开始播放=已回调" else "开始播放=未回调"
        val hiddenSummary = hidden?.let { "隐藏模式=${translateBooleanToken(it)}" }
        val requestSummary = requestId?.let { "请求ID=$it" }
        val stageSummary = stage?.let { "阶段=${translateStageToken(it)}" }
        val reasonSummary = reason?.let { "原因=${translateReasonToken(it)}" }
        val rawErrorDetails = buildList {
            errorCode
                ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                ?.let { add("错误码=$it") }
            error
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("unknown", ignoreCase = true) &&
                        !it.equals("unknown error", ignoreCase = true)
                }
                ?.let { add("错误=${normalizeErrorToken(it)}") }
        }
        val terminalSummary = buildString {
            append("终态=")
            append(describeTerminalEvent(finalEventType))
            if (!stageSummary.isNullOrBlank()) {
                append("（")
                append(stageSummary)
                if (rawErrorDetails.isNotEmpty()) {
                    append("，")
                    append(rawErrorDetails.joinToString("，"))
                } else if (!reasonSummary.isNullOrBlank()) {
                    append("，")
                    append(reasonSummary)
                }
                append("）")
            } else if (rawErrorDetails.isNotEmpty()) {
                append("（")
                append(rawErrorDetails.joinToString("，"))
                append("）")
            } else if (!reasonSummary.isNullOrBlank()) {
                append("（")
                append(reasonSummary)
                append("）")
            }
        }
        val summaryMessage = listOfNotNull(
            "广告阶段=已进入",
            requestSummary,
            hiddenSummary,
            requestedSummary,
            loadedSummary,
            startedSummary,
            terminalSummary
        ).joinToString("，").take(MAX_MESSAGE_LENGTH)
        val lastElapsed = steps.lastOrNull()?.elapsedMs ?: 0L
        return TraceStep(
            index = steps.size + 1,
            elapsedMs = lastElapsed,
            eventType = AD_FLOW_SUMMARY_EVENT,
            rawEventMessage = summaryMessage,
            eventMessage = summaryMessage,
            adLog = null
        )
    }

    private fun buildFlowSummaryStep(
        steps: List<TraceStep>,
        finalEventType: String
    ): TraceStep? {
        if (steps.isEmpty()) {
            return null
        }
        val popupNeedShow = findLastValue(steps, "CMP_SNAPSHOT_LOAD_RESULT", "needShowPop")
        val decisionEligible = findLastValue(steps, "CMP_GATE_DECISION_ELIGIBILITY", "decisionEligible")
            ?: findLastValue(steps, "CMP_GATE_EVALUATED", "decisionEligible")
        val gateSkipReason = findLastValue(steps, "CMP_GATE_SKIP", "reason")
        val triggerReasons = buildTriggerReasons(steps)
        val popupSummary = when {
            hasEvent(steps, "POPUP_CALLBACK_FAIL") -> "popup请求=请求失败"
            hasEvent(steps, "POPUP_REQUEST_SUCCESS") -> "popup请求=已请求并返回动作"
            hasEvent(steps, "POPUP_REQUEST_START") -> "popup请求=已请求，等待返回"
            gateSkipReason != null || popupNeedShow == "false" -> "popup请求=未触发"
            else -> "popup请求=未触发"
        }
        val gateSummary = when {
            gateSkipReason != null -> "门禁结果=跳过（${translateReasonToken(gateSkipReason)}）"
            decisionEligible == "true" -> "门禁结果=允许继续决策"
            decisionEligible == "false" -> "门禁结果=不允许继续决策"
            else -> "门禁结果=未知"
        }
        val popupNeedSummary = when (popupNeedShow) {
            "true" -> "SDK判定=需要CMP弹窗"
            "false" -> "SDK判定=无需CMP弹窗"
            else -> "SDK判定=未知"
        }
        val decisionAction = resolveDecisionActionSummary(steps)
        val actionExecution = resolveActionExecutionSummary(steps)
        val userActionSummary = resolveUserActionSummary(steps)
        val consentReportSummary = resolveConsentReportSummary(steps)
        val authorizeSummary = buildFlowTerminalSummary(steps, finalEventType)
        val summaryMessage = listOfNotNull(
            popupNeedSummary,
            gateSummary,
            triggerReasons,
            popupSummary,
            decisionAction,
            actionExecution,
            userActionSummary,
            consentReportSummary,
            authorizeSummary
        ).joinToString("，").take(MAX_MESSAGE_LENGTH)
        val lastElapsed = steps.lastOrNull()?.elapsedMs ?: 0L
        return TraceStep(
            index = steps.size + 1,
            elapsedMs = lastElapsed,
            eventType = FLOW_SUMMARY_EVENT,
            rawEventMessage = summaryMessage,
            eventMessage = summaryMessage,
            adLog = null
        )
    }

    private fun buildFlowTerminalSummary(
        steps: List<TraceStep>,
        finalEventType: String
    ): String {
        val terminalLabel = describeTerminalEvent(finalEventType)
        val finalStep = steps.lastOrNull { it.eventType == finalEventType }
        val detail = when (finalEventType) {
            "CMP_GATE_STOP",
            "AUTHORIZE_CALLBACK_FAIL",
            "AUTHORIZE_CALLBACK_EMPTY" -> finalStep?.rawEventMessage
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    if (raw.contains("=")) {
                        localizeEventMessage(finalEventType, raw)
                    } else {
                        localizeFreeText(finalEventType, raw)
                    }
                }
            else -> null
        }
        return if (detail.isNullOrBlank()) {
            "授权结果=$terminalLabel"
        } else {
            "授权结果=$terminalLabel（$detail）"
        }
    }

    private fun buildTriggerReasons(steps: List<TraceStep>): String? {
        val reasons = mutableListOf<String>()
        if (findLastValue(steps, "CMP_GATE_EVALUATED", "localInvalid") == "true") {
            reasons += "本地同意状态无效"
        }
        if (findLastValue(steps, "CMP_GATE_EVALUATED", "remoteHasNewCampaign") == "true") {
            reasons += "远端有新活动"
        }
        if (findLastValue(steps, "CMP_GATE_EVALUATED", "remoteRecoveryEligible") == "true") {
            reasons += "可直接恢复远端同意状态"
        }
        if (findLastValue(steps, "CMP_GATE_EVALUATED", "missingRequiredSeed") == "true") {
            reasons += "缺少活动种子"
        }
        return reasons.takeIf { it.isNotEmpty() }
            ?.joinToString("、")
            ?.let { "触发原因=$it" }
    }

    private fun resolveDecisionActionSummary(steps: List<TraceStep>): String {
        val action = findLastValue(steps, "CMP_DECISION_START", "action")
            ?: findLastPopupAction(steps)
            ?: findLastValue(steps, "CMP_DECISION_SKIPPED", "action")
        return if (action.isNullOrBlank()) {
            "远端动作=未下发"
        } else {
            "远端动作=${translateActionToken(action)}"
        }
    }

    private fun findLastPopupAction(steps: List<TraceStep>): String? {
        return steps.asReversed()
            .firstOrNull { step -> step.eventType.startsWith("POPUP_ACTION_") }
            ?.eventType
            ?.removePrefix("POPUP_ACTION_")
    }

    private fun resolveActionExecutionSummary(steps: List<TraceStep>): String {
        if (hasEvent(steps, "CMP_DECISION_FALLBACK")) {
            return "动作执行=回退稍后再说"
        }
        findLastValue(steps, "SDK_ACTION_PATH", "path")?.let { path ->
            return "动作执行=${translatePathToken(path)}"
        }
        findLastValue(steps, "CMP_MAYBE_LATER_PATH", "path")?.let { path ->
            return "动作执行=${translatePathToken(path)}"
        }
        if (hasEvent(steps, "CMP_REMOTE_RECOVERY")) {
            return "动作执行=恢复远端已有同意状态"
        }
        if (hasEvent(steps, "CMP_DECISION_BUILD_FAIL")) {
            return "动作执行=构建动作种子失败"
        }
        if (hasEvent(steps, "CMP_SEED_MISSING")) {
            return "动作执行=缺少活动种子，无法执行"
        }
        if (hasEvent(steps, "CMP_DECISION_SKIPPED")) {
            return "动作执行=已跳过"
        }
        return "动作执行=未触发"
    }

    private fun resolveUserActionSummary(steps: List<TraceStep>): String {
        return when {
            hasEvent(steps, "USER_ACTION_SUCCESS") -> "user/action=成功"
            hasEvent(steps, "USER_ACTION_FAIL") -> "user/action=失败"
            hasEvent(steps, "USER_ACTION_START") -> "user/action=已发起"
            hasEvent(steps, "PENDING_USER_ACTION_FOUND") || hasEvent(steps, "PENDING_SDK_SYNC_STORED") -> "user/action=待补发"
            else -> "user/action=未触发"
        }
    }

    private fun resolveConsentReportSummary(steps: List<TraceStep>): String {
        return when {
            hasEvent(steps, "CONSENT_REPORT_SUCCESS") -> "consent-report=成功"
            hasEvent(steps, "CONSENT_REPORT_FAIL") -> "consent-report=失败"
            hasEvent(steps, "CONSENT_REPORT_ENQUEUED") -> "consent-report=已入队待补发"
            else -> "consent-report=未触发"
        }
    }

    private fun parseAdLog(raw: String): Any {
        return runCatching { JsonParser.parseString(raw) }
            .getOrNull()
            ?.let { element ->
                when {
                    element.isJsonObject -> gson.fromJson(element, LinkedHashMap::class.java)
                    element.isJsonArray -> gson.fromJson(element, List::class.java)
                    else -> raw
                }
            }
            ?: raw
    }

    private fun hasEvent(steps: List<TraceStep>, eventType: String): Boolean {
        return steps.any { it.eventType == eventType }
    }

    private fun findLastValue(steps: List<TraceStep>, eventType: String, key: String): String? {
        return steps.asReversed()
            .firstOrNull { it.eventType == eventType }
            ?.rawEventMessage
            ?.let { extractValue(it, key) }
    }

    private fun extractValue(message: String, key: String): String? {
        return splitMessageSegments(message)
            .firstNotNullOfOrNull { segment ->
                val index = segment.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    val currentKey = segment.substring(0, index).trim()
                    if (currentKey == key) {
                        segment.substring(index + 1).trim()
                    } else {
                        null
                    }
                }
            }
    }

    private fun splitMessageSegments(message: String): List<String> {
        if (message.isBlank()) {
            return emptyList()
        }
        val result = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < message.length) {
            if (message[index] == ',') {
                val next = message.substring(index + 1).trimStart()
                if (next.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*=.*"))) {
                    result += message.substring(start, index).trim()
                    start = index + 1
                }
            }
            index += 1
        }
        result += message.substring(start).trim()
        return result.filter { it.isNotBlank() }
    }

    private fun localizeEventMessage(eventType: String, rawEventMessage: String): String {
        if (rawEventMessage.isBlank()) {
            return rawEventMessage
        }
        val segments = splitMessageSegments(rawEventMessage)
        if (segments.isEmpty()) {
            return localizeFreeText(eventType, rawEventMessage)
        }
        val suppressGenericExecutionReason = shouldSuppressGenericExecutionReason(segments)
        val localized = segments.mapNotNull { segment ->
            val index = segment.indexOf('=')
            if (index <= 0) {
                localizeFreeText(eventType, segment)
            } else {
                val key = segment.substring(0, index).trim()
                val value = segment.substring(index + 1).trim()
                if (suppressGenericExecutionReason && key == "reason" && value == "execution_error") {
                    null
                } else {
                    "${translateKey(key)}=${translateValue(key, value)}"
                }
            }
        }.joinToString("，")
        return localized.take(MAX_MESSAGE_LENGTH)
    }

    private fun shouldSuppressGenericExecutionReason(segments: List<String>): Boolean {
        val reason = extractValueFromSegments(segments, "reason")
        if (reason != "execution_error") {
            return false
        }
        val errorCode = extractValueFromSegments(segments, "errorCode")
        if (!errorCode.isNullOrBlank() && !errorCode.equals("none", ignoreCase = true)) {
            return true
        }
        val error = extractValueFromSegments(segments, "error")
        return !error.isNullOrBlank() &&
            !error.equals("unknown", ignoreCase = true) &&
            !error.equals("unknown error", ignoreCase = true)
    }

    private fun extractValueFromSegments(segments: List<String>, key: String): String? {
        return segments.firstNotNullOfOrNull { segment ->
            val index = segment.indexOf('=')
            if (index <= 0) {
                null
            } else {
                val currentKey = segment.substring(0, index).trim()
                if (currentKey == key) {
                    segment.substring(index + 1).trim()
                } else {
                    null
                }
            }
        }
    }

    private fun translateKey(key: String): String {
        return when (key) {
            "initialized" -> "已初始化"
            "consentReady" -> "同意状态已就绪"
            "consentLength" -> "同意串长度"
            "ready" -> "当前已就绪"
            "timeoutMs" -> "超时时间毫秒"
            "reason" -> "原因"
            "zone" -> "区域"
            "needShowPop" -> "需要弹窗"
            "sdkNeedShowPop" -> "SDK需要弹窗"
            "decisionEligible" -> "允许执行决策"
            "cmpCycleKey" -> "CMP轮次"
            "localSeedPresent" -> "本地种子存在"
            "localInvalid" -> "本地同意无效"
            "remoteHasStoredConsent" -> "远端已有同意串"
            "remoteHasNewCampaign" -> "远端有新活动"
            "shouldFetchCampaign" -> "需要拉取活动种子"
            "remoteRecoveryEligible" -> "可恢复远端同意"
            "suppressDecisionFlow" -> "抑制决策流程"
            "missingRequiredSeed" -> "缺少必要种子"
            "action" -> "动作"
            "remoteAction" -> "远端动作"
            "sdkAction" -> "SDK动作"
            "reportAction" -> "上报动作"
            "pendingAction" -> "待补动作"
            "campaignId" -> "活动ID"
            "campaignVersion" -> "活动版本"
            "vendorListVersion" -> "供应商列表版本"
            "code" -> "返回码"
            "message" -> "返回信息"
            "hasData" -> "有返回数据"
            "uploadHash" -> "上传哈希"
            "path" -> "执行路径"
            "source" -> "来源"
            "storage" -> "存储方式"
            "adType" -> "广告类型"
            "hidden" -> "隐藏模式"
            "requestId" -> "请求ID"
            "consentPreview" -> "同意串预览"
            "consentSuffix" -> "同意串尾段"
            "authorized" -> "授权结果"
            "enabled" -> "总开关开启"
            "forcePopup" -> "强制弹窗"
            "popupLogEnabled" -> "日志上报开启"
            "cycleKey" -> "轮次"
            "actionType" -> "动作类型"
            "error" -> "错误"
            "errorCode" -> "错误码"
            "stage" -> "阶段"
            "channelId" -> "渠道ID"
            "payload" -> "载荷"
            "payloadPresent" -> "带载荷"
            "purpose" -> "目的同意数"
            "purposeLi" -> "目的合法利益数"
            "customPurpose" -> "自定义目的同意数"
            "customPurposeLi" -> "自定义目的合法利益数"
            "specialFeature" -> "特殊功能同意数"
            "vendor" -> "供应商同意数"
            "vendorLi" -> "供应商合法利益数"
            "tcLength" -> "TC长度"
            "hasNewCampaign" -> "有新活动"
            "fallback" -> "兜底动作"
            else -> key
        }
    }

    private fun translateValue(key: String, value: String): String {
        return when (key) {
            "initialized",
            "consentReady",
            "ready",
            "needShowPop",
            "sdkNeedShowPop",
            "decisionEligible",
            "localSeedPresent",
            "localInvalid",
            "remoteHasStoredConsent",
            "remoteHasNewCampaign",
            "shouldFetchCampaign",
            "remoteRecoveryEligible",
            "suppressDecisionFlow",
            "missingRequiredSeed",
            "authorized",
            "enabled",
            "popupLogEnabled",
            "payloadPresent",
            "payload",
            "hasData",
            "hidden",
            "hasNewCampaign" -> translateBooleanToken(value)
            "action",
            "remoteAction",
            "sdkAction",
            "reportAction",
            "pendingAction",
            "actionType",
            "fallback" -> translateActionToken(value)
            "reason" -> translateReasonToken(value)
            "path" -> translatePathToken(value)
            "stage" -> translateStageToken(value)
            "storage" -> translateStorageToken(value)
            "source" -> translateSourceToken(value)
            "error" -> normalizeErrorToken(value)
            else -> translateGenericToken(value)
        }
    }

    private fun localizeFreeText(eventType: String, rawEventMessage: String): String {
        val trimmed = rawEventMessage.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }
        return when {
            eventType.endsWith("_FAIL") || eventType.endsWith("_INVALID") -> "错误=${normalizeErrorToken(trimmed)}"
            else -> translateGenericToken(trimmed)
        }
    }

    private fun translateBooleanToken(value: String): String {
        return when (value.lowercase()) {
            "true" -> "是"
            "false" -> "否"
            else -> translateGenericToken(value)
        }
    }

    private fun translateActionToken(value: String): String {
        return when (value) {
            "ACCEPT_ALL" -> "全部同意"
            "REJECT" -> "拒绝非必要"
            "SAVE_SETTINGS" -> "保存设置"
            "SAVE_AND_EXIT" -> "保存并退出"
            "MAYBE_LATER" -> "稍后再说"
            "SKIP_ALREADY_DECIDED" -> "已决策直接跳过"
            "EMPTY" -> "空动作"
            else -> translateGenericToken(value)
        }
    }

    private fun translateReasonToken(value: String): String {
        return when {
            value == "already_initialized" -> "已初始化过"
            value == "sdk_no_popup_needed" -> "SDK判定无需再次处理CMP"
            value == "flow_control_fail" -> "流控接口失败"
            value == "flow_control_disabled" -> "流控开关关闭"
            value == "ad_gate_not_eligible" -> "广告门禁未放行"
            value == "local_seed_missing" -> "本地种子缺失"
            value == "action_not_terminal" -> "本地动作不是终态"
            value == "local_seed_expired" -> "本地种子已过期"
            value == "tc_string_empty" -> "TC String 为空"
            value == "reflective_unavailable" -> "SDK原生反射不可用"
            value == "decision_build_failed" -> "构建动作种子失败"
            value == "campaign_seed_missing" -> "活动种子缺失"
            value == "remote_already_decided" -> "远端已存在统一记录"
            value == "ad_gate_no_decision_needed" -> "广告门禁判定无需继续决策"
            value == "sdk_processor_load_fail" -> "读取 SDK 本地状态失败"
            value == "consent_file_parse_fail" -> "解析本地 consent 文件失败"
            value == "campaign_bean_parse_fail" -> "解析 campaign bean 失败"
            value == "remote_tc_empty" -> "远端 TC String 为空"
            value == "payload_missing" -> "缺少必要载荷"
            value == "execution_error" -> "执行动作异常"
            value == "frequency_control" -> "命中投放频控"
            value == "sdk_init_failed" -> "广告 SDK 初始化失败"
            value == "container_released" -> "广告容器已释放"
            value == "controller_start_failed" -> "调用 controller.start 失败"
            value == "ad_request_failed" -> "广告请求抛出异常"
            value == "callback_timeout" -> "等待广告回调超时"
            value == "window_hidden" -> "窗口提前隐藏"
            value == "floating_ad_finished" -> "悬浮广告流程完成"
            value == "authorize_denied" -> "授权拒绝后结束"
            value == "authorize_fail" -> "授权失败后结束"
            value == "authorize_empty" -> "授权返回为空后结束"
            value.startsWith("campaign_") -> "活动侧${translateReasonToken(value.removePrefix("campaign_"))}"
            value.startsWith("request_error:") -> "请求失败:${normalizeErrorToken(value.removePrefix("request_error:"))}"
            value.startsWith("unknown_action:") -> "未知动作:${translateActionToken(value.removePrefix("unknown_action:"))}"
            else -> translateGenericToken(value)
        }
    }

    private fun translateStageToken(value: String): String {
        return when (value) {
            "sdk_init" -> "SDK 初始化"
            "container_prepare" -> "容器准备"
            "container_post" -> "容器投递"
            "controller_start" -> "开始播放"
            "ad_request" -> "广告请求"
            "sdk_callback" -> "SDK 回调"
            "container_size" -> "容器尺寸"
            "callback_timeout" -> "回调等待"
            "window_destroy" -> "窗口销毁"
            else -> translateGenericToken(value)
        }
    }

    private fun translatePathToken(value: String): String {
        return when (value) {
            "reflective" -> "SDK原生反射"
            "explicit" -> "显式静默注入"
            "direct_user_action" -> "直接上报 user/action"
            "sdk_processor" -> "SDK 处理器"
            "file_fallback" -> "本地文件兜底"
            else -> translateGenericToken(value)
        }
    }

    private fun translateStorageToken(value: String): String {
        return when (value) {
            "sdk" -> "SDK 原生持久化"
            "manual_fallback" -> "本地文件兜底"
            else -> translateGenericToken(value)
        }
    }

    private fun translateSourceToken(value: String): String {
        return when {
            value == "explicit_bootstrap" -> "启动静默恢复"
            value.startsWith("reflective_") -> "SDK原生反射-${translateActionToken(value.removePrefix("reflective_").uppercase())}"
            value.startsWith("explicit_") -> "显式静默注入-${translateActionToken(value.removePrefix("explicit_").uppercase())}"
            value.startsWith("remote_recovery_") -> "远端状态恢复-${translateActionToken(value.removePrefix("remote_recovery_").uppercase())}"
            else -> translateGenericToken(value)
        }
    }

    private fun translateGenericToken(value: String): String {
        if (value.isBlank()) {
            return value
        }
        if (value.contains("=") && !value.contains("://")) {
            return splitMessageSegments(value).joinToString("，") { nested ->
                val index = nested.indexOf('=')
                if (index <= 0) {
                    nested
                } else {
                    val nestedKey = nested.substring(0, index).trim()
                    val nestedValue = nested.substring(index + 1).trim()
                    "${translateKey(nestedKey)}=${translateValue(nestedKey, nestedValue)}"
                }
            }
        }
        return when (value.lowercase()) {
            "true" -> "是"
            "false" -> "否"
            "unknown" -> "未知"
            "network error" -> "网络错误"
            else -> value
        }
    }

    private fun normalizeErrorToken(value: String): String {
        val trimmed = value.trim()
        return when (trimmed.lowercase()) {
            "network error" -> "网络错误"
            "unknown" -> "未知错误"
            else -> trimmed
        }
    }

    private fun describeTerminalEvent(eventType: String): String {
        return when (eventType) {
            "CMP_GATE_STOP" -> "CMP 门禁提前结束"
            "AUTHORIZE_ALLOWED" -> "授权通过"
            "AUTHORIZE_DENIED" -> "授权拒绝"
            "AUTHORIZE_CALLBACK_FAIL" -> "授权回调失败"
            "AUTHORIZE_CALLBACK_EMPTY" -> "授权回调为空"
            "AD_PHASE_COMPLETED" -> "广告播放完成"
            "AD_PHASE_ERROR" -> "广告播放失败"
            "AD_PHASE_TIMEOUT" -> "广告播放超时"
            "AD_PHASE_CANCELLED" -> "广告流程提前结束"
            "FLOATING_FLOW_SKIPPED" -> "广告流程已跳过"
            "FLOW_GUARD_FINISH" -> "广告流程守卫收口"
            else -> eventType
        }
    }

    private fun safeMacAddress(): String? {
        return runCatching { getMacAddress() }
            .onFailure { error ->
                Log.w(TAG, "read mac failed for consent-log-report: ${error.message}")
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private data class TraceStep(
        val index: Int,
        val elapsedMs: Long,
        val eventType: String,
        val rawEventMessage: String,
        val eventMessage: String,
        val adLog: String?
    )

    private data class ConsentLogUploadPayload(
        val eventType: String,
        val eventMessage: String,
        val adLog: String,
        val popupLogEnabled: Boolean
    )
}
