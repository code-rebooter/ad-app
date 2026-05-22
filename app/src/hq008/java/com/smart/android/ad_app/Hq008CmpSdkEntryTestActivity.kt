package com.smart.android.ad_app

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class Hq008CmpSdkEntryTestActivity : FragmentActivity() {

    private companion object {
        const val DEFAULT_ZONE = "de"
    }

    private var popupCmpConsentManager = CmpConsentManager()
    private var cmpPopStateManager = CmpPopStateManager()
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var deviceIdInput: EditText
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var showCmpButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        stripSavedFragmentState(savedInstanceState)
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_hq008_cmp_sdk_entry_test)

        deviceIdInput = findViewById(R.id.device_id_input)
        statusView = findViewById(R.id.cmp_status_text)
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

        appendLog(
            "已创建 CMP SDK 正常接入测试页，deviceMake=${resolveDeviceMake()} clientType=${resolveClientType()} zone=$DEFAULT_ZONE deviceId=${deviceIdInput.text}"
        )
        updateCmpStatusUi()
    }

    override fun onDestroy() {
        releaseSdkManagers()
        super.onDestroy()
    }

    private fun releaseSdkManagers() {
        runCatching { popupCmpConsentManager.dismissCmpFragment() }
        runCatching { popupCmpConsentManager.release() }
        runCatching { cmpPopStateManager.release() }
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

    private fun updateCmpStatusUi() {
        val status = popupCmpConsentManager.getCmpStatus()
        val isDataReady = status == CMPStatus.CMP_DATA_READY
        statusView.text = "cmpStatus=$status\nshowCmpPopEnabled=$isDataReady"
        showCmpButton.isEnabled = isDataReady
        showCmpButton.alpha = if (isDataReady) 1f else 0.45f
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
}
