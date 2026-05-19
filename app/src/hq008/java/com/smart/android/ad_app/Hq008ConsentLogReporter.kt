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
    private const val TAG = "Hq008ConsentLog"
    private const val MAX_MESSAGE_LENGTH = 512
    private const val MAX_TRACE_STEPS = 80
    private const val MAX_TRACE_LOG_LENGTH = 24_000
    private const val FLOW_SUMMARY_EVENT = "CMP_FLOW_SUMMARY"
    private val criticalAdLogEvents = setOf(
        "USER_ACTION_START",
        "USER_ACTION_SUCCESS",
        "USER_ACTION_FAIL"
    )
    private val consentLogUrl = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/consent-log-report"
    private val gson = Gson()
    private val lock = Any()
    private val pendingSteps = mutableListOf<TraceStep>()
    private var traceStartedElapsedMs: Long = 0L

    fun report(
        eventType: String,
        eventMessage: String,
        adLog: String? = null
    ) {
        val rawMessage = eventMessage.take(MAX_MESSAGE_LENGTH)
        val localizedMessage = localizeEventMessage(eventType, rawMessage)
        val upload = synchronized(lock) {
            if (shouldResetForNewFlow(eventType)) {
                Log.w(TAG, "consent-log-report 丢弃未完成旧流程，newStart=$eventType")
                resetLocked()
            }

            if (pendingSteps.isEmpty()) {
                traceStartedElapsedMs = SystemClock.elapsedRealtime()
            }

            pendingSteps += TraceStep(
                index = pendingSteps.size + 1,
                elapsedMs = SystemClock.elapsedRealtime() - traceStartedElapsedMs,
                eventType = eventType,
                rawEventMessage = rawMessage,
                eventMessage = localizedMessage,
                adLog = adLog?.takeIf { it.isNotBlank() }
            )
            trimStepsLocked()

            if (!isTerminalEvent(eventType)) {
                null
            } else {
                buildUploadPayloadLocked(
                    finalEventType = eventType
                ).also {
                    resetLocked()
                }
            }
        }

        upload?.let { send(it) }
    }

    private fun send(payload: ConsentLogUploadPayload) {
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

    private fun buildUploadPayloadLocked(
        finalEventType: String
    ): ConsentLogUploadPayload {
        val stepsForUpload = buildStepsForUploadLocked(finalEventType)
        val summaryStep = stepsForUpload.lastOrNull { it.eventType == FLOW_SUMMARY_EVENT }
        val finalStep = stepsForUpload.lastOrNull { it.eventType == finalEventType }
        val finalEventMessage = finalStep?.eventMessage.orEmpty()
        val summaryStepMessage = summaryStep?.eventMessage.orEmpty()
        val traceSummary = buildTraceSummary(
            finalEventType = finalEventType,
            finalEventMessage = finalEventMessage,
            stepsForUpload = stepsForUpload
        )

        var adLogPayload = gson.toJson(traceSummary)
        if (adLogPayload.length > MAX_TRACE_LOG_LENGTH) {
            val compactedTraceSummary = buildTraceSummary(
                finalEventType = finalEventType,
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
                finalEventType = finalEventType,
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
            append(describeTerminalEvent(finalEventType))
            append("，步骤数=")
            append(stepsForUpload.size)
            val conclusion = summaryStepMessage.ifBlank { finalEventMessage }
            if (conclusion.isNotBlank()) {
                append("，结论=")
                append(conclusion.take(220))
            }
        }.take(MAX_MESSAGE_LENGTH)

        return ConsentLogUploadPayload(
            eventType = finalEventType,
            eventMessage = summaryMessage,
            adLog = adLogPayload
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

    private fun buildStepsForUploadLocked(finalEventType: String): List<TraceStep> {
        if (pendingSteps.isEmpty()) {
            return emptyList()
        }
        val summaryStep = buildFlowSummaryStepLocked(finalEventType)
        return if (summaryStep != null) {
            pendingSteps + summaryStep
        } else {
            pendingSteps.toList()
        }
    }

    private fun buildFlowSummaryStepLocked(finalEventType: String): TraceStep? {
        if (pendingSteps.isEmpty()) {
            return null
        }
        val popupNeedShow = findLastValue("CMP_SNAPSHOT_LOAD_RESULT", "needShowPop")
        val decisionEligible = findLastValue("CMP_GATE_DECISION_ELIGIBILITY", "decisionEligible")
            ?: findLastValue("CMP_GATE_EVALUATED", "decisionEligible")
        val gateSkipReason = findLastValue("CMP_GATE_SKIP", "reason")
        val triggerReasons = buildTriggerReasons()
        val popupSummary = when {
            hasEvent("POPUP_CALLBACK_FAIL") -> "popup请求=请求失败"
            hasEvent("POPUP_REQUEST_SUCCESS") -> "popup请求=已请求并返回动作"
            hasEvent("POPUP_REQUEST_START") -> "popup请求=已请求，等待返回"
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
        val decisionAction = resolveDecisionActionSummary()
        val actionExecution = resolveActionExecutionSummary()
        val userActionSummary = resolveUserActionSummary()
        val consentReportSummary = resolveConsentReportSummary()
        val authorizeSummary = "授权结果=${describeTerminalEvent(finalEventType)}"
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
        val lastElapsed = pendingSteps.lastOrNull()?.elapsedMs ?: 0L
        return TraceStep(
            index = pendingSteps.size + 1,
            elapsedMs = lastElapsed,
            eventType = FLOW_SUMMARY_EVENT,
            rawEventMessage = summaryMessage,
            eventMessage = summaryMessage,
            adLog = null
        )
    }

    private fun buildTriggerReasons(): String? {
        val reasons = mutableListOf<String>()
        if (findLastValue("CMP_GATE_EVALUATED", "localInvalid") == "true") {
            reasons += "本地同意状态无效"
        }
        if (findLastValue("CMP_GATE_EVALUATED", "remoteHasNewCampaign") == "true") {
            reasons += "远端有新活动"
        }
        if (findLastValue("CMP_GATE_EVALUATED", "remoteRecoveryEligible") == "true") {
            reasons += "可直接恢复远端同意状态"
        }
        if (findLastValue("CMP_GATE_EVALUATED", "missingRequiredSeed") == "true") {
            reasons += "缺少活动种子"
        }
        return reasons.takeIf { it.isNotEmpty() }
            ?.joinToString("、")
            ?.let { "触发原因=$it" }
    }

    private fun resolveDecisionActionSummary(): String {
        val action = findLastValue("CMP_DECISION_START", "action")
            ?: findLastPopupAction()
            ?: findLastValue("CMP_DECISION_SKIPPED", "action")
        return if (action.isNullOrBlank()) {
            "远端动作=未下发"
        } else {
            "远端动作=${translateActionToken(action)}"
        }
    }

    private fun findLastPopupAction(): String? {
        return pendingSteps.asReversed()
            .firstOrNull { step -> step.eventType.startsWith("POPUP_ACTION_") }
            ?.eventType
            ?.removePrefix("POPUP_ACTION_")
    }

    private fun resolveActionExecutionSummary(): String {
        if (hasEvent("CMP_DECISION_FALLBACK")) {
            return "动作执行=回退稍后再说"
        }
        findLastValue("SDK_ACTION_PATH", "path")?.let { path ->
            return "动作执行=${translatePathToken(path)}"
        }
        findLastValue("CMP_MAYBE_LATER_PATH", "path")?.let { path ->
            return "动作执行=${translatePathToken(path)}"
        }
        if (hasEvent("CMP_REMOTE_RECOVERY")) {
            return "动作执行=恢复远端已有同意状态"
        }
        if (hasEvent("CMP_DECISION_BUILD_FAIL")) {
            return "动作执行=构建动作种子失败"
        }
        if (hasEvent("CMP_SEED_MISSING")) {
            return "动作执行=缺少活动种子，无法执行"
        }
        if (hasEvent("CMP_DECISION_SKIPPED")) {
            return "动作执行=已跳过"
        }
        return "动作执行=未触发"
    }

    private fun resolveUserActionSummary(): String {
        return when {
            hasEvent("USER_ACTION_SUCCESS") -> "user/action=成功"
            hasEvent("USER_ACTION_FAIL") -> "user/action=失败"
            hasEvent("USER_ACTION_START") -> "user/action=已发起"
            hasEvent("PENDING_USER_ACTION_FOUND") || hasEvent("PENDING_SDK_SYNC_STORED") -> "user/action=待补发"
            else -> "user/action=未触发"
        }
    }

    private fun resolveConsentReportSummary(): String {
        return when {
            hasEvent("CONSENT_REPORT_SUCCESS") -> "consent-report=成功"
            hasEvent("CONSENT_REPORT_FAIL") -> "consent-report=失败"
            hasEvent("CONSENT_REPORT_ENQUEUED") -> "consent-report=已入队待补发"
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

    private fun trimStepsLocked() {
        if (pendingSteps.size <= MAX_TRACE_STEPS) {
            return
        }
        val first = pendingSteps.first()
        val tail = pendingSteps.takeLast(MAX_TRACE_STEPS - 1)
        pendingSteps.clear()
        pendingSteps += first.copy(
            rawEventMessage = "${first.rawEventMessage} [已裁剪]",
            eventMessage = "${first.eventMessage} [已裁剪]"
        )
        pendingSteps += tail.mapIndexed { index, step -> step.copy(index = index + 2) }
    }

    private fun shouldResetForNewFlow(eventType: String): Boolean {
        return pendingSteps.isNotEmpty() &&
            eventType == "CMP_GATE_START" &&
            pendingSteps.any { it.eventType == "CMP_GATE_START" }
    }

    private fun isTerminalEvent(eventType: String): Boolean {
        return eventType == "CMP_GATE_STOP" ||
            eventType == "AUTHORIZE_ALLOWED" ||
            eventType == "AUTHORIZE_DENIED" ||
            eventType == "AUTHORIZE_CALLBACK_FAIL" ||
            eventType == "AUTHORIZE_CALLBACK_EMPTY"
    }

    private fun resetLocked() {
        pendingSteps.clear()
        traceStartedElapsedMs = 0L
    }

    private fun hasEvent(eventType: String): Boolean {
        return pendingSteps.any { it.eventType == eventType }
    }

    private fun findLastValue(eventType: String, key: String): String? {
        return pendingSteps.asReversed()
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
        val localized = segments.map { segment ->
            val index = segment.indexOf('=')
            if (index <= 0) {
                localizeFreeText(eventType, segment)
            } else {
                val key = segment.substring(0, index).trim()
                val value = segment.substring(index + 1).trim()
                "${translateKey(key)}=${translateValue(key, value)}"
            }
        }.joinToString("，")
        return localized.take(MAX_MESSAGE_LENGTH)
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
            "hasData" -> "有返回数据"
            "uploadHash" -> "上传哈希"
            "path" -> "执行路径"
            "source" -> "来源"
            "storage" -> "存储方式"
            "adType" -> "广告类型"
            "hidden" -> "隐藏模式"
            "requestId" -> "请求ID"
            "authorized" -> "授权结果"
            "enabled" -> "总开关开启"
            "forcePopup" -> "强制弹窗"
            "cycleKey" -> "轮次"
            "actionType" -> "动作类型"
            "error" -> "错误"
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
            value.startsWith("campaign_") -> "活动侧${translateReasonToken(value.removePrefix("campaign_"))}"
            value.startsWith("request_error:") -> "请求失败:${normalizeErrorToken(value.removePrefix("request_error:"))}"
            value.startsWith("unknown_action:") -> "未知动作:${translateActionToken(value.removePrefix("unknown_action:"))}"
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
        val adLog: String
    )
}
