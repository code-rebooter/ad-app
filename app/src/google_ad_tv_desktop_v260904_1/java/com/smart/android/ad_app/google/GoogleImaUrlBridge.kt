package com.smart.android.ad_app.google

import android.net.Uri
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.Keep
import com.google.ads.interactivemedia.v3.api.AdPodInfo
import com.google.ads.interactivemedia.v3.api.AdsLoader
import com.google.ads.interactivemedia.v3.api.AdsRequest
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate
import com.google.ads.interactivemedia.v3.impl.JavaScriptMessage
import com.google.ads.interactivemedia.v3.impl.data.zzbu
import com.google.ads.interactivemedia.v3.impl.data.zzbx
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.AdPrivacySanitizer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the pinned IMA Core through URL assembly, then consumes its native HTTP command. */
@Keep
object GoogleImaUrlBridge {
    private val main = Handler(Looper.getMainLooper())
    private val owners = WeakHashMap<Any, Owner>()

    interface RequestHandle {
        fun cancel()
    }

    data class FinalUrl(
        val url: String, val userAgent: String?, val imaUrl: String = url,
        val beforeCommonPatchUrl: String = url, val tclPatchEnabled: Boolean = false
    ) {
        val commonPatchChanged: Boolean get() = url != beforeCommonPatchUrl
    }

    fun buildFinalUrl(
        container: ViewGroup,
        adTagUrl: String,
        soundEnabled: Boolean,
        timeoutMs: Long,
        imaTclUrlPatch: Boolean = false,
        onResult: (FinalUrl?, String?) -> Unit
    ): RequestHandle {
        val request = PendingRequest(container, adTagUrl, soundEnabled, imaTclUrlPatch, onResult)
        request.start(timeoutMs)
        return request
    }

    // Called from IMA zzy.zzp before requestAds is delivered to Core. The request
    // context binds the native session to its own cancellable host request.
    @JvmStatic
    fun bindRequest(adsRequest: AdsRequest, sessionId: String, owner: Any): Boolean {
        val request = adsRequest.userRequestContext as? PendingRequest ?: return true
        synchronized(owners) {
            if (request.finished.get()) return false
            owners[owner] = Owner(sessionId, request)
        }
        return true
    }

    // Called before zzba dispatches nativeXhr to either GKS or URLConnection.
    // No response is supplied to IMA, so it cannot parse VAST or start tracking.
    @JvmStatic
    fun consumeNativeRequest(owner: Any, message: JavaScriptMessage): Boolean {
        if (message.zza() != JavaScriptMessage.MsgChannel.nativeXhr ||
            message.zzb() != JavaScriptMessage.MsgType.nativeRequest) return false
        val slot = synchronized(owners) { owners[owner] } ?: return false
        val request = synchronized(owners) { slot.request } ?: return true
        if (message.zzd() != slot.sessionId && message.zzd() != "*") return true
        val networkRequest = (message.zzc() as? zzbu)?.networkRequest ?: run {
            request.finish(null, "ima_native_request_missing")
            return true
        }
        // Core may also issue ancillary HTTP commands. Only the original ad
        // endpoint (including Google's secure host alias) is a URL handoff.
        if (!request.matchesAdEndpoint(networkRequest.url())) return true
        if (networkRequest.requestType() != zzbx.GET || !networkRequest.content().isNullOrEmpty()) {
            request.finish(null, "ima_ad_request_requires_http_body")
            return true
        }
        request.finishUrl(networkRequest.url(), networkRequest.userAgent())
        return true
    }

    private class Owner(val sessionId: String, var request: PendingRequest?)

