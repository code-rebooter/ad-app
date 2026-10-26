package com.smart.android.ad_app.google

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.Hq008XhsxAarRuntimeBridge
import io.codex.imarepro.AndroidNativeIdentityOwner
import io.codex.imarepro.FirmwareInputCorrections
import io.codex.imarepro.facade.AndroidIndependentUrlFacade
import io.codex.imarepro.independent.android.AndroidIndependentMsOwner
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one fresh local browser/session per request and shares only the process owner. */
internal object GoogleIndependentUrlBridge : GoogleAdUrlBridge.Generator {
    override val engine = "independent"
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "IndependentGoogleUrl").apply { isDaemon = true }
    }
    private const val LOCAL_ORIGIN = "https://localhost.invalid/ima-independent/"

    override fun generate(
        container: ViewGroup, adTagUrl: String, soundEnabled: Boolean, timeoutMs: Long,
        onResult: (GoogleAdUrlBridge.GeneratedUrl?, String?) -> Unit
    ): GoogleAdUrlBridge.RequestHandle = PendingRequest(container, adTagUrl, soundEnabled, onResult).also {
        it.start(timeoutMs)
    }

    private fun configuration(): AndroidIndependentUrlFacade.Configuration {
        val identity = object : AndroidIndependentUrlFacade.DeviceIdentity {
            override fun sdkInt() = Hq008XhsxAarRuntimeBridge.getAndroidSdkInt()
            override fun release(): String = Hq008XhsxAarRuntimeBridge.getAndroidVersionRelease()
            override fun provenance() = "Host shared firmware SDK/release policy"
        }
        val loader = object : AndroidNativeIdentityOwner.LoaderPrivacy {
            // Same initial loader flag as the retained createAdsLoader/default-settings path.
            // The facade separately reads actual IAB consent preferences for the idless gate.
            override fun actualGdprLoader() = false
            override fun provenance() = "Pinned IMA 3.35.1 default native loader isGdprLoader=false"
        }
        return AndroidIndependentUrlFacade.Configuration(
            identity,
            AndroidIndependentMsOwner.Configuration.pinnedGoogleUrlOnly(
                System.getProperty("os.arch"), "Actual host System.getProperty(os.arch)"
            ),
            loader, null, false, false, false, true, 5_000L, 500
        )
    }

    private class PendingRequest(
        container: ViewGroup,
        private val adTagUrl: String,
        private val soundEnabled: Boolean,
        private var callback: ((GoogleAdUrlBridge.GeneratedUrl?, String?) -> Unit)?
    ) : GoogleAdUrlBridge.RequestHandle {
        private val finished = AtomicBoolean()
        private var parent: ViewGroup? = container
        private var host: FrameLayout? = null
        private var browser: WebView? = null
        private var workScheduled = false // main-thread state; worker owns session closure.
        @Volatile private var worker: Thread? = null
        private val timeout = Runnable { complete(null, "independent_url_build_timeout", interrupt = true) }

        fun start(timeoutMs: Long) {
            main.post {
                if (finished.get()) return@post
                main.postDelayed(timeout, timeoutMs.coerceAtLeast(1L))
                try {
                    val target = parent ?: return@post
                    val localHost = FrameLayout(target.context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    host = localHost
                    target.addView(localHost)
                    val localBrowser = WebView(target.context)
                    browser = localBrowser
                    localHost.addView(localBrowser, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    localBrowser.setBackgroundColor(Color.TRANSPARENT)
                    localBrowser.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkLoads = true
                        if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
                    }
                    FirmwareInputCorrections.prepareBrowser(localBrowser)
                    localBrowser.webViewClient = object : WebViewClient() {
                        private var loaded = false
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        override fun onPageFinished(view: WebView, url: String) {
                            if (loaded || finished.get()) return
                            loaded = true
                            generateOnWorker(target.context, localHost, localBrowser)
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame && !loaded)
                                complete(null, "independent_local_page_failed:${error.errorCode}")
                        }
                    }
                    localBrowser.loadDataWithBaseURL(LOCAL_ORIGIN,
                        "<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>",
                        "text/html", "UTF-8", null)
                } catch (error: Exception) {
                    complete(null, "independent_local_browser_failed:${error.message}")
                } catch (error: LinkageError) {
                    complete(null, "independent_runtime_unavailable:${error.message}")
                }
            }
        }

        private fun generateOnWorker(context: Context, container: ViewGroup, localBrowser: WebView) {
            if (finished.get()) return
            workScheduled = true
            try {
                executor.execute {
                    worker = Thread.currentThread()
                    var session: AndroidIndependentUrlFacade? = null
                    var result: GoogleAdUrlBridge.GeneratedUrl? = null
                    var failure: String? = null
                    try {
                        if (!finished.get()) {
                            val process = AndroidIndependentUrlFacade.openProcessOwner(
                                context.applicationContext, configuration())
                            if (!finished.get()) {
                                session = process.newPendingRequest(context, container, localBrowser)
                                if (!finished.get()) {
                                    val input = AndroidIndependentUrlFacade.Request().apply {
                                        adTagUrl = this@PendingRequest.adTagUrl
                                        adWillAutoPlay = true
                                        adWillPlayMuted = !soundEnabled
                                    }
                                    val generated = session.generate(input)
                                    val signal = generated.report["independentSignal"] as? Map<*, *>
                                    result = GoogleAdUrlBridge.GeneratedUrl(generated.url,
                                        FirmwareInputCorrections.userAgent(),
                                        mapOf("signalBranch" to signal?.get("branch").toString()))
                                }
                            }
                        }
                    } catch (error: Exception) {
                        failure = "independent_url_build_failed:${error.javaClass.simpleName}:${error.message}"
                        Log.w("IndependentGoogleUrl", "独立 URL 生成失败", error)
                    } catch (error: LinkageError) {
                        failure = "independent_runtime_unavailable:${error.javaClass.simpleName}:${error.message}"
                        Log.e("IndependentGoogleUrl", "独立 URL 运行时不可用", error)
                    } finally {
                        try { session?.close() }
                        catch (error: Exception) { Log.w("IndependentGoogleUrl", "释放独立 URL 会话失败", error) }
                        worker = null
                        main.post {
                            workScheduled = false
                            complete(result, failure ?: if (result == null) "independent_request_cancelled" else null)
                            disposeBrowser()
                        }
                    }
                }
            } catch (error: RuntimeException) {
                workScheduled = false
                complete(null, "independent_worker_failed:${error.message}")
            }
        }

        /** UI cleanup waits for worker session closure; cancel never blocks the UI on generate(). */
        private fun complete(result: GoogleAdUrlBridge.GeneratedUrl?, error: String?, interrupt: Boolean = false) {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            if (interrupt) worker?.interrupt()
            if (!workScheduled) disposeBrowser()
            val notify = callback
            callback = null
            notify?.invoke(result, error)
        }

        override fun cancel() {
            finished.set(true)
            worker?.interrupt()
            main.post {
                main.removeCallbacks(timeout)
                callback = null
                if (!workScheduled) disposeBrowser()
            }
        }

        private fun disposeBrowser() {
            val oldBrowser = browser
            browser = null
            oldBrowser?.let {
                try {
                    it.stopLoading()
                    (it.parent as? ViewGroup)?.removeView(it)
                    it.webViewClient = WebViewClient()
                    it.destroy()
                } catch (error: RuntimeException) { Log.w("IndependentGoogleUrl", "释放本地 WebView 失败", error) }
            }
            host?.let { (it.parent as? ViewGroup)?.removeView(it) }
            host = null
            parent = null
        }
    }
}
