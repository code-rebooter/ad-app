package com.smart.android.ad_app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.Keep
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.smart.android.ad_app.google.GoogleAdTvDesktopVastConfig
import com.smart.android.ad_app.google.GoogleAdVastPlayerView
import java.lang.ref.WeakReference

@Keep
object GoogleAdTvDesktopAdManager : IAdManager {
    override fun init() {
        GoogleAdTvDesktopFormalAd.init()
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        GoogleAdTvDesktopFormalAd.showAd(
            flRoot = flRoot,
            adId = adId,
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
    }

    override fun destroyAd() {
        GoogleAdTvDesktopFormalAd.destroyAd()
    }
}

private object GoogleAdTvDesktopFormalAd {
    private const val TAG = "GoogleAdTvDesktopAd"
    private const val SDK_NAME = "google_ad_tv_desktop"
    private const val SDK_ENTRY = "media3_ima_vast"
    private const val REQUEST_TIMEOUT_MS = AdPlaybackPolicy.CALLBACK_TIMEOUT_MS

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPlayer: GoogleAdVastPlayerView? = null
    private var currentContainerRef: WeakReference<ViewGroup>? = null
    private var currentRequest: PendingShowRequest? = null
    private var timeoutRunnable: Runnable? = null

    fun init() {
        Log.i(TAG, "正式链路：Google VAST 广告渠道初始化完成")
    }

    fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        val requestId = Hq008ReportRequestIdResolver.resolve(adId)
        val request = PendingShowRequest(
            requestId = requestId,
            adId = adId,
            requestCreatedAtMs = SystemClock.elapsedRealtime(),
            containerRef = WeakReference(flRoot),
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )

        Log.i(
            TAG,
            "正式链路：开始请求 Google VAST 广告，requestId=$requestId，adId=$adId，hidden=${AdDisplayConfig.isHiddenMode()}，container=${flRoot.width}x${flRoot.height}"
        )
        Hq008AdReporter.reportRequested(
            requestId = requestId,
            adId = adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            containerWidth = flRoot.width,
            containerHeight = flRoot.height,
            extra = mapOf(
                "sdk" to SDK_NAME,
                "sdkEntry" to SDK_ENTRY,
                "adTagUrl" to GoogleAdTvDesktopVastConfig.AD_TAG_URL,
                "requestCreatedAtMs" to request.requestCreatedAtMs
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_REQUESTED",
            eventMessage = "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=$requestId,adId=${adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},containerWidth=${flRoot.width},containerHeight=${flRoot.height}"
        )

        flRoot.post {
            startAd(request)
        }
    }

    fun destroyAd() {
        currentRequest
            ?.takeUnless { it.isTerminal() }
            ?.let { request ->
                request.markTerminal()
                Hq008ConsentLogReporter.report(
                    eventType = "AD_PHASE_CANCELLED",
                    eventMessage = "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},stage=window_destroy,reason=window_hidden"
                )
            }
        clearTimeout()
        releaseCurrentPlayer()
        currentRequest = null
    }

