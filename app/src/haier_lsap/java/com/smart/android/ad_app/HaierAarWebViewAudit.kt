package com.smart.android.ad_app

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.UUID

internal object HaierAarWebViewAudit {
    fun setClient(webView: WebView, client: WebViewClient?) {
        forceUa(webView)
        webView.webViewClient = when (client) {
            null -> WebViewClient()
            is AuditingWebViewClient -> client
            else -> AuditingWebViewClient(client)
        }
    }

    fun loadUrl(webView: WebView, url: String?) {
        forceUa(webView)
        val normalizedUrl = normalizeAarUrlUa(url.orEmpty())
        record(
            source = "webview_load_url",
            method = "GET",
            url = normalizedUrl,
            headers = mapOf("User-Agent" to HaierAarRuntimeBridge.currentEffectiveUa())
        )
        webView.loadUrl(normalizedUrl)
    }

    fun loadUrl(webView: WebView, url: String?, headers: Map<String, String>?) {
        forceUa(webView)
        val normalizedUrl = normalizeAarUrlUa(url.orEmpty())
        val finalHeaders = LinkedHashMap<String, String>()
        headers.orEmpty().forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) finalHeaders[name] = value
        }
        finalHeaders["User-Agent"] = HaierAarRuntimeBridge.currentEffectiveUa()
        record(
            source = "webview_load_url_headers",
            method = "GET",
            url = normalizedUrl,
            headers = finalHeaders
        )
        webView.loadUrl(normalizedUrl, finalHeaders)
    }

    fun postUrl(webView: WebView, url: String?, body: ByteArray?) {
        forceUa(webView)
        val normalizedUrl = normalizeAarUrlUa(url.orEmpty())
        val bytes = body ?: ByteArray(0)
        val normalizedBody = normalizeAarPayloadUa(
            bytes.toString(Charsets.UTF_8),
            "application/x-www-form-urlencoded"
        ).toByteArray(Charsets.UTF_8)
        record(
            source = "webview_post_url",
            method = "POST",
            url = normalizedUrl,
            headers = mapOf(
                "User-Agent" to HaierAarRuntimeBridge.currentEffectiveUa(),
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            capturedBody = captureAarBytes(normalizedBody, "application/x-www-form-urlencoded")
        )
        webView.postUrl(normalizedUrl, normalizedBody)
    }

    private fun forceUa(webView: WebView) {
        runCatching {
            webView.settings.userAgentString = HaierAarRuntimeBridge.currentEffectiveUa()
        }
    }

    private fun record(
        source: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        capturedBody: HaierCapturedBody = unavailableAarBody("webview_body_unavailable")
    ) {
        val headersRaw = headers.entries.joinToString("\n") { (name, value) -> "$name: $value" }
        HaierAarAuditUploader.enqueue(
            HaierAarAuditEvent(
                eventType = "AAR_WEBVIEW_HTTP_AUDIT",
                sourceStack = source,
                method = method,
                urlRaw = url,
                headersRaw = headersRaw,
                bodyRaw = capturedBody.raw,
                contentType = headers.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    .orEmpty(),
                bodyEncoding = capturedBody.encoding,
                bodyLength = capturedBody.length,
                bodySha256 = capturedBody.sha256,
                coverage = "webview_visible_request",
                auditId = UUID.randomUUID().toString(),
                extra = mapOf(
                    "body_unavailable_reason" to capturedBody.unavailableReason,
                    "header_ua_final" to HaierAarRuntimeBridge.currentEffectiveUa(),
                    "is_network_url" to (url.startsWith("http://") || url.startsWith("https://"))
                )
            )
        )
    }

    private class AuditingWebViewClient(
        private val delegate: WebViewClient
    ) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            record(
                source = "webview_should_override_legacy",
                method = "GET",
                url = url,
                headers = mapOf("User-Agent" to HaierAarRuntimeBridge.currentEffectiveUa())
            )
            return delegate.shouldOverrideUrlLoading(view, url)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            recordRequest("webview_should_override", request)
            return delegate.shouldOverrideUrlLoading(view, request)
        }

        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
            record(
                source = "webview_intercept_legacy",
                method = "GET",
                url = url,
                headers = mapOf("User-Agent" to HaierAarRuntimeBridge.currentEffectiveUa())
            )
            return delegate.shouldInterceptRequest(view, url)
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            recordRequest("webview_intercept", request)
            return delegate.shouldInterceptRequest(view, request)
        }

        private fun recordRequest(source: String, request: WebResourceRequest) {
            val headers = LinkedHashMap<String, String>()
            request.requestHeaders.orEmpty().forEach { (name, value) -> headers[name] = value }
            headers["User-Agent"] = HaierAarRuntimeBridge.currentEffectiveUa()
            record(
                source = source,
                method = request.method.orEmpty(),
                url = request.url?.toString().orEmpty(),
                headers = headers,
                capturedBody = unavailableAarBody("webview_body_unavailable")
            )
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
}
