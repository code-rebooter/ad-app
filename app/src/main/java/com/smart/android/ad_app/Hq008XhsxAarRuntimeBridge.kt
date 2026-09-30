package com.smart.android.ad_app

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection

@Keep
object Hq008XhsxAarRuntimeBridge {
    private const val HTTP_AGENT = "http.agent"

    private fun effectiveUa(): String {
        return HaierUserAgentInstaller.ensureEffectiveForCurrentProcess().effectiveUa
    }

    @JvmStatic
    fun getAndroidVersionRelease(): String = HaierBuildIdentityNormalizer.androidVersion()

    @JvmStatic
    fun getAndroidSdkInt(): Int = HaierBuildIdentityNormalizer.sdkInt()

    @JvmStatic
    fun getAndroidDeviceModel(): String = HaierDeviceModelNormalizer.FIXED_MODEL

    @JvmStatic
    fun getAndroidBuildId(): String = HaierBuildIdentityNormalizer.buildId()

    @JvmStatic
    fun getAndroidBrand(): String = HaierBuildIdentityNormalizer.brand()

    @JvmStatic
    fun getAndroidDevice(): String = HaierBuildIdentityNormalizer.device()

    @JvmStatic
    fun getAndroidManufacturer(): String = HaierBuildIdentityNormalizer.manufacturer()

    @JvmStatic
    fun getAndroidProduct(): String = HaierBuildIdentityNormalizer.product()

    @JvmStatic
    fun getSystemProperty(name: String?): String? {
        return if (name == HTTP_AGENT) effectiveUa() else name?.let(System::getProperty)
    }

    @JvmStatic
    fun setWebViewUserAgent(settings: WebSettings, requestedUa: String?) {
        settings.userAgentString = effectiveUa()
    }

    @JvmStatic
    fun getDefaultWebViewUserAgent(context: Context?): String = effectiveUa()

    @JvmStatic
    fun getWebViewUserAgent(settings: WebSettings): String = effectiveUa()

    @JvmStatic
    fun setAuditedWebViewClient(webView: WebView, client: WebViewClient) {
        webView.setWebViewClient(client)
    }

    @JvmStatic
    fun loadWebViewUrl(webView: WebView, url: String?) {
        webView.loadUrl(url.orEmpty())
    }

    @JvmStatic
    fun loadWebViewUrlWithHeaders(
        webView: WebView,
        url: String?,
        headers: Map<String, String>?
    ) {
        val finalHeaders = LinkedHashMap(headers.orEmpty())
        finalHeaders["User-Agent"] = effectiveUa()
        webView.loadUrl(url.orEmpty(), finalHeaders)
    }

    @JvmStatic
    fun postWebViewUrl(webView: WebView, url: String?, body: ByteArray?) {
        webView.settings.userAgentString = effectiveUa()
        webView.postUrl(url.orEmpty(), body ?: ByteArray(0))
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
}