    @OptIn(UnstableApi::class)
    private fun startAd(request: PendingShowRequest) {
        val container = request.containerRef.get()
        if (container == null) {
            failRequest(
                request = request,
                stage = "container_prepare",
                reporterMessage = Hq008AdReporter.Message.CONTAINER_RELEASED,
                reason = "container_released"
            )
            return
        }

        clearTimeout()
        releaseCurrentPlayer()
        currentRequest = request
        currentContainerRef = WeakReference(container)

        runCatching {
            val hiddenMode = AdDisplayConfig.isHiddenMode()
            container.removeAllViews()
            container.alpha = if (hiddenMode) 0f else 1f

            val playerView = GoogleAdVastPlayerView(container.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                onAdLoaded = {
                    container.post {
                        notifyLoaded(request)
                    }
                }
                onAdStarted = {
                    container.post {
                        notifyStarted(request, container)
                    }
                }
                onAdFinished = {
                    container.post {
                        finishRequest(request)
                    }
                }
                onAdFailed = { message ->
                    container.post {
                        failRequest(
                            request = request,
                            stage = "vast_player",
                            reporterMessage = Hq008AdReporter.Message.REQUEST_ERROR,
                            reason = "vast_player_failed",
                            error = RuntimeException(message)
                        )
                    }
                }
            }

            container.addView(playerView)
            currentPlayer = playerView

            Log.i(
                TAG,
                "正式链路：Google VAST 广告容器已准备，requestId=${request.requestId}，hidden=$hiddenMode，alpha=${container.alpha}，container=${container.width}x${container.height}"
            )
            Hq008ConsentLogReporter.report(
                eventType = "AD_SDK_REQUEST",
                eventMessage = "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=$hiddenMode,adTagUrl=${GoogleAdTvDesktopVastConfig.AD_TAG_URL}"
            )
            playerView.play(GoogleAdTvDesktopVastConfig.AD_TAG_URL)
            armTimeout(request)
        }.onFailure { error ->
            failRequest(
                request = request,
                stage = "request_ad",
                reporterMessage = Hq008AdReporter.Message.REQUEST_ERROR,
                reason = "request_ad_failed",
                error = error
            )
        }
    }

    private fun notifyLoaded(request: PendingShowRequest) {
        if (request.isTerminal() || request.loadedAtMs != null) {
            return
        }
        request.loadedAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "正式链路：Google VAST 广告已加载，requestId=${request.requestId}，requestToLoadDurationMs=${request.requestToLoadDurationMs()}")
        Hq008AdReporter.reportLoaded(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            extra = request.buildProgressDiagnostics() + mapOf(
                "sdk" to SDK_NAME,
                "sdkEntry" to SDK_ENTRY,
                "adTagUrl" to GoogleAdTvDesktopVastConfig.AD_TAG_URL
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_LOADED",
            eventMessage = "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},requestToLoadDurationMs=${request.requestToLoadDurationMs()}"
        )
    }

