package com.smart.android.ad_app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.util.Locale
import java.util.WeakHashMap

@Keep
object Hq008XhsxAarRuntimeBridge {
    private const val HTTP_AGENT = "http.agent"
    private val documentStartScriptWebViews = WeakHashMap<WebView, Boolean>()
    private val imaUrlOnlyWebViews = WeakHashMap<WebView, Boolean>()
    private val imaUrlOnlyScopeDepth = ThreadLocal<Int>()

    private data class AppliedWebViewIdentity(
        val userAgent: String,
        val documentStartScriptInstalled: Boolean,
        val bootstrapScript: String
    )

    private fun effectiveUa(): String {
        return HaierUserAgentInstaller.ensureEffectiveForCurrentProcess().effectiveUa
    }

    private fun applyEffectiveWebViewIdentity(webView: WebView): AppliedWebViewIdentity {
        val effectiveUa = effectiveUa()
        val settings = webView.settings
        settings.userAgentString = effectiveUa
        applyEffectiveUserAgentMetadata(settings)
        val bootstrapScript = WebViewIdentityBootstrap.script(
            effectiveUa,
            getAndroidDeviceModel(),
            getAndroidVersionRelease(),
            getAndroidSdkInt()
        )
        val installed = installDocumentStartScript(webView, bootstrapScript)
        return AppliedWebViewIdentity(effectiveUa, installed, bootstrapScript)
    }

    private fun applyEffectiveUserAgentMetadata(settings: WebSettings) {
        val observed = AndroidXWebKitIdentityCompat.readUserAgentMetadata(settings) ?: return
        val corrected = correctAndroidClientHints(
            linkedMapOf(
                "platform" to observed.platform,
                "platformVersion" to observed.platformVersion,
                "model" to observed.model
            )
        ).orEmpty()
        val correctedModel = corrected["model"] as? String ?: observed.model
        val correctedPlatformVersion =
            corrected["platformVersion"] as? String ?: observed.platformVersion
        if (correctedModel == observed.model &&
            correctedPlatformVersion == observed.platformVersion) return
        AndroidXWebKitIdentityCompat.writeUserAgentMetadata(
            settings, observed, correctedModel, correctedPlatformVersion
        )
    }

    private fun installDocumentStartScript(webView: WebView, script: String): Boolean {
        synchronized(documentStartScriptWebViews) {
            if (documentStartScriptWebViews[webView] == true) return true
            if (!AndroidXWebKitIdentityCompat.addDocumentStartJavaScript(webView, script)) {
                return false
            }
            documentStartScriptWebViews[webView] = true
            return true
        }
    }

    @JvmStatic
    fun getEffectiveUserAgent(): String = effectiveUa()

    @JvmStatic
    fun getAndroidVersionRelease(): String = HaierBuildIdentityNormalizer.androidVersion()

    @JvmStatic
    fun getAndroidSdkInt(): Int = HaierBuildIdentityNormalizer.sdkInt()

    @JvmStatic
    fun getAndroidDeviceModel(): String = HaierBuildIdentityNormalizer.model()

    @JvmStatic
    fun getAndroidBuildId(): String = HaierBuildIdentityNormalizer.buildId()

    @JvmStatic
    fun getAndroidBuildFingerprint(): String = HaierBuildIdentityNormalizer.fingerprint()

    @JvmStatic
    fun getAndroidBrand(): String = HaierBuildIdentityNormalizer.brand()

    @JvmStatic
    fun getAndroidDevice(): String = HaierBuildIdentityNormalizer.device()

    @JvmStatic
    fun getAndroidManufacturer(): String = HaierBuildIdentityNormalizer.manufacturer()

    @JvmStatic
    fun getAndroidProduct(): String = HaierBuildIdentityNormalizer.product()

    @JvmStatic
    fun getAndroidHardware(): String = HaierBuildIdentityNormalizer.hardware()

    @JvmStatic
    fun correctAndroidClientHints(observed: Map<String, Any?>?): Map<String, Any?>? =
        WebViewClientHintsNormalizer.normalize(
            observed, getAndroidDeviceModel(), getAndroidVersionRelease(), getAndroidSdkInt()
        )

    /** Existing shipped-firmware corrections for reflective Android property reads. */
    @JvmStatic
    fun getAndroidSystemProperty(name: String?, defaultValue: String?): String? {
        return if (BuildFlavor.needsIdentityPatch() && HaierBuildIdentityNormalizer.isFingerprintProperty(name)) {
            // The caller's default is not a firmware source. Missing properties use native components.
            HaierBuildIdentityNormalizer.correctFingerprintProperty(
                name, HaierBuildIdentityNormalizer.systemProperty(name, null)
            )
        } else HaierBuildIdentityNormalizer.systemProperty(name, defaultValue)
    }

