package com.smart.android.ad_app

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tcl.ff.component.oversea.CmpConfigParams
import com.tcl.ff.component.oversea.CmpConsentManager
import com.tcl.ff.component.oversea.CmpPopStateManager
import com.tcl.ff.component.oversea.constant.CMPErrorCode
import com.tcl.ff.component.oversea.constant.CMPStatus
import com.tcl.ff.component.oversea.constant.CmpDisplayType
import com.tcl.ff.component.oversea.listener.OnCmpLoadStateListener
import com.tcl.ff.component.oversea.listener.OnCmpStatusListener
import com.tcl.ff.component.oversea.listener.OnCustomClickListener
import com.tcl.ff.component.oversea.model.expose.GDPRConsent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class Hq008CmpSdkEntryTestActivity : FragmentActivity() {

    private companion object {
        const val DEFAULT_ZONE = "de"
        const val LOCAL_CONSENT_RELATIVE_PATH = "CmpConsent/consent.0"
        const val CMP_CONSENT_DIR_NAME = "CmpConsent"
    }

    private var popupCmpConsentManager = CmpConsentManager()
    private var cmpPopStateManager = CmpPopStateManager()
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var deviceIdInput: EditText
    private lateinit var statusView: TextView
    private lateinit var snapshotView: TextView
    private lateinit var logView: TextView
    private lateinit var showCmpButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        stripSavedFragmentState(savedInstanceState)
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_hq008_cmp_sdk_entry_test)

        deviceIdInput = findViewById(R.id.device_id_input)
        statusView = findViewById(R.id.cmp_status_text)
        snapshotView = findViewById(R.id.local_snapshot_text)
        logView = findViewById(R.id.log_text)
        showCmpButton = findViewById(R.id.show_cmp_pop_button)

        logView.movementMethod = ScrollingMovementMethod()
        deviceIdInput.setText(resolveDefaultDeviceId())

        findViewById<Button>(R.id.load_pop_state_button).setOnClickListener {
            runLoadPopState()
        }
        findViewById<Button>(R.id.load_cmp_popup_button).setOnClickListener {
            runLoadCmpPrivacy()
        }
        findViewById<Button>(R.id.show_cmp_pop_button).setOnClickListener {
            runShowCmpPop()
        }
        findViewById<Button>(R.id.refresh_local_snapshot_button).setOnClickListener {
            refreshLocalSnapshot("manual_refresh")
        }
        findViewById<Button>(R.id.reset_local_consent_button).setOnClickListener {
            resetLocalConsentFile()
        }
        findViewById<Button>(R.id.random_device_id_button).setOnClickListener {
            val randomDeviceId = "cmp-test-${UUID.randomUUID().toString().take(8)}"
            deviceIdInput.setText(randomDeviceId)
            appendLog("已切换测试 deviceId=$randomDeviceId")
        }
        findViewById<Button>(R.id.reset_device_id_button).setOnClickListener {
            val defaultDeviceId = resolveDefaultDeviceId()
            deviceIdInput.setText(defaultDeviceId)
            appendLog("已恢复默认 deviceId=$defaultDeviceId")
        }
        findViewById<Button>(R.id.clear_log_button).setOnClickListener {
            logView.text = ""
            appendLog("日志已清空")
        }

        appendLog(
            "已创建 CMP SDK 测试页，deviceMake=${resolveDeviceMake()} clientType=${resolveClientType()} zone=$DEFAULT_ZONE deviceId=${deviceIdInput.text}"
        )
        appendLog("建议复现顺序：先点“删除本地 consent 文件”，再点“loadCmpPrivacy(CMP_POP)”")
        updateCmpStatusUi()
        refreshLocalSnapshot("on_create")
    }

    override fun onDestroy() {
        releaseSdkManagers()
        super.onDestroy()
    }

    private fun runLoadPopState() {
        val cmpConfig = buildCmpConfig()
        appendLog("开始调用 loadPopState，deviceId=${cmpConfig.deviceId} zone=${cmpConfig.zone}")
        cmpPopStateManager.loadPopState(
            cmpConfig,
            object : OnCmpLoadStateListener {
                override fun onLoadComplete(consentString: String?, needShowPop: Boolean) {
                    appendLog(
                        "loadPopState 回调：needShowPop=$needShowPop consentLength=${consentString?.length ?: 0}"
                    )
                    refreshLocalSnapshot("load_pop_state")
                }
            }
        )
    }

    private fun runLoadCmpPrivacy() {
        val cmpConfig = buildCmpConfig()
        appendLog("开始调用 loadCmpPrivacy(CMP_POP)，deviceId=${cmpConfig.deviceId} zone=${cmpConfig.zone}")
        popupCmpConsentManager.loadCmpPrivacy(
            CmpDisplayType.CMP_POP,
            cmpConfig,
            this,
            object : OnCmpStatusListener {
                override fun onCmpDataReady() {
                    appendLog("onCmpDataReady：cmpStatus=${popupCmpConsentManager.getCmpStatus()}，如果云端走业务方弹窗，可再点 showCmpPop()")
                    updateCmpStatusUi()
                }

                override fun onConsentStringReady(
                    consentString: String?,
                    gdprConsent: GDPRConsent?
                ) {
                    appendLog(
                        "onConsentStringReady：cmpStatus=${popupCmpConsentManager.getCmpStatus()} consentLength=${consentString?.length ?: 0} consentAction=${gdprConsent?.consentAction} deviceId=${gdprConsent?.deviceId}"
                    )
                    refreshLocalSnapshot("on_consent_ready")
                    updateCmpStatusUi()
                }

                override fun onCmpPopup() {
                    appendLog("onCmpPopup：SDK 已弹出 CMP 界面，cmpStatus=${popupCmpConsentManager.getCmpStatus()}")
                    updateCmpStatusUi()
                }

                override fun onError(errorCode: CMPErrorCode) {
                    appendLog(
                        "onError：name=${errorCode.name} code=${errorCode.errorCode} message=${errorCode.msg}"
                    )
                    refreshLocalSnapshot("on_error")
                    updateCmpStatusUi()
                }
            },
            object : OnCustomClickListener {
                override fun privacyBtnClick() {
                    appendLog("OnCustomClickListener：点击了 privacy 按钮")
                    Toast.makeText(this@Hq008CmpSdkEntryTestActivity, "privacyBtnClick", Toast.LENGTH_SHORT).show()
                }

                override fun termsBtnClick() {
                    appendLog("OnCustomClickListener：点击了 terms 按钮")
                    Toast.makeText(this@Hq008CmpSdkEntryTestActivity, "termsBtnClick", Toast.LENGTH_SHORT).show()
                }
            }
        )
        updateCmpStatusUi()
    }

    private fun runShowCmpPop() {
        appendLog("尝试调用 showCmpPop()，当前 cmpStatus=${popupCmpConsentManager.getCmpStatus()}")
        runCatching {
            popupCmpConsentManager.showCmpPop(supportFragmentManager)
        }.onFailure { error ->
            appendLog("showCmpPop() 调用失败：${error.message}")
        }
        updateCmpStatusUi()
    }

    private fun buildCmpConfig(): CmpConfigParams {
        return CmpConfigParams.Builder()
            .setDeviceMake(resolveDeviceMake())
            .setDeviceId(deviceIdInput.text?.toString().orEmpty())
            .setZone(DEFAULT_ZONE)
            .setClientType(resolveClientType())
            .setShowPopForce(false)
            .setCorner(24f)
            .build()
    }

    private fun refreshLocalSnapshot(reason: String) {
        val snapshotText = buildLocalSnapshotText()
        snapshotView.text = snapshotText
        appendLog("已刷新本地快照，reason=$reason")
        updateCmpStatusUi()
    }

    private fun updateCmpStatusUi() {
        val status = popupCmpConsentManager.getCmpStatus()
        val isDataReady = status == CMPStatus.CMP_DATA_READY
        statusView.text = "cmpStatus=$status\nshowCmpPopEnabled=$isDataReady"
        showCmpButton.isEnabled = isDataReady
        showCmpButton.alpha = if (isDataReady) 1f else 0.45f
    }

    private fun resetLocalConsentFile() {
        val deletedTargets = mutableListOf<String>()
        val failedTargets = mutableListOf<String>()

        val targetPaths = listOf(
            File(filesDir, LOCAL_CONSENT_RELATIVE_PATH),
            File(filesDir, CMP_CONSENT_DIR_NAME),
            File(filesDir.absolutePath + CMP_CONSENT_DIR_NAME + "/consent.0"),
            File(filesDir.absolutePath + CMP_CONSENT_DIR_NAME)
        ).distinctBy { it.absolutePath }

        targetPaths.forEach { target ->
            when {
                !target.exists() -> Unit
                deleteRecursively(target) -> deletedTargets += target.absolutePath
                else -> failedTargets += target.absolutePath
            }
        }

        val sharedPrefsSummary = clearCmpRelatedSharedPreferences()
        val processorCleared = clearSdkConsentProcessorState()
        rebuildSdkManagers("reset_local_cmp_state")

        when {
            failedTargets.isNotEmpty() -> {
                appendLog("CMP 本地状态清理完成，但以下路径删除失败：${failedTargets.joinToString()}")
            }

            deletedTargets.isEmpty() -> {
                appendLog("CMP 本地状态清理完成：未发现 consent 文件，已处理 shared_prefs 和 SDK 内存态")
            }

            else -> {
                appendLog("CMP 本地状态清理完成：已删除 ${deletedTargets.size} 个路径")
            }
        }
        appendLog("shared_prefs 清理摘要：$sharedPrefsSummary")
        appendLog("SDK Processor 内存清理：${if (processorCleared) "成功" else "失败(见上方日志)"}")
        refreshLocalSnapshot("delete_local_state")
    }

    private fun releaseSdkManagers() {
        runCatching { popupCmpConsentManager.dismissCmpFragment() }
        runCatching { popupCmpConsentManager.release() }
        runCatching { cmpPopStateManager.release() }
    }

    private fun rebuildSdkManagers(reason: String) {
        releaseSdkManagers()
        popupCmpConsentManager = CmpConsentManager()
        cmpPopStateManager = CmpPopStateManager()
        appendLog("已重建 CMP SDK manager，reason=$reason")
        updateCmpStatusUi()
    }

    private fun clearCmpRelatedSharedPreferences(): String {
        var deletedPrefFiles = 0
        var removedKeys = 0

        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultKeysToRemove = defaultPrefs.all.keys.filter { key ->
            key.startsWith("IABTCF_") || key.contains("cmp", ignoreCase = true) ||
                key.contains("consent", ignoreCase = true) || key.contains("gdpr", ignoreCase = true)
        }
        if (defaultKeysToRemove.isNotEmpty()) {
            val editor = defaultPrefs.edit()
            defaultKeysToRemove.forEach(editor::remove)
            editor.apply()
            removedKeys += defaultKeysToRemove.size
        }

        val prefDir = File(applicationInfo.dataDir, "shared_prefs")
        val prefNames = prefDir.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            .orEmpty()

        val forceDeleteNames = setOf("AppPreferences", "tcl_ad")
        prefNames.forEach { prefName ->
            val shouldDeleteWholeFile = prefName in forceDeleteNames
            if (shouldDeleteWholeFile) {
                if (deleteSharedPreferences(prefName)) {
                    deletedPrefFiles++
                }
                return@forEach
            }

            val prefs = getSharedPreferences(prefName, MODE_PRIVATE)
            val keysToRemove = prefs.all.keys.filter { key ->
                key.startsWith("IABTCF_") || key.contains("cmp", ignoreCase = true) ||
                    key.contains("consent", ignoreCase = true) || key.contains("gdpr", ignoreCase = true)
            }
            if (keysToRemove.isNotEmpty()) {
                val editor = prefs.edit()
                keysToRemove.forEach(editor::remove)
                editor.apply()
                removedKeys += keysToRemove.size
            }
        }

        return "deletedPrefFiles=$deletedPrefFiles,removedKeys=$removedKeys,scannedPrefFiles=${prefNames.size}"
    }

    private fun clearSdkConsentProcessorState(): Boolean {
        return runCatching {
            val processorClass = Class.forName("com.tcl.ff.component.oversea.model.CMPConsentDataProcessor")
            val companion = processorClass.getField("a").get(null)
            val processor = companion.javaClass.getDeclaredMethod("a").apply {
                isAccessible = true
            }.invoke(companion) ?: return false

            // SDK 内部 b() 对应 clearConsentList，用于清掉进程内缓存态
            processorClass.getDeclaredMethod("b").apply {
                isAccessible = true
            }.invoke(processor)
            true
        }.onFailure { error ->
            appendLog("清理 SDK Processor 内存态失败：${error.message}")
        }.getOrDefault(false)
    }

    private fun deleteRecursively(target: File): Boolean {
        if (!target.exists()) {
            return true
        }
        if (target.isDirectory) {
            target.listFiles()?.forEach { child ->
                if (!deleteRecursively(child)) {
                    return false
                }
            }
        }
        return target.delete()
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

    private fun resolveDefaultDeviceId(): String {
        val overrideDeviceId = BuildConfig.CMP_DEVICE_ID_OVERRIDE
        if (overrideDeviceId.isNotBlank()) {
            return overrideDeviceId
        }
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    }

    private fun resolveDeviceMake(): String {
        return Build.MANUFACTURER.orEmpty().ifBlank { "android" }.lowercase(Locale.US)
    }

    private fun resolveClientType(): String {
        return Build.MODEL.orEmpty().ifBlank { "android" }
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

    private fun stripSavedFragmentState(savedInstanceState: Bundle?) {
        val fragmentTag = findSavedFragmentTag()
        savedInstanceState?.remove(fragmentTag)
        savedInstanceState
            ?.getBundle("androidx.lifecycle.BundlableSavedStateRegistry.key")
            ?.remove(fragmentTag)
    }

    private fun findSavedFragmentTag(): String {
        return runCatching {
            val field = FragmentActivity::class.java.getDeclaredField("FRAGMENTS_TAG")
            field.isAccessible = true
            field.get(null)?.toString()
        }.getOrNull().orEmpty().ifBlank { "android:support:fragments" }
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
}
