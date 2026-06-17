package com.smart.android.ad_app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class Hq008CmpSilentEntryTestActivity : FragmentActivity() {

    private enum class RunMode(val label: String) {
        NONE("空闲"),
        FULL_FLOATING("完整悬浮广告链路")
    }

    private companion object {
        const val LOCAL_CONSENT_RELATIVE_PATH = "CmpConsent/consent.0"
        const val CMP_CONSENT_DIR_NAME = "CmpConsent"
        const val RUN_TIMEOUT_MS = 25_000L
        const val EVENT_LOCAL_STATE_LOADED = "CMP_LOCAL_STATE_LOADED"
    }

    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runTimeoutRunnable = Runnable {
        val mode = currentRunMode
        if (mode != RunMode.NONE) {
            appendLog("${mode.label}等待超时，先回收页面按钮并刷新本地状态")
            completeRun(mode, "timeout")
        }
    }
    private val cmpTraceListener = Hq008ConsentLogReporter.DebugTraceListener { eventType, rawEventMessage ->
        mainHandler.post {
            appendLog("[$eventType] $rawEventMessage")
            if (currentRunMode == RunMode.FULL_FLOATING && isTerminalFlowEvent(eventType)) {
                completeRun(
                    mode = RunMode.FULL_FLOATING,
                    reason = "terminal_$eventType"
                )
            } else {
                refreshStatusUiFromTrace(eventType)
            }
        }
    }

    private lateinit var deviceIdInput: EditText
    private lateinit var statusView: TextView
    private lateinit var snapshotView: TextView
    private lateinit var logView: TextView
    private lateinit var runFullFlowButton: Button
    private var currentRunMode = RunMode.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_hq008_cmp_silent_entry_test)

        deviceIdInput = findViewById(R.id.effective_device_id_input)
        statusView = findViewById(R.id.cmp_status_text)
        snapshotView = findViewById(R.id.local_snapshot_text)
        logView = findViewById(R.id.log_text)
        runFullFlowButton = findViewById(R.id.run_full_flow_button)

        logView.movementMethod = ScrollingMovementMethod()
        deviceIdInput.setText(resolveSelectedDeviceId())

        runFullFlowButton.setOnClickListener {
            runFullFloatingFlow()
        }
        findViewById<Button>(R.id.random_device_id_button).setOnClickListener {
            val randomDeviceId = "cmp-test-${UUID.randomUUID().toString().take(8)}"
            deviceIdInput.setText(randomDeviceId)
            applyDeviceIdOverride(randomDeviceId, "已切换测试 deviceId")
        }
        findViewById<Button>(R.id.reset_device_id_button).setOnClickListener {
            val defaultDeviceId = resolveDefaultDeviceId()
            deviceIdInput.setText(defaultDeviceId)
            Hq008CmpManager.setDebugDeviceIdOverride(null)
            appendLog("已恢复默认 deviceId=$defaultDeviceId")
            refreshStatusUi()
        }
        findViewById<Button>(R.id.open_sdk_test_page_button).setOnClickListener {
            startActivity(Intent(this, Hq008CmpSdkEntryTestActivity::class.java))
        }
        findViewById<Button>(R.id.refresh_local_snapshot_button).setOnClickListener {
            refreshLocalSnapshot("manual_refresh")
        }
        findViewById<Button>(R.id.reset_local_consent_button).setOnClickListener {
            resetLocalConsentFile()
        }
        findViewById<Button>(R.id.clear_log_button).setOnClickListener {
            logView.text = ""
            appendLog("日志已清空")
        }

        appendLog(
            "已创建 CMP 静默链路测试页，effectiveDeviceId=${resolveSelectedDeviceId()} overlay=${Settings.canDrawOverlays(this)} sdk=${Build.VERSION.SDK_INT}"
        )
        appendLog("主按钮会走完整链路：CMP 门禁 + 授权 + 广告请求")
        refreshLocalSnapshot("on_create")
    }

    override fun onStart() {
        super.onStart()
        Hq008ConsentLogReporter.addDebugTraceListener(cmpTraceListener)
    }

    override fun onStop() {
        Hq008ConsentLogReporter.removeDebugTraceListener(cmpTraceListener)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(runTimeoutRunnable)
        super.onDestroy()
    }

    private fun runFullFloatingFlow() {
        if (!beginRun(RunMode.FULL_FLOATING, "开始执行完整悬浮广告链路")) {
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            appendLog("当前悬浮窗权限未开启，本页不再主动拉起授权页")
        }
        AdConfigManager.getAdConfig(AdType.FLOATING)
    }

    private fun beginRun(mode: RunMode, startMessage: String): Boolean {
        if (currentRunMode != RunMode.NONE) {
            appendLog("当前已有${currentRunMode.label}在执行，等它结束后再点")
            return false
        }
        if (!syncDeviceIdOverrideFromInput()) {
            return false
        }
        currentRunMode = mode
        mainHandler.removeCallbacks(runTimeoutRunnable)
        mainHandler.postDelayed(runTimeoutRunnable, RUN_TIMEOUT_MS)
        appendLog(startMessage)
        refreshStatusUi()
        updateActionButtons()
        return true
    }

    private fun completeRun(mode: RunMode, reason: String) {
        if (currentRunMode != mode) {
            return
        }
        currentRunMode = RunMode.NONE
        mainHandler.removeCallbacks(runTimeoutRunnable)
        appendLog(
            "${mode.label}已结束，reason=$reason consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0} expired=${Hq008CmpManager.isConsentExpired(this)}"
        )
        refreshLocalSnapshot(reason)
        updateActionButtons()
    }

    private fun updateActionButtons() {
        val idle = currentRunMode == RunMode.NONE
        runFullFlowButton.isEnabled = idle
        runFullFlowButton.alpha = if (idle) 1f else 0.45f
    }

    private fun refreshLocalSnapshot(reason: String) {
        snapshotView.text = buildLocalSnapshotText()
        appendLog("已刷新本地快照，reason=$reason")
        refreshStatusUi()
    }

    private fun refreshStatusUi() {
        val consentLength = Hq008CmpManager.getConsentString()?.length ?: 0
        val consentExpired = Hq008CmpManager.isConsentExpired(this)
        val overlayGranted = Settings.canDrawOverlays(this)
        statusView.text = buildString {
            appendLine("runMode=${currentRunMode.label}")
            appendLine("consentLength=$consentLength")
            appendLine("consentExpired=$consentExpired")
            appendLine("overlayGranted=$overlayGranted")
            append("effectiveDeviceId=${resolveSelectedDeviceId()}")
        }
    }

    private fun refreshStatusUiFromTrace(eventType: String) {
        if (eventType == EVENT_LOCAL_STATE_LOADED) {
            return
        }
        refreshStatusUi()
    }

    private fun resolveSelectedDeviceId(): String {
        val inputDeviceId = deviceIdInput.text?.toString()?.trim().orEmpty()
        if (inputDeviceId.isNotBlank()) {
            return inputDeviceId
        }
        return Hq008CmpManager.getDebugDeviceIdOverride() ?: resolveDefaultDeviceId()
    }

    private fun resolveDefaultDeviceId(): String {
        val overrideDeviceId = BuildConfig.CMP_DEVICE_ID_OVERRIDE
        if (overrideDeviceId.isNotBlank()) {
            return overrideDeviceId
        }
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    }

    private fun syncDeviceIdOverrideFromInput(): Boolean {
        val deviceId = deviceIdInput.text?.toString()?.trim().orEmpty()
        if (deviceId.isBlank()) {
            appendLog("deviceId 不能为空，请先恢复默认 ID 或生成随机 ID")
            return false
        }
        val defaultDeviceId = resolveDefaultDeviceId()
        if (deviceId == defaultDeviceId) {
            Hq008CmpManager.setDebugDeviceIdOverride(null)
        } else {
            Hq008CmpManager.setDebugDeviceIdOverride(deviceId)
        }
        refreshStatusUi()
        return true
    }

    private fun applyDeviceIdOverride(deviceId: String, logPrefix: String) {
        Hq008CmpManager.setDebugDeviceIdOverride(deviceId)
        appendLog("$logPrefix=$deviceId")
        refreshStatusUi()
    }

    private fun resetLocalConsentFile() {
        val targetPaths = listOf(
            resolveSdkConsentFile(),
            resolveSdkConsentDir(),
            File(filesDir, LOCAL_CONSENT_RELATIVE_PATH),
            File(filesDir, CMP_CONSENT_DIR_NAME)
        ).distinctBy { it.absolutePath }
        val failedTargets = targetPaths.filter { target ->
            target.exists() && !target.deleteRecursively()
        }
        appendLog(
            if (failedTargets.isEmpty()) {
                "已删除本地 consent 状态，paths=${targetPaths.joinToString { it.absolutePath }}"
            } else {
                "删除本地 consent 状态失败：${failedTargets.joinToString { it.absolutePath }}"
            }
        )
        refreshLocalSnapshot("delete_local_file")
    }

    private fun buildLocalSnapshotText(): String {
        val localConsentFile = resolveSdkConsentFile()
        if (!localConsentFile.exists()) {
            val legacyConsentFile = File(filesDir, LOCAL_CONSENT_RELATIVE_PATH)
            return "本地 consent 文件不存在\nsdkPath=${localConsentFile.absolutePath}\nlegacyPath=${legacyConsentFile.absolutePath}"
        }

        val rawContent = runCatching(localConsentFile::readText).getOrElse { error ->
            return "读取本地 consent 文件失败：${error.message}\npath=${localConsentFile.absolutePath}"
        }
        if (rawContent.isBlank()) {
            return "本地 consent 文件为空\npath=${localConsentFile.absolutePath}"
        }

        val jsonObject = runCatching {
            JsonParser.parseString(rawContent).asJsonObject
        }.getOrElse {
            return "本地 consent 文件不是有效 JSON\npath=${localConsentFile.absolutePath}\nraw=$rawContent"
        }

        val tcString = jsonObject.stringOrNull("cmp_tc_string")
        val createTime = jsonObject.longOrNull("tc_string_create_time")
        val expireTime = jsonObject.longOrNull("tc_string_expire_time")
        val campaignId = jsonObject.intOrNull("campaign_id")
        val actionType = jsonObject.stringOrNull("action_type")
        val hasNewCampaign = jsonObject.booleanOrNull("has_new_campaign")
        val invalid = isSdkLocalConsentInvalid(tcString, createTime, expireTime)

        return buildString {
            appendLine("path=${localConsentFile.absolutePath}")
            appendLine("exists=true")
            appendLine("tcLength=${tcString?.length ?: 0}")
            appendLine("tcPreview=${tcString?.take(24).orEmpty()}")
            appendLine("createTime=$createTime")
            appendLine("expireTime=$expireTime")
            appendLine("campaignId=$campaignId")
            appendLine("actionType=$actionType")
            appendLine("hasNewCampaign=$hasNewCampaign")
            appendLine("sdkLocalInvalid=$invalid")
        }.trimEnd()
    }

    private fun isSdkLocalConsentInvalid(
        tcString: String?,
        createTime: Long?,
        expireTime: Long?
    ): Boolean {
        if (tcString.isNullOrBlank()) {
            return true
        }
        val safeCreateTime = createTime ?: return true
        val safeExpireTime = expireTime ?: return true
        if (safeCreateTime <= 0L || safeExpireTime <= 0L) {
            return true
        }
        return System.currentTimeMillis() - safeCreateTime > safeExpireTime
    }

    private fun isTerminalFlowEvent(eventType: String): Boolean {
        return eventType == "CMP_GATE_STOP" ||
            eventType == "AUTHORIZE_DENIED" ||
            eventType == "AUTHORIZE_CALLBACK_FAIL" ||
            eventType == "AUTHORIZE_CALLBACK_EMPTY" ||
            eventType == "AD_PHASE_COMPLETED" ||
            eventType == "AD_PHASE_ERROR" ||
            eventType == "AD_PHASE_TIMEOUT" ||
            eventType == "AD_PHASE_CANCELLED" ||
            eventType == "FLOW_GUARD_FINISH"
    }

    private fun appendLog(message: String) {
        val timestamp = logTimeFormat.format(Date())
        val nextLog = buildString {
            append(logView.text)
            if (isNotEmpty()) {
                append('\n')
            }
            append('[')
            append(timestamp)
            append("] ")
            append(message)
        }
        logView.text = if (nextLog.length > 14_000) {
            nextLog.takeLast(14_000)
        } else {
            nextLog
        }
        logView.post {
            val scrollAmount = logView.layout?.getLineTop(logView.lineCount) ?: 0
            if (scrollAmount > logView.height) {
                logView.scrollTo(0, scrollAmount - logView.height)
            } else {
                logView.scrollTo(0, 0)
            }
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) {
            return null
        }
        return element.asString
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val element = get(key) ?: return null
        if (element.isJsonNull) {
            return null
        }
        return element.asLong
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val element = get(key) ?: return null
        if (element.isJsonNull) {
            return null
        }
        return element.asInt
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val element = get(key) ?: return null
        if (element.isJsonNull) {
            return null
        }
        return element.asBoolean
    }

    private fun resolveSdkConsentFile(): File {
        return File(filesDir.absolutePath + CMP_CONSENT_DIR_NAME + "/consent.0")
    }

    private fun resolveSdkConsentDir(): File {
        return File(filesDir.absolutePath + CMP_CONSENT_DIR_NAME)
    }
}
