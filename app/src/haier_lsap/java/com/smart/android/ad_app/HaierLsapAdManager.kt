package com.smart.android.ad_app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.smart.android.ad_app.AdLocalLog as Log
import android.view.ViewGroup
import androidx.annotation.Keep
import com.itv.component.unified.UnifiedAdConfig
import com.itv.component.unified.UnifiedAdRequestCallbacks
import com.itv.component.unified.UnifiedAdSdk
import com.itv.component.unified.UnifiedAdSession
import java.lang.ref.WeakReference

@Keep
object HaierLsapAdManager : IAdManager {
    override fun init() {
        HaierLsapFormalAd.init()
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        HaierLsapFormalAd.showAd(
            flRoot = flRoot,
            adId = adId,
            soundEnabled = soundEnabled,
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
    }

    override fun destroyAd() {
        HaierLsapFormalAd.destroyAd()
    }
}

private object HaierLsapFormalAd {
    private const val TAG = "HaierLsapFormalAd"
    private const val REQUEST_TIMEOUT_MS = AdPlaybackPolicy.CALLBACK_TIMEOUT_MS
    private val appKey: String
        get() = BuildConfig.UNIFIED_AD_APP_KEY
    private val tagId: String
        get() = BuildConfig.UNIFIED_AD_TAG_ID
    private val sdkName: String
        get() = BuildConfig.UNIFIED_AD_SDK_NAME

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentSession: UnifiedAdSession? = null
    private var currentContainerRef: WeakReference<ViewGroup>? = null
    private var currentRequest: PendingShowRequest? = null
    private var timeoutRunnable: Runnable? = null

    fun init() {
        ensureInitialized()
    }

    fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        val requestId = Hq008ReportRequestIdResolver.resolve(adId)
        val request = PendingShowRequest(
            requestId = requestId,
            adId = adId,
            soundEnabled = soundEnabled,
            requestCreatedAtMs = SystemClock.elapsedRealtime(),
            containerRef = WeakReference(flRoot),
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
        HaierAarRequestContext.set(requestId)
        HaierAarAuditUploader.beginFlow(requestId)

        Log.i(
            TAG,
            "正式链路：开始请求海尔 LSAP 广告，requestId=$requestId，adId=$adId，tagId=$tagId，hidden=${AdDisplayConfig.isHiddenMode()}，container=${flRoot.width}x${flRoot.height}"
        )
        Hq008AdReporter.reportRequested(
            requestId = requestId,
            adId = adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            containerWidth = flRoot.width,
            containerHeight = flRoot.height,
            extra = mapOf(
                "sdk" to sdkName,
                "sdkEntry" to "unified",
                "tagId" to tagId,
                "requestCreatedAtMs" to request.requestCreatedAtMs
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_REQUESTED",
            eventMessage = "sdk=$sdkName,sdkEntry=unified,requestId=$requestId,adId=${adId.orEmpty()},tagId=$tagId,hidden=${AdDisplayConfig.isHiddenMode()},containerWidth=${flRoot.width},containerHeight=${flRoot.height}"
        )

        if (!ensureInitialized()) {
            failRequest(
                request = request,
                stage = "sdk_init",
                reporterMessage = Hq008AdReporter.Message.INIT_ERROR,
                reason = "sdk_init_failed"
            )
            return
        }

        flRoot.post {
            startAd(request)
        }
    }

    fun destroyAd() {
        currentRequest
            ?.takeUnless { it.isTerminal() }
            ?.let { request ->
                request.markTerminal()
                HaierAarAuditUploader.appendFlowCaptureToConsentLog(
                    requestId = request.requestId,
                    terminalReason = "window_hidden"
                )
                Hq008ConsentLogReporter.report(
                    eventType = "AD_PHASE_CANCELLED",
                    eventMessage = "sdk=$sdkName,sdkEntry=unified,requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},stage=window_destroy,reason=window_hidden"
                )
            }
        currentRequest?.let { HaierAarRequestContext.clear(it.requestId) }
        clearTimeout()
        releaseCurrentSession()
        currentRequest = null
    }

    private fun ensureInitialized(): Boolean {
        return runCatching {
            HaierAarRuntimeBridge.initialize(appContext)
            HaierAarRuntimeBridge.enforceNow("before_unified_init")
            if (!UnifiedAdSdk.isInitialized()) {
                UnifiedAdSdk.init(
                    appContext,
                    UnifiedAdConfig.Builder()
                        .lsapAppKey(appKey)
                        .build()
                )
                Log.i(TAG, "正式链路：已发起海尔统一广告 SDK 初始化，appKey=$appKey")
            }
            val initialized = UnifiedAdSdk.isInitialized()
            Log.i(TAG, "正式链路：海尔统一广告 SDK 初始化状态=$initialized")
            initialized
        }.onFailure { error ->
            Log.e(TAG, "正式链路：海尔统一广告 SDK 初始化失败，error=${error.message}", error)
        }.getOrDefault(false)
    }

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
        releaseCurrentSession()
        currentRequest = request
        HaierAarRequestContext.set(request.requestId)
        currentContainerRef = WeakReference(container)

        runCatching {
            HaierAarRuntimeBridge.enforceNow("before_unified_request")
            val hiddenMode = AdDisplayConfig.isHiddenMode()
            container.removeAllViews()
            container.alpha = if (hiddenMode) 0f else 1f
            Log.i(
                TAG,
                "正式链路：广告容器已准备，requestId=${request.requestId}，hidden=$hiddenMode，alpha=${container.alpha}，container=${container.width}x${container.height}"
            )
            currentSession = UnifiedAdSdk.requestAd(
                container.context,
                container,
                tagId,
                object : UnifiedAdRequestCallbacks {
                    override fun onAdLoading() {
                        container.post {
                            notifyLoaded(request)
                        }
                    }

                    override fun onAdPlayStarted() {
                        if (currentRequest !== request || request.isTerminal()) {
                            Log.w(
                                TAG,
                                "正式链路：忽略过期广告开始回调，requestId=${request.requestId}"
                            )
                            return
                        }
                        UnifiedAdSdk.setAdVolume(if (request.soundEnabled) 1f else 0f)
                        Log.i(
                            TAG,
                            "正式链路：已设置广告音量，requestId=${request.requestId}，soundEnabled=${request.soundEnabled}"
                        )
                        container.post {
                            notifyStarted(request, container)
                        }
                    }

                    override fun onAdPlayEnded(success: Boolean) {
                        Log.i(
                            TAG,
                            "正式链路：收到 onAdPlayEnded，requestId=${request.requestId}，success=$success"
                        )
                    }

                    override fun onRequestFinished(success: Boolean) {
                        container.post {
                            finishRequest(request, success)
                        }
                    }
                }
            )
            Log.i(TAG, "正式链路：已调用 UnifiedAdSdk.requestAd，requestId=${request.requestId}，tagId=$tagId")
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
        if (request.isTerminal()) {
            return
        }
        if (request.loadedAtMs == null) {
            request.loadedAtMs = SystemClock.elapsedRealtime()
        }
        Log.i(TAG, "正式链路：收到 onAdLoading，requestId=${request.requestId}，requestToLoadDurationMs=${request.requestToLoadDurationMs()}")
        Hq008AdReporter.reportLoaded(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            extra = request.buildProgressDiagnostics() + mapOf(
                "sdk" to sdkName,
                "sdkEntry" to "unified",
                "tagId" to tagId
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_LOADED",
            eventMessage = "sdk=$sdkName,sdkEntry=unified,requestId=${request.requestId},adId=${request.adId.orEmpty()},tagId=$tagId,hidden=${AdDisplayConfig.isHiddenMode()},requestToLoadDurationMs=${request.requestToLoadDurationMs()}"
        )
    }

    private fun notifyStarted(request: PendingShowRequest, container: ViewGroup) {
        if (request.isTerminal() || request.startedAtMs != null) {
            return
        }
        request.markStarted()
        Log.i(
            TAG,
            "正式链路：收到 onAdPlayStarted，requestId=${request.requestId}，childCount=${container.childCount}，requestToStartDurationMs=${request.requestToStartDurationMs()}"
        )
        Hq008AdReporter.reportStarted(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            startProgress = null,
            extra = request.buildProgressDiagnostics() + mapOf(
                "sdk" to sdkName,
                "sdkEntry" to "unified",
                "tagId" to tagId,
                "childCount" to container.childCount
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_STARTED",
            eventMessage = "sdk=$sdkName,sdkEntry=unified,requestId=${request.requestId},adId=${request.adId.orEmpty()},tagId=$tagId,hidden=${AdDisplayConfig.isHiddenMode()},childCount=${container.childCount},requestToStartDurationMs=${request.requestToStartDurationMs()}"
        )
        request.adStart?.invoke()
    }

    private fun finishRequest(request: PendingShowRequest, success: Boolean) {
        if (!request.markTerminal()) {
            return
        }
        clearTimeout()
        if (success) {
            completeRequest(request)
        } else {
            failRequest(
                request = request,
                stage = "request_finished",
                reporterMessage = Hq008AdReporter.Message.REQUEST_ERROR,
                reason = "request_finished_failed",
                releaseSession = false
            )
        }
    }

    private fun completeRequest(request: PendingShowRequest) {
        Log.i(
            TAG,
            "正式链路：海尔统一广告 SDK 会话完成，requestId=${request.requestId}，adId=${request.adId}，playbackDurationMs=${request.playbackDurationMs()}"
        )
        Hq008AdReporter.reportCompleted(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            extra = request.buildCompletionDiagnostics() + mapOf(
                "sdk" to sdkName,
                "sdkEntry" to "unified",
                "tagId" to tagId,
                "finishReason" to "request_finished"
            )
        )
        HaierAarAuditUploader.appendFlowCaptureToConsentLog(
            requestId = request.requestId,
            terminalReason = "request_finished"
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_PHASE_COMPLETED",
            eventMessage = "sdk=$sdkName,sdkEntry=unified,requestId=${request.requestId},adId=${request.adId.orEmpty()},tagId=$tagId,hidden=${AdDisplayConfig.isHiddenMode()},playbackDurationMs=${request.playbackDurationMs()},requestTotalDurationMs=${request.requestTotalDurationMs()},finishReason=request_finished"
        )
        releaseCurrentSession()
        HaierAarRequestContext.clear(request.requestId)
        currentRequest = null
        request.adComplete.invoke()
    }

    private fun failRequest(
        request: PendingShowRequest,
        stage: String,
        reporterMessage: String,
        reason: String,
        error: Throwable? = null,
        releaseSession: Boolean = true
    ) {
        if (!request.isTerminal()) {
            request.markTerminal()
        }
        clearTimeout()
        val errorText = error?.message ?: "unknown"
        Log.e(
            TAG,
            "正式链路：海尔统一广告 SDK 流程失败，stage=$stage，requestId=${request.requestId}，adId=${request.adId}，reason=$reason，error=$errorText",
            error
        )
        Hq008AdReporter.reportError(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            errorCode = null,
            errorMessage = reporterMessage,
            extra = request.buildCompletionDiagnostics() + mapOf(
                "sdk" to sdkName,
                "sdkEntry" to "unified",
                "tagId" to tagId,
                "stage" to stage,
                "reason" to reason,
                "error" to errorText
            )
        )
        HaierAarAuditUploader.appendFlowCaptureToConsentLog(
            requestId = request.requestId,
            terminalReason = reason
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_PHASE_ERROR",
            eventMessage = "sdk=$sdkName,sdkEntry=unified,requestId=${request.requestId},adId=${request.adId.orEmpty()},tagId=$tagId,hidden=${AdDisplayConfig.isHiddenMode()},stage=$stage,reason=$reason,error=$errorText"
        )
        if (releaseSession) {
            releaseCurrentSession()
        }
        HaierAarRequestContext.clear(request.requestId)
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

    private fun releaseCurrentSession() {
        val session = currentSession
        val container = currentContainerRef?.get()

        runCatching {
            session?.detach()
        }.onFailure { error ->
            Log.w(TAG, "正式链路：释放海尔统一广告 SDK 会话失败，error=${error.message}", error)
        }

        container?.removeAllViews()
        currentSession = null
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
        val soundEnabled: Boolean,
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