    @JvmStatic
    fun getSystemProperty(name: String?): String? {
        return if (name == HTTP_AGENT) effectiveUa() else name?.let(System::getProperty)
    }

    @JvmStatic
    fun getSystemPropertyWithDefault(name: String?, defaultValue: String?): String? {
        return when {
            name == null -> defaultValue
            name == HTTP_AGENT -> effectiveUa()
            else -> System.getProperty(name, defaultValue)
        }
    }

    @JvmStatic
    fun setWebViewUserAgent(settings: WebSettings, requestedUa: String?) {
        settings.userAgentString = effectiveUa()
        applyEffectiveUserAgentMetadata(settings)
    }

    @JvmStatic
    fun getDefaultWebViewUserAgent(context: Context?): String = effectiveUa()

    @JvmStatic
    fun getWebViewUserAgent(settings: WebSettings): String = effectiveUa()

    @JvmStatic
    fun prepareWebViewIdentity(webView: WebView) {
        applyEffectiveWebViewIdentity(webView)
    }

    @JvmStatic
    fun beginImaUrlOnlyWebViewScope() {
        imaUrlOnlyScopeDepth.set((imaUrlOnlyScopeDepth.get() ?: 0) + 1)
    }

    @JvmStatic
    fun endImaUrlOnlyWebViewScope() {
        val remaining = (imaUrlOnlyScopeDepth.get() ?: 1) - 1
        if (remaining > 0) imaUrlOnlyScopeDepth.set(remaining) else imaUrlOnlyScopeDepth.remove()
    }

    @JvmStatic
    fun setAuditedWebViewClient(webView: WebView, client: WebViewClient) {
        val urlOnly = synchronized(imaUrlOnlyWebViews) {
            if ((imaUrlOnlyScopeDepth.get() ?: 0) > 0) {
                imaUrlOnlyWebViews[webView] = true
            }
            imaUrlOnlyWebViews[webView] == true
        }
        webView.setWebViewClient(
            if (!urlOnly || client is ImaUrlOnlyWebViewClient) client
            else ImaUrlOnlyWebViewClient(client)
        )
    }

    @JvmStatic
    fun loadWebViewUrl(webView: WebView, url: String?) {
        applyEffectiveWebViewIdentity(webView)
        webView.loadUrl(url.orEmpty())
    }