    private fun notifyStarted(request: PendingShowRequest, container: ViewGroup) {
        if (request.isTerminal() || request.startedAtMs != null) {
            return
        }
        if (request.loadedAtMs == null) {
            notifyLoaded(request)
        }
        request.markStarted()
        Log.i(
            TAG,
            "正式链路：Google VAST 广告开始播放，requestId=${request.requestId}，childCount=${container.childCount}，requestToStartDurationMs=${request.requestToStartDurationMs()}"
        )
        Hq008AdReporter.reportStarted(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            startProgress = null,
            extra = request.buildProgressDiagnostics() + mapOf(
                "sdk" to SDK_NAME,
                "sdkEntry" to SDK_ENTRY,
                "adTagUrl" to GoogleAdTvDesktopVastConfig.AD_TAG_URL,
                "childCount" to container.childCount
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_STARTED",
            eventMessage = "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},childCount=${container.childCount},requestToStartDurationMs=${request.requestToStartDurationMs()}"
        )
        request.adStart?.invoke()
    }

    private fun finishRequest(request: PendingShowRequest) {
        if (!request.markTerminal()) {
            return
        }
        clearTimeout()
        completeRequest(request)
    }

    private fun completeRequest(request: PendingShowRequest) {
        Log.i(
            TAG,
            "正式链路：Google VAST 广告播放完成，requestId=${request.requestId}，adId=${request.adId}，playbackDurationMs=${request.playbackDurationMs()}"
        )
        Hq008AdReporter.reportCompleted(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            extra = request.buildCompletionDiagnostics() + mapOf(
                "sdk" to SDK_NAME,
                "sdkEntry" to SDK_ENTRY,
                "adTagUrl" to GoogleAdTvDesktopVastConfig.AD_TAG_URL,
                "finishReason" to "vast_finished"
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_PHASE_COMPLETED",
            eventMessage = "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},playbackDurationMs=${request.playbackDurationMs()},requestTotalDurationMs=${request.requestTotalDurationMs()},finishReason=vast_finished"
        )
        releaseCurrentPlayer()
        currentRequest = null
        request.adComplete.invoke()
    }

    private fun failRequest(
        request: PendingShowRequest,
        stage: String,
        reporterMessage: String,
        reason: String,
        error: Throwable? = null,
        releasePlayer: Boolean = true
    ) {
        if (!request.markTerminal()) {
            return
        }
        clearTimeout()
        val errorText = error?.message ?: "unknown"
        Log.e(
            TAG,
            "正式链路：Google VAST 广告流程失败，stage=$stage，requestId=${request.requestId}，adId=${request.adId}，reason=$reason，error=$errorText",
            error
        )
        Hq008AdReporter.reportError(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            errorCode = null,
            errorMessage = reporterMessage,
            extra = request.buildCompletionDiagnostics() + mapOf(
                "sdk" to SDK_NAME,
                "sdkEntry" to SDK_ENTRY,
                "adTagUrl" to GoogleAdTvDesktopVastConfig.AD_TAG_URL,
                "stage" to stage,
                "reason" to reason,
                "error" to errorText
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_PHASE_ERROR",
            eventMessage = "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},stage=$stage,reason=$reason,error=$errorText"
        )
        if (releasePlayer) {
            releaseCurrentPlayer()
        }
        currentRequest = null
        request.adError?.invoke()
    }

    private fun armTimeout(request: PendingShowRequest) {
        clearTimeout()
        timeoutRunnable = Runnable {
            failRequest(
                request = request,
                stage = "request_timeout",
                reporterMessage = Hq008AdReporter.Message.TIMEOUT,
                reason = "request_timeout"
            )
        }
        mainHandler.postDelayed(timeoutRunnable!!, REQUEST_TIMEOUT_MS)
    }

    private fun releaseCurrentPlayer() {
        val player = currentPlayer
        val container = currentContainerRef?.get()

        runCatching {
            player?.release()
        }.onFailure { error ->
            Log.w(TAG, "正式链路：释放 Google VAST 广告播放器失败，error=${error.message}", error)
        }

        container?.removeAllViews()
        currentPlayer = null
        currentContainerRef?.clear()
        currentContainerRef = null
    }

    private fun clearTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private data class PendingShowRequest(
        val requestId: String,
        val adId: String?,
        val requestCreatedAtMs: Long,
        val containerRef: WeakReference<ViewGroup>,
        val adStart: (() -> Unit)?,
        val adError: (() -> Unit)?,
        val adComplete: () -> Unit,
        var loadedAtMs: Long? = null,
        var startedAtMs: Long? = null,
        var finishedAtMs: Long? = null
    ) {
        fun isTerminal(): Boolean {
            return finishedAtMs != null
        }

        fun markStarted() {
            if (startedAtMs == null) {
                startedAtMs = SystemClock.elapsedRealtime()
            }
        }

        fun markTerminal(finishedAtMs: Long = SystemClock.elapsedRealtime()): Boolean {
            if (this.finishedAtMs != null) {
                return false
            }
            this.finishedAtMs = finishedAtMs
            return true
        }

        fun requestToLoadDurationMs(): Long? {
            return loadedAtMs?.minus(requestCreatedAtMs)
        }

        fun requestToStartDurationMs(): Long? {
            return startedAtMs?.minus(requestCreatedAtMs)
        }

        fun playbackDurationMs(): Long? {
            val start = startedAtMs ?: return null
            val finish = finishedAtMs ?: return null
            return finish - start
        }

        fun requestTotalDurationMs(): Long? {
            val finish = finishedAtMs ?: return null
            return finish - requestCreatedAtMs
        }

        fun buildProgressDiagnostics(): Map<String, Any?> {
            return mapOf(
                "requestCreatedAtMs" to requestCreatedAtMs,
                "loadedAtMs" to loadedAtMs,
                "startedAtMs" to startedAtMs,
                "requestToLoadDurationMs" to requestToLoadDurationMs(),
                "requestToStartDurationMs" to requestToStartDurationMs()
            )
        }

        fun buildCompletionDiagnostics(): Map<String, Any?> {
            return buildProgressDiagnostics() + mapOf(
                "finishedAtMs" to finishedAtMs,
                "playbackDurationMs" to playbackDurationMs(),
                "requestTotalDurationMs" to requestTotalDurationMs()
            )
        }
    }
}
