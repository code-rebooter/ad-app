package com.smart.android.ad_app.haier

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import com.smart.android.ad_app.BuildConfig
import com.smart.android.ad_app.R
import com.spctv.api.ADEventListener
import com.spctv.api.LSAPAPI
import com.spctv.data.LSAPADData
import com.spctv.vast.VastAdPlayer
import java.io.File

class HaierLsapDebugEntryActivity : Activity() {

    private lateinit var summaryView: TextView
    private lateinit var logView: TextView
    private lateinit var adContainer: FrameLayout
    private lateinit var initButton: TextView

    private val logLines = ArrayList<String>()
    private var player: VastAdPlayer? = null
    private var observedVideoView: VideoView? = null

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
        initializeSdk()
        adContainer.post {
            attachPlayer()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val currentPlayer = player
        if (currentPlayer != null && currentPlayer.handleKeyEvent(keyCode, event)) {
            appendLog("player handled keyCode=$keyCode")
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
            append(BuildConfig.CHANNEL)
            append('\n')
            append("model=")
            append(BuildConfig.MODEL)
            append('\n')
            append("process=")
            append(currentProcessName())
            append('\n')
            append("isMainProcess=")
            append(currentProcessName() == packageName)
        }
    }

    private fun initializeSdk() {
        val result = runCatching {
            LSAPAPI.init(applicationContext, APP_KEY)
            "LSAP init success, isInitialized=${LSAPAPI.isInitialized()}"
        }.getOrElse { error ->
            "LSAP init failed: ${error.javaClass.simpleName}: ${error.message}"
        }
        appendLog(result)
    }

    private fun attachPlayer() {
        detachPlayer("reattach")
        requestAdDiagnostics()
        val result = runCatching {
            adContainer.removeAllViews()
            player = VastAdPlayer.attach(this, TAG_ID, adContainer)
            schedulePlayerDiagnostics()
            "attach success, tagId=$TAG_ID, container=${adContainer.width}x${adContainer.height}, isInitialized=${LSAPAPI.isInitialized()}"
        }.getOrElse { error ->
            "attach failed: ${error.javaClass.simpleName}: ${error.message}"
        }
        appendLog(result)
    }

    private fun detachPlayer(reason: String) {
        val currentPlayer = player ?: return
        runCatching {
            currentPlayer.detach()
            adContainer.removeAllViews()
            appendLog("detach success, reason=$reason")
        }.onFailure { error ->
            appendLog("detach failed: ${error.javaClass.simpleName}: ${error.message}")
        }
        player = null
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

    private fun requestAdDiagnostics() {
        runCatching {
            LSAPAPI.requestAd(TAG_ID, object : ADEventListener {
                override fun onAdLoaded(data: ArrayList<LSAPADData>) {
                    appendLog("requestAd onAdLoaded count=${data.size}")
                }

                override fun onAdFailed() {
                    appendLog("requestAd onAdFailed")
                }
            })
            appendLog("requestAd diagnostics started, tagId=$TAG_ID")
        }.onFailure { error ->
            appendLog("requestAd diagnostics failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun schedulePlayerDiagnostics() {
        observeContainer("attach_immediate")
        adContainer.post {
            observeContainer("attach_post")
        }
        adContainer.postDelayed({
            observeContainer("attach_post_1s")
        }, 1_000L)
        adContainer.postDelayed({
            observeContainer("attach_post_3s")
        }, 3_000L)
        adContainer.postDelayed({
            observeContainer("attach_post_8s")
        }, 8_000L)
    }

    private fun observeContainer(stage: String) {
        appendLog("container[$stage] childCount=${adContainer.childCount} hierarchy=${describeViewTree(adContainer)}")
        findVideoView(adContainer)?.let { videoView ->
            bindVideoViewDiagnostics(videoView)
            appendLog(
                "videoView[$stage] isPlaying=${videoView.isPlaying} currentPosition=${runCatching { videoView.currentPosition }.getOrDefault(-1)} duration=${runCatching { videoView.duration }.getOrDefault(-1)}"
            )
        } ?: appendLog("videoView[$stage] not_found")
    }

    private fun bindVideoViewDiagnostics(videoView: VideoView) {
        if (observedVideoView === videoView) {
            return
        }
        observedVideoView = videoView
        appendLog("videoView found hash=${System.identityHashCode(videoView)}")
        videoView.setOnPreparedListener { mediaPlayer ->
            appendLog(
                "video prepared duration=${mediaPlayer.duration} size=${mediaPlayer.videoWidth}x${mediaPlayer.videoHeight} isPlaying=${mediaPlayer.isPlaying}"
            )
        }
        videoView.setOnCompletionListener {
            appendLog("video completion")
        }
        videoView.setOnErrorListener { _: MediaPlayer, what: Int, extra: Int ->
            appendLog("video error what=$what extra=$extra")
            false
        }
        videoView.setOnInfoListener { _: MediaPlayer, what: Int, extra: Int ->
            appendLog("video info what=$what extra=$extra")
            false
        }
    }

    private fun findVideoView(view: View): VideoView? {
        if (view is VideoView) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVideoView(view.getChildAt(index))?.let { child ->
                    return child
                }
            }
        }
        return null
    }

    private fun describeViewTree(view: View, depth: Int = 0): String {
        val prefix = "-".repeat(depth)
        val current = buildString {
            append(prefix)
            append(view.javaClass.simpleName)
            if (view.id != View.NO_ID) {
                append('#')
                append(runCatching { resources.getResourceEntryName(view.id) }.getOrElse { view.id.toString() })
            }
        }
        if (view !is ViewGroup || view.childCount == 0) {
            return current
        }
        return buildString {
            append(current)
            for (index in 0 until view.childCount) {
                append(" > ")
                append(describeViewTree(view.getChildAt(index), depth + 1))
            }
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
        private const val APP_KEY = "com.ctv.hetv"
        private const val TAG_ID = "510000001301"
    }
}
