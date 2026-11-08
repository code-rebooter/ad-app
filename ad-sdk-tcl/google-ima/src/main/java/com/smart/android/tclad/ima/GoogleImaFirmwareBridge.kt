package com.smart.android.tclad.ima

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
object GoogleImaFirmwareBridge {
    private const val HTTP_AGENT = "http.agent"
    private val documentStartScriptWebViews = WeakHashMap<WebView, Boolean>()

    private data class AppliedWebViewIdentity(
        val userAgent: String,
        val documentStartScriptInstalled: Boolean,
        val bootstrapScript: String
    )

    private fun effectiveUa(): String {
        val result = HaierUserAgentNormalizer.normalize(System.getProperty(HTTP_AGENT),
            getAndroidSdkInt(), HaierBuildIdentityNormalizer.userAgentIdentity())
        if (result.changed) System.setProperty(HTTP_AGENT, result.effectiveUa)
        return result.effectiveUa
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
        return if (HaierBuildIdentityNormalizer.isFingerprintProperty(name)) {
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
    fun setAuditedWebViewClient(webView: WebView, client: WebViewClient) {
        webView.webViewClient = client
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