    @JvmStatic
    fun loadWebViewUrlWithHeaders(
        webView: WebView,
        url: String?,
        headers: Map<String, String>?
    ) {
        val effectiveUa = applyEffectiveWebViewIdentity(webView).userAgent
        val finalHeaders = LinkedHashMap<String, String>()
        headers.orEmpty().forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) {
                finalHeaders[name] = value
            }
        }
        finalHeaders["User-Agent"] = effectiveUa
        webView.loadUrl(url.orEmpty(), finalHeaders)
    }

    @JvmStatic
    fun postWebViewUrl(webView: WebView, url: String?, body: ByteArray?) {
        applyEffectiveWebViewIdentity(webView)
        webView.postUrl(url.orEmpty(), body ?: ByteArray(0))
    }

    @JvmStatic
    fun loadWebViewData(
        webView: WebView,
        data: String?,
        mimeType: String?,
        encoding: String?
    ) {
        val identity = applyEffectiveWebViewIdentity(webView)
        val finalData = if (identity.documentStartScriptInstalled) data.orEmpty() else {
            WebViewIdentityBootstrap.prependToHtml(data.orEmpty(), identity.bootstrapScript)
        }
        webView.loadData(finalData, mimeType, encoding)
    }

    @JvmStatic
    fun loadWebViewDataWithBaseUrl(
        webView: WebView,
        baseUrl: String?,
        data: String?,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?
    ) {
        val identity = applyEffectiveWebViewIdentity(webView)
        val finalData = if (identity.documentStartScriptInstalled) data.orEmpty() else {
            WebViewIdentityBootstrap.prependToHtml(data.orEmpty(), identity.bootstrapScript)
        }
        webView.loadDataWithBaseURL(baseUrl, finalData, mimeType, encoding, historyUrl)
    }

    @JvmStatic
    fun openUrlConnection(url: URL): URLConnection {
        return url.openConnection().also {
            it.setRequestProperty("User-Agent", effectiveUa())
        }
    }

    @JvmStatic
    fun openUrlConnectionWithProxy(url: URL, proxy: Proxy): URLConnection {
        return url.openConnection(proxy).also {
            it.setRequestProperty("User-Agent", effectiveUa())
        }
    }

    @JvmStatic
    fun setUrlConnectionRequestProperty(
        connection: URLConnection,
        name: String?,
        value: String?
    ) {
        connection.setRequestProperty(
            name.orEmpty(),
            if (name.equals("User-Agent", ignoreCase = true)) effectiveUa() else value.orEmpty()
        )
    }

    @JvmStatic
    fun addUrlConnectionRequestProperty(
        connection: URLConnection,
        name: String?,
        value: String?
    ) {
        connection.addRequestProperty(
            name.orEmpty(),
            if (name.equals("User-Agent", ignoreCase = true)) effectiveUa() else value.orEmpty()
        )
    }

    @JvmStatic
    fun setUrlConnectionRequestMethod(connection: HttpURLConnection, method: String?) {
        connection.requestMethod = method.orEmpty()
    }

    @JvmStatic
    fun newOkHttpCall(client: OkHttpClient, request: Request): Call {
        val normalized = request.newBuilder()
            .header("User-Agent", effectiveUa())
            .build()
        return client.newCall(normalized)
    }

    @JvmStatic
    fun shouldRenderSxkVideoFrame(renderer: Any, presentationTimeUs: Long): Boolean {
        return Hq008VideoFrameThrottle.shouldRender(
            flavor = BuildConfig.FLAVOR,
            renderer = renderer,
            presentationTimeUs = presentationTimeUs
        )
    }

    @JvmStatic
    fun shouldApplySxkPlaybackPatch(): Boolean {
        return isSxkPlaybackPatchFlavor(BuildConfig.FLAVOR)
    }

    internal fun isSxkPlaybackPatchFlavor(flavor: String): Boolean {
        return flavor == "ad_ytx01_sxk"
    }

    @JvmStatic
    fun normalizeHeaderValue(name: String?, value: String?): String? {
        return if (name.equals("User-Agent", ignoreCase = true)) effectiveUa() else value
    }

    @JvmStatic
    fun getUrlConnectionOutputStream(connection: URLConnection): OutputStream =
        connection.getOutputStream()

    @JvmStatic
    fun getUrlConnectionInputStream(connection: URLConnection): InputStream =
        connection.getInputStream()

    @JvmStatic
    fun getUrlConnectionResponseCode(connection: HttpURLConnection): Int =
        connection.responseCode

    @JvmStatic
    fun getUrlConnectionErrorStream(connection: HttpURLConnection): InputStream? =
        connection.errorStream

    @JvmStatic
    fun connectUrlConnection(connection: URLConnection) {
        connection.connect()
    }

    @JvmStatic
    fun disconnectUrlConnection(connection: HttpURLConnection) {
        connection.disconnect()
    }

    private fun isAllowedImaUrlOnlyRequest(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return true
        if (scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val path = uri.encodedPath.orEmpty()
        return when (host) {
            "imasdk.googleapis.com" -> {
                path == "/native/sdkloader/native_sdk_v3.html" ||
                    (path.startsWith("/native/core/") &&
                        path.substringAfterLast('/').startsWith("native_bridge__") &&
                        path.endsWith(".js"))
            }
            else -> false
        }
    }

    private fun blockedWebResourceResponse(url: String): WebResourceResponse {
        val path = Uri.parse(url).encodedPath.orEmpty().lowercase(Locale.US)
        val mimeType = when {
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            else -> "text/plain"
        }
        return WebResourceResponse(
            mimeType,
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private class ImaUrlOnlyWebViewClient(
        private val delegate: WebViewClient
    ) : WebViewClient() {
        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (!Hq008XhsxAarRuntimeBridge.isAllowedImaUrlOnlyRequest(url)) return true
            return delegate.shouldOverrideUrlLoading(view, url)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            if (!Hq008XhsxAarRuntimeBridge.isAllowedImaUrlOnlyRequest(request.url.toString())) {
                return true
            }
            return delegate.shouldOverrideUrlLoading(view, request)
        }

        @Suppress("DEPRECATION")
        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
            if (!Hq008XhsxAarRuntimeBridge.isAllowedImaUrlOnlyRequest(url)) {
                return Hq008XhsxAarRuntimeBridge.blockedWebResourceResponse(url)
            }
            return delegate.shouldInterceptRequest(view, url)
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (!request.method.equals("GET", ignoreCase = true) ||
                !Hq008XhsxAarRuntimeBridge.isAllowedImaUrlOnlyRequest(request.url.toString())) {
                return Hq008XhsxAarRuntimeBridge.blockedWebResourceResponse(
                    request.url.toString()
                )
            }
            return delegate.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            delegate.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            delegate.onPageFinished(view, url)
        }

        override fun onLoadResource(view: WebView, url: String) {
            delegate.onLoadResource(view, url)
        }

        @Suppress("DEPRECATION")
        override fun onTooManyRedirects(view: WebView, cancelMsg: Message, continueMsg: Message) {
            delegate.onTooManyRedirects(view, cancelMsg, continueMsg)
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String,
            failingUrl: String
        ) {
            delegate.onReceivedError(view, errorCode, description, failingUrl)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            delegate.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            delegate.onReceivedHttpError(view, request, errorResponse)
        }

        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
            delegate.onFormResubmission(view, dontResend, resend)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            delegate.doUpdateVisitedHistory(view, url, isReload)
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            delegate.onReceivedSslError(view, handler, error)
        }

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
            delegate.onReceivedClientCertRequest(view, request)
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String
        ) {
            delegate.onReceivedHttpAuthRequest(view, handler, host, realm)
        }

        override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean {
            return delegate.shouldOverrideKeyEvent(view, event)
        }

        override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
            delegate.onUnhandledKeyEvent(view, event)
        }

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
            delegate.onScaleChanged(view, oldScale, newScale)
        }

        override fun onReceivedLoginRequest(
            view: WebView,
            realm: String,
            account: String?,
            args: String
        ) {
            delegate.onReceivedLoginRequest(view, realm, account, args)
        }
    }

    private object AndroidXWebKitIdentityCompat {
        private const val WEB_VIEW_FEATURE = "androidx.webkit.WebViewFeature"
        private const val WEB_VIEW_COMPAT = "androidx.webkit.WebViewCompat"
        private const val WEB_SETTINGS_COMPAT = "androidx.webkit.WebSettingsCompat"
        private const val USER_AGENT_METADATA = "androidx.webkit.UserAgentMetadata"
        private const val USER_AGENT_METADATA_BUILDER =
            "androidx.webkit.UserAgentMetadata\$Builder"

        data class Metadata(
            val raw: Any,
            val platform: String?,
            val platformVersion: String?,
            val model: String?
        )

        fun readUserAgentMetadata(settings: WebSettings): Metadata? {
            if (!isFeatureSupported("USER_AGENT_METADATA")) return null
            val compatClass = Class.forName(WEB_SETTINGS_COMPAT)
            val metadata = compatClass
                .getMethod("getUserAgentMetadata", WebSettings::class.java)
                .invoke(null, settings) ?: return null
            val metadataClass = Class.forName(USER_AGENT_METADATA)
            return Metadata(
                raw = metadata,
                platform = metadataClass.getMethod("getPlatform").invoke(metadata) as? String,
                platformVersion = metadataClass.getMethod("getPlatformVersion")
                    .invoke(metadata) as? String,
                model = metadataClass.getMethod("getModel").invoke(metadata) as? String
            )
        }

        fun writeUserAgentMetadata(
            settings: WebSettings,
            observed: Metadata,
            model: String?,
            platformVersion: String?
        ) {
            val metadataClass = Class.forName(USER_AGENT_METADATA)
            val builderClass = Class.forName(USER_AGENT_METADATA_BUILDER)
            val builder = builderClass.getConstructor(metadataClass).newInstance(observed.raw)
            builderClass.getMethod("setModel", String::class.java).invoke(builder, model)
            builderClass.getMethod("setPlatformVersion", String::class.java)
                .invoke(builder, platformVersion)
            val metadata = builderClass.getMethod("build").invoke(builder)
            Class.forName(WEB_SETTINGS_COMPAT)
                .getMethod("setUserAgentMetadata", WebSettings::class.java, metadataClass)
                .invoke(null, settings, metadata)
        }

        fun addDocumentStartJavaScript(webView: WebView, script: String): Boolean {
            if (!isFeatureSupported("DOCUMENT_START_SCRIPT")) return false
            Class.forName(WEB_VIEW_COMPAT)
                .getMethod(
                    "addDocumentStartJavaScript",
                    WebView::class.java,
                    String::class.java,
                    Set::class.java
                )
                .invoke(null, webView, script, setOf("*"))
            return true
        }

        private fun isFeatureSupported(fieldName: String): Boolean {
            val featureClass = try {
                Class.forName(WEB_VIEW_FEATURE)
            } catch (_: ClassNotFoundException) {
                return false
            }
            val featureName = try {
                featureClass.getField(fieldName).get(null) as String
            } catch (_: NoSuchFieldException) {
                return false
            }
            return featureClass.getMethod("isFeatureSupported", String::class.java)
                .invoke(null, featureName) == true
        }
    }
}