    private class PendingRequest(
        container: ViewGroup,
        private val adTagUrl: String,
        private val soundEnabled: Boolean,
        private val imaTclUrlPatch: Boolean,
        private var onResult: ((FinalUrl?, String?) -> Unit)?
    ) : RequestHandle {
        val finished = AtomicBoolean()
        @Volatile private var cancelled = false
        private val originalUri = Uri.parse(adTagUrl)
        private val appContext = container.context.applicationContext
        private val packageName = container.context.packageName
        private val television = container.context.packageManager.let {
            it.hasSystemFeature(PackageManager.FEATURE_TELEVISION) || it.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }
        private var parent: ViewGroup? = container
        private var host: FrameLayout? = null
        private var loader: AdsLoader? = null
        private val timeout = Runnable { finish(null, "ima_url_build_timeout") }

        fun start(timeoutMs: Long) {
            main.postDelayed(timeout, timeoutMs.coerceAtLeast(1L))
            try {
                val container = parent ?: return
                val imaHost = FrameLayout(container.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                host = imaHost
                container.addView(imaHost)
                val factory = ImaSdkFactory.getInstance()
                val display = ImaSdkFactory.createAdDisplayContainer(imaHost, ParameterPlayer())
                val adsLoader = factory.createAdsLoader(container.context, factory.createImaSdkSettings(), display)
                loader = adsLoader
                adsLoader.addAdErrorListener { event ->
                    finish(null, "ima_url_build_failed:${event.error}")
                }
                adsLoader.addAdsLoadedListener { event ->
                    event.adsManager?.destroy()
                    finish(null, "ima_response_reached_url_only_bridge")
                }
                val adsRequest = factory.createAdsRequest().apply {
                    setAdTagUrl(this@PendingRequest.adTagUrl)
                    setUserRequestContext(this@PendingRequest)
                    setAdWillAutoPlay(true)
                    setAdWillPlayMuted(!soundEnabled)
                    setVastLoadTimeout(timeoutMs.toFloat())
                    setContentProgressProvider { VideoProgressUpdate.VIDEO_TIME_NOT_READY }
                }
                adsLoader.requestAds(adsRequest)
            } catch (error: RuntimeException) {
                finish(null, "ima_url_build_failed:${error.message}")
            }
        }

        fun matchesAdEndpoint(url: String): Boolean {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" && uri.scheme != "http") return false
            if (uri.encodedPath != originalUri.encodedPath) return false
            if (uri.host.equals(originalUri.host, ignoreCase = true) && uri.port == originalUri.port) return true
            val googleHosts = setOf("pubads.g.doubleclick.net", "securepubads.g.doubleclick.net")
            return originalUri.host?.lowercase() in googleHosts && uri.host?.lowercase() in googleHosts
        }

        fun finishUrl(imaUrl: String, userAgent: String?) {
            val result = GoogleImaTclUrlPipeline.apply(adTagUrl, imaUrl, imaTclUrlPatch,
                !soundEnabled, television, packageName,
                fallbackUid = { GoogleTclUidStore.readOrCreate(appContext) })
            finish(FinalUrl(result.url, userAgent, imaUrl, result.beforeCommonPatchUrl, imaTclUrlPatch), null)
        }

        fun finish(result: FinalUrl?, error: String?) {
            if (!finished.compareAndSet(false, true)) return
            detach()
            // Finish the native message dispatch before destroying its WebView.
            main.post {
                val callback = onResult
                onResult = null
                dispose()
                if (!cancelled) {
                    result?.let {
                        Log.i("GoogleImaUrl", "IMA 完成 URL 拼接，urlHash=${AdPrivacySanitizer.shortHash(it.url)},urlLength=${it.url.length}")
                    }
                    callback?.invoke(result, error)
                }
            }
        }

        override fun cancel() {
            cancelled = true
            finished.set(true)
            detach()
            onResult = null
            if (Looper.myLooper() == Looper.getMainLooper()) dispose() else main.post { dispose() }
        }

        private fun detach() {
            synchronized(owners) {
                owners.values.forEach { if (it.request === this) it.request = null }
            }
        }

        private fun dispose() {
            main.removeCallbacks(timeout)
            val oldLoader = loader
            loader = null
            try {
                oldLoader?.release()
            } catch (error: RuntimeException) {
                Log.w("GoogleImaUrl", "释放 IMA 参数桥接失败", error)
            }
            host?.let { (it.parent as? ViewGroup)?.removeView(it) }
            host = null
            parent = null
        }

        private inner class ParameterPlayer : VideoAdPlayer {
            override fun getVolume(): Int = if (soundEnabled) 100 else 0
            override fun getAdProgress(): VideoProgressUpdate = VideoProgressUpdate.VIDEO_TIME_NOT_READY
            override fun addCallback(callback: VideoAdPlayer.VideoAdPlayerCallback) = Unit
            override fun removeCallback(callback: VideoAdPlayer.VideoAdPlayerCallback) = Unit
            override fun loadAd(mediaInfo: AdMediaInfo, podInfo: AdPodInfo) {
                finish(null, "ima_playback_reached_url_only_bridge")
            }
            override fun playAd(mediaInfo: AdMediaInfo) = Unit
            override fun pauseAd(mediaInfo: AdMediaInfo) = Unit
            override fun stopAd(mediaInfo: AdMediaInfo) = Unit
            override fun release() = Unit
        }
    }
}
