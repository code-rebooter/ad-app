package com.smart.android.ad_app.haier

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import com.itv.component.unified.UnifiedAdConfig
import com.itv.component.unified.UnifiedAdRequestCallbacks
import com.itv.component.unified.UnifiedAdSdk
import com.itv.component.unified.UnifiedAdSession
import com.smart.android.ad_app.AdChannelResolver
import com.smart.android.ad_app.BuildConfig
import com.smart.android.ad_app.R
import java.io.File

class HaierLsapDebugEntryActivity : Activity() {

    private lateinit var summaryView: TextView
    private lateinit var logView: TextView
    private lateinit var adContainer: FrameLayout
    private lateinit var initButton: TextView

    private val logLines = ArrayList<String>()
    private var session: UnifiedAdSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_haier_lsap_test_entry)

        summaryView = findViewById(R.id.summary_text)
        logView = findViewById(R.id.log_text)
        adContainer = findViewById(R.id.ad_container)
        initButton = findViewById(R.id.init_button)

        initButton.setOnClickListener {
            initializeSdk()
        }
        findViewById<TextView>(R.id.attach_button).setOnClickListener {
            attachPlayer()
        }
        findViewById<TextView>(R.id.detach_button).setOnClickListener {
            detachPlayer("manual_detach")
        }

        renderSummary()
        initButton.post {
            initButton.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val currentSession = session
        if (currentSession != null && currentSession.handleKeyEvent(keyCode, event)) {
            appendLog("session handled keyCode=$keyCode")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        detachPlayer("activity_destroy")
        super.onDestroy()
    }

    private fun renderSummary() {
        summaryView.text = buildString {
            append("flavor=")
            append(BuildConfig.FLAVOR)
            append('\n')
            append("package=")
            append(packageName)
            append('\n')
            append("channel=")
            append(AdChannelResolver.currentChannel())
            append('\n')
            append("model=")
            append(BuildConfig.MODEL)
            append('\n')
            append("process=")
            append(currentProcessName())
            append('\n')
            append("isMainProcess=")
            append(currentProcessName() == packageName)
            append('\n')
            append("appKey=")
            append(BuildConfig.UNIFIED_AD_APP_KEY)
            append('\n')
            append("tagId=")
            append(BuildConfig.UNIFIED_AD_TAG_ID)
        }
    }

    private fun initializeSdk() {
        val result = runCatching {
            if (!UnifiedAdSdk.isInitialized()) {
                UnifiedAdSdk.init(
                    applicationContext,
                    UnifiedAdConfig.Builder()
                        .lsapAppKey(BuildConfig.UNIFIED_AD_APP_KEY)
                        .build()
                )
            }
            "UnifiedAdSdk init success, isInitialized=${UnifiedAdSdk.isInitialized()}"
        }.getOrElse { error ->
            "UnifiedAdSdk init failed: ${error.javaClass.simpleName}: ${error.message}"
        }
        appendLog(result)
    }

    private fun attachPlayer() {
        detachPlayer("reattach")
        val result = runCatching {
            adContainer.removeAllViews()
            session = UnifiedAdSdk.requestAd(
                this,
                adContainer,
                BuildConfig.UNIFIED_AD_TAG_ID,
                object : UnifiedAdRequestCallbacks {
                    override fun onAdLoading() {
                        appendLog("onAdLoading tagId=${BuildConfig.UNIFIED_AD_TAG_ID} childCount=${adContainer.childCount}")
                    }

                    override fun onAdPlayStarted() {
                        appendLog("onAdPlayStarted tagId=${BuildConfig.UNIFIED_AD_TAG_ID} childCount=${adContainer.childCount}")
                    }

                    override fun onAdPlayEnded(success: Boolean) {
                        appendLog("onAdPlayEnded success=$success childCount=${adContainer.childCount}")
                    }

                    override fun onRequestFinished(success: Boolean) {
                        appendLog("onRequestFinished success=$success childCount=${adContainer.childCount}")
                    }
                }
            )
            "requestAd started, tagId=${BuildConfig.UNIFIED_AD_TAG_ID}, container=${adContainer.width}x${adContainer.height}, isInitialized=${UnifiedAdSdk.isInitialized()}"
        }.getOrElse { error ->
            "requestAd failed: ${error.javaClass.simpleName}: ${error.message}"
        }
        appendLog(result)
    }

    private fun detachPlayer(reason: String) {
        val currentSession = session ?: return
        runCatching {
            currentSession.detach()
            adContainer.removeAllViews()
            appendLog("detach success, reason=$reason")
        }.onFailure { error ->
            appendLog("detach failed: ${error.javaClass.simpleName}: ${error.message}")
        }
        session = null
    }

    private fun appendLog(message: String) {
        Log.i(DEBUG_TAG, message)
        logLines.add(message)
        while (logLines.size > 12) {
            logLines.removeAt(0)
        }
        logView.text = buildString {
            logLines.forEachIndexed { index, line ->
                if (index > 0) {
                    append('\n')
                }
                append(line)
            }
            append('\n')
            append("manufacturer=")
            append(Build.MANUFACTURER.orEmpty())
            append('\n')
            append("sdkInt=")
            append(Build.VERSION.SDK_INT)
        }
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return runCatching {
            File("/proc/${Process.myPid()}/cmdline").readText().trim { it <= ' ' }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
    }

    companion object {
        private const val DEBUG_TAG = "HaierLsapDebug"
    }
}
