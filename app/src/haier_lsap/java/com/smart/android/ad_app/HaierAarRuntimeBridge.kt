package com.smart.android.ad_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.webkit.WebSettings
import androidx.annotation.Keep
import com.spctv.utils.okhttp3.w
import com.spctv.utils.okhttp3.x
import com.spctv.utils.okhttp3.y
import com.spctv.utils.okhttp3.s
import com.spctv.utils.okio.c
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import titan.sdk.android.TitanSDK
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Keep
object HaierAarRuntimeBridge {
    const val PATCH_VERSION = "lsap-full-network-audit-2"
    private const val TAG = "HaierAarBridge"
    private const val HTTP_AGENT = "http.agent"
    private const val LSAP_PREFS = "lsapdata"
    private const val LSAD_WEB_UA = "LSADWEBUA"
    private const val AUDIT_REPORT_PATH = "/api/v2/ad/report"

    @Volatile
    private var applicationContext: Context? = null
    private val writingCachedUa = AtomicBoolean(false)
    private var preferences: SharedPreferences? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key != LSAD_WEB_UA || writingCachedUa.get()) return@OnSharedPreferenceChangeListener
            val cached = prefs.getString(LSAD_WEB_UA, "").orEmpty()
            val effective = currentEffectiveUa()
            if (cached != effective) {
                auditUaEvent(
                    eventType = "AAR_UA_DRIFT",
                    source = "shared_preferences_listener",
                    observed = cached,
                    effective = effective
                )
                writeCachedUa(effective)
            }
        }

    @JvmStatic
    fun initialize(context: Context) {
        val app = context.applicationContext
        applicationContext = app
        val prefs = app.getSharedPreferences(LSAP_PREFS, Context.MODE_PRIVATE)
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = prefs
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        enforceNow("initialize")
    }

    @JvmStatic
    fun enforceNow(source: String): String {
        val check = HaierUserAgentInstaller.ensureEffectiveForCurrentProcess()
        val cached = preferences?.getString(LSAD_WEB_UA, "").orEmpty()
        if (cached != check.effectiveUa) {
            auditUaEvent(
                eventType = "AAR_UA_REPAIRED",
                source = source,
                observed = cached,
                effective = check.effectiveUa
            )
            writeCachedUa(check.effectiveUa)
        }
        if (check.repaired) {
            auditUaEvent(
                eventType = "AAR_UA_REPAIRED",
                source = "$source-system",
                observed = check.observedUa,
                effective = check.effectiveUa
            )
        }
        return check.effectiveUa
    }

    @JvmStatic
    fun currentEffectiveUa(): String {
        return HaierUserAgentInstaller.ensureEffectiveForCurrentProcess().effectiveUa
    }

    @JvmStatic
    fun getSystemProperty(name: String?): String? {
        return if (name == HTTP_AGENT) currentEffectiveUa() else name?.let(System::getProperty)
    }

    @JvmStatic
    fun enforceResolvedUa(resolvedUa: String?, context: Context?): String {
        if (applicationContext == null && context != null) initialize(context)
        val effective = enforceNow("aar_resolver")
        if (!resolvedUa.isNullOrEmpty() && resolvedUa != effective) {
            auditUaEvent("AAR_UA_DRIFT", "aar_resolver", resolvedUa, effective)
        }
        return effective
    }

    @JvmStatic
    fun normalizeStoredValue(key: String?, value: String?): String? {
        if (key != LSAD_WEB_UA) return value
        val effective = currentEffectiveUa()
        if (!value.isNullOrEmpty() && value != effective) {
            auditUaEvent("AAR_UA_DRIFT", "aar_store", value, effective)
        }
        return effective
    }

    @JvmStatic
    fun normalizeHeaderValue(name: String?, value: String?): String? {
        return if (name.equals("User-Agent", ignoreCase = true)) currentEffectiveUa() else value
    }

    @JvmStatic
    fun setWebViewUserAgent(settings: WebSettings, requestedUa: String?) {
        val effective = currentEffectiveUa()
        if (!requestedUa.isNullOrEmpty() && requestedUa != effective) {
            auditUaEvent("AAR_UA_DRIFT", "webview", requestedUa, effective)
        }
        settings.userAgentString = effective
    }

    @JvmStatic
    fun getDefaultWebViewUserAgent(context: Context?): String {
        if (applicationContext == null && context != null) initialize(context)
        return currentEffectiveUa()
    }

    @JvmStatic
    fun getWebViewUserAgent(settings: WebSettings): String {
        val observed = runCatching { settings.userAgentString }.getOrDefault("")
        val effective = currentEffectiveUa()
        if (observed.isNotEmpty() && observed != effective) {
            auditUaEvent("AAR_UA_DRIFT", "webview_getter", observed, effective)
        }
        return effective
    }

    @JvmStatic
    fun setAuditedWebViewClient(
        webView: android.webkit.WebView,
        client: android.webkit.WebViewClient?
    ) {
        HaierAarWebViewAudit.setClient(webView, client)
    }

    @JvmStatic
    fun loadWebViewUrl(webView: android.webkit.WebView, url: String?) {
        HaierAarWebViewAudit.loadUrl(webView, url)
    }

    @JvmStatic
    fun loadWebViewUrlWithHeaders(
        webView: android.webkit.WebView,
        url: String?,
        headers: Map<String, String>?
    ) {
        HaierAarWebViewAudit.loadUrl(webView, url, headers)
    }

    @JvmStatic
    fun postWebViewUrl(webView: android.webkit.WebView, url: String?, body: ByteArray?) {
        HaierAarWebViewAudit.postUrl(webView, url, body)
    }

    @JvmStatic
    fun openUrlConnection(url: URL): URLConnection {
        return HaierAarUrlConnectionAudit.open(url)
    }

    @JvmStatic
    fun openUrlConnectionWithProxy(url: URL, proxy: Proxy): URLConnection {
        return HaierAarUrlConnectionAudit.open(url, proxy)
    }

    @JvmStatic
    fun setUrlConnectionRequestProperty(
        connection: URLConnection,
        name: String?,
        value: String?
    ) {
        HaierAarUrlConnectionAudit.setRequestProperty(connection, name, value)
    }

    @JvmStatic
    fun addUrlConnectionRequestProperty(
        connection: URLConnection,
        name: String?,
        value: String?
    ) {
        HaierAarUrlConnectionAudit.addRequestProperty(connection, name, value)
    }

    @JvmStatic
    fun setUrlConnectionRequestMethod(connection: HttpURLConnection, method: String?) {
        HaierAarUrlConnectionAudit.setRequestMethod(connection, method)
    }

    @JvmStatic
    fun connectUrlConnection(connection: URLConnection) {
        HaierAarUrlConnectionAudit.connect(connection)
    }

    @JvmStatic
    fun getUrlConnectionOutputStream(connection: URLConnection): OutputStream {
        return HaierAarUrlConnectionAudit.getOutputStream(connection)
    }

    @JvmStatic
    fun getUrlConnectionInputStream(connection: URLConnection): InputStream {
        return HaierAarUrlConnectionAudit.getInputStream(connection)
    }

    @JvmStatic
    fun getUrlConnectionResponseCode(connection: HttpURLConnection): Int {
        return HaierAarUrlConnectionAudit.getResponseCode(connection)
    }

    @JvmStatic
    fun getUrlConnectionErrorStream(connection: HttpURLConnection): InputStream? {
        return HaierAarUrlConnectionAudit.getErrorStream(connection)
    }

    @JvmStatic
    fun disconnectUrlConnection(connection: HttpURLConnection) {
        HaierAarUrlConnectionAudit.disconnect(connection)
    }

    @JvmStatic
    fun newOkHttpCall(client: OkHttpClient, request: Request): Call {
        return HaierAarOkHttpAudit.newCall(client, request)
    }

    @JvmStatic
    fun sendDatagram(socket: DatagramSocket, packet: DatagramPacket) {
        val startedAtNs = System.nanoTime()
        val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        val capturedBody = captureAarBytes(bytes, "application/octet-stream")
        val address = packet.address?.hostAddress.orEmpty()
        val auditId = UUID.randomUUID().toString()
        HaierAarAuditUploader.enqueue(
            HaierAarAuditEvent(
                eventType = "AAR_UDP_AUDIT",
                sourceStack = "java_datagram_socket",
                method = "UDP_SEND",
                urlRaw = "udp://$address:${packet.port}",
                headersRaw = "",
                bodyRaw = capturedBody.raw,
                contentType = "application/octet-stream",
                bodyEncoding = capturedBody.encoding,
                bodyLength = capturedBody.length,
                bodySha256 = capturedBody.sha256,
                coverage = "java_udp_send",
                auditId = auditId,
                extra = mapOf(
                    "remote_address" to address,
                    "remote_port" to packet.port,
                    "local_port" to socket.localPort
                )
            )
        )
        try {
            socket.send(packet)
        } catch (error: Throwable) {
            HaierAarAuditUploader.enqueue(
                HaierAarAuditEvent(
                    eventType = "AAR_UDP_ERROR",
                    sourceStack = "java_datagram_socket",
                    method = "UDP_SEND",
                    urlRaw = "udp://$address:${packet.port}",
                    headersRaw = "",
                    bodyRaw = capturedBody.raw,
                    contentType = "application/octet-stream",
                    bodyEncoding = capturedBody.encoding,
                    bodyLength = capturedBody.length,
                    bodySha256 = capturedBody.sha256,
                    errorMessage = "${error.javaClass.name}: ${error.message.orEmpty()}",
                    durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs),
                    coverage = "java_udp_send",
                    auditId = auditId
                ),
                critical = true
            )
            throw error
        }
    }

    @JvmStatic
    fun rewriteRtbBody(body: String?): String? {
        if (body.isNullOrBlank()) return body
        return runCatching {
            val root = JSONObject(body)
            val device = root.optJSONObject("device") ?: return@runCatching body
            val original = device.optString("ua")
            val effective = currentEffectiveUa()
            device.put("ua", effective)
            if (device.has("model")) {
                val originalModel = device.optString("model")
                if (HaierDeviceModelNormalizer.isGeneric(originalModel)) {
                    device.put("model", HaierDeviceModelNormalizer.normalize(originalModel))
                }
            }
            if (original != effective) {
                auditUaEvent("AAR_UA_REPAIRED", "rtb_device_ua", original, effective)
            }
            root.toString()
        }.getOrElse {
            Log.w(TAG, "RTB UA重写失败，将保留原请求体", it)
            body
        }
    }

    @JvmStatic
    fun executeShadedRequest(chain: s.a, request: w): y {
        val auditId = UUID.randomUUID().toString()
        val startedAtNs = System.nanoTime()
        val patched = runCatching {
            val url = request.g().toString()
            val normalizedUrl = normalizeAarUrlUa(url)
            if (url.contains(AUDIT_REPORT_PATH)) return chain.a(request)
            val originalBody = readShadedBody(request)
            val rtbRewrittenBody = if (url.contains("/rtb/bid")) {
                rewriteRtbBody(originalBody).orEmpty()
            } else {
                originalBody
            }
            val body = request.a()
            val rewrittenBody = normalizeAarPayloadUa(
                rtbRewrittenBody,
                body?.b()?.toString()
            )
            val builder = request.f()
            if (normalizedUrl != url) builder.b(normalizedUrl)
            builder.b("User-Agent", currentEffectiveUa())
            if (body != null && rewrittenBody != originalBody) {
                builder.a(request.e(), x.a(body.b(), rewrittenBody))
            }
            val patched = builder.a()
            val bodyBytes = readShadedBodyBytes(patched)
            val capturedBody = captureAarBytes(bodyBytes, patched.a()?.b()?.toString())
            HaierAarAuditUploader.enqueue(
                HaierAarAuditEvent(
                    eventType = "AAR_HTTP_AUDIT",
                    sourceStack = "spctv_okhttp",
                    method = patched.e(),
                    urlRaw = patched.g().toString(),
                    headersRaw = patched.c().toString(),
                    bodyRaw = capturedBody.raw,
                    contentType = patched.a()?.b()?.toString().orEmpty(),
                    bodyEncoding = capturedBody.encoding,
                    bodyLength = capturedBody.length,
                    bodySha256 = capturedBody.sha256,
                    auditId = auditId,
                    extra = mapOf(
                        "header_ua_final" to patched.a("User-Agent"),
                        "parameter_ua_final" to extractRtbUa(readShadedBody(patched))
                    )
                ),
                critical = url.contains("/rtb/bid")
            )
            patched
        }.getOrElse { error ->
            Log.e(TAG, "AAR shaded请求修正失败", error)
            request
        }
        return try {
            val response = chain.a(patched)
            val responseRequest = response.v()
            val bodyBytes = readShadedBodyBytes(responseRequest)
            val capturedBody = captureAarBytes(
                bodyBytes,
                responseRequest.a()?.b()?.toString()
            )
            HaierAarAuditUploader.enqueue(
                HaierAarAuditEvent(
                    eventType = "AAR_HTTP_RESPONSE",
                    sourceStack = "spctv_okhttp_final_network_interceptor",
                    method = responseRequest.e(),
                    urlRaw = responseRequest.g().toString(),
                    headersRaw = responseRequest.c().toString(),
                    bodyRaw = capturedBody.raw,
                    contentType = responseRequest.a()?.b()?.toString().orEmpty(),
                    bodyEncoding = capturedBody.encoding,
                    bodyLength = capturedBody.length,
                    bodySha256 = capturedBody.sha256,
                    responseCode = response.o(),
                    responseHeadersRaw = response.q().toString(),
                    durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs),
                    auditId = auditId,
                    extra = mapOf(
                        "header_ua_final" to responseRequest.a("User-Agent")
                    )
                ),
                critical = responseRequest.g().toString().contains("/rtb/bid")
            )
            response
        } catch (error: Throwable) {
            HaierAarAuditUploader.enqueue(
                HaierAarAuditEvent(
                    eventType = "AAR_HTTP_ERROR",
                    sourceStack = "spctv_okhttp_final_network_interceptor",
                    method = patched.e(),
                    urlRaw = patched.g().toString(),
                    headersRaw = patched.c().toString(),
                    bodyRaw = readShadedBody(patched),
                    errorMessage = "${error.javaClass.name}: ${error.message.orEmpty()}",
                    durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs),
                    auditId = auditId
                ),
                critical = true
            )
            throw error
        }
    }

    @JvmStatic
    fun captureAarCallbackResponse(owner: String, body: String?, status: Int) {
        HaierAarAuditUploader.enqueue(
            HaierAarAuditEvent(
                eventType = "AAR_CONFIG_RESPONSE",
                sourceStack = owner,
                method = "CALLBACK",
                urlRaw = "",
                headersRaw = "",
                bodyRaw = body.orEmpty(),
                responseCode = status
            )
        )
    }

    @JvmStatic
    fun captureHeziEncryptionInput(value: String?): String? {
        if (!value.isNullOrEmpty()) {
            HaierAarAuditUploader.enqueue(
                HaierAarAuditEvent(
                    eventType = "AAR_HEZI_PLAINTEXT",
                    sourceStack = "hezi_encrypt",
                    method = "ENCRYPT",
                    urlRaw = "",
                    headersRaw = "",
                    bodyRaw = value
                )
            )
        }
        return value
    }

    @JvmStatic
    fun systemLoad(path: String) {
        HaierAarAuditUploader.enqueue(
            HaierAarAuditEvent(
                eventType = "AAR_TITAN_EVENT",
                sourceStack = "system_load",
                method = "LOAD",
                urlRaw = path,
                headersRaw = "",
                bodyRaw = path,
                coverage = "native_unverified"
            ),
            critical = true
        )
        System.load(path)
    }

    @JvmStatic
    fun systemLoadLibrary(name: String) {
        HaierAarAuditUploader.enqueue(
            HaierAarAuditEvent(
                eventType = "AAR_TITAN_EVENT",
                sourceStack = "system_load_library",
                method = "LOAD_LIBRARY",
                urlRaw = name,
                headersRaw = "",
                bodyRaw = name,
                coverage = "native_unverified"
            ),
            critical = true
        )
        System.loadLibrary(name)
    }

    @JvmStatic
    fun nativeStart(workspace: String?, configJson: String?): Int {
        HaierAarAuditUploader.enqueue(
            HaierAarAuditEvent(
                eventType = "AAR_TITAN_EVENT",
                sourceStack = "titan_native_start",
                method = "JNI",
                urlRaw = workspace.orEmpty(),
                headersRaw = "",
                bodyRaw = configJson.orEmpty(),
                coverage = "native_unverified"
            ),
            critical = true
        )
        return TitanSDK.nativeStart(workspace, configJson)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun invokeDynamicMethod(method: Method, receiver: Any?, arguments: Array<Any?>?): Any? {
        val auditId = UUID.randomUUID().toString()
        HaierAarAuditUploader.enqueue(
            HaierAarAuditEvent(
                eventType = "AAR_DEX_EVENT",
                sourceStack = "dynamic_dex_invoke",
                method = method.name,
                urlRaw = method.declaringClass.name,
                headersRaw = "",
                bodyRaw = arguments?.contentDeepToString().orEmpty(),
                coverage = "dynamic_dex_unverified",
                auditId = auditId
            ),
            critical = true
        )
        return try {
            method.invoke(receiver, *(arguments ?: emptyArray())).also { result ->
                HaierAarAuditUploader.enqueue(
                    HaierAarAuditEvent(
                        eventType = "AAR_DEX_RESULT",
                        sourceStack = "dynamic_dex_invoke",
                        method = method.name,
                        urlRaw = method.declaringClass.name,
                        headersRaw = "",
                        bodyRaw = result?.toString().orEmpty(),
                        coverage = "dynamic_dex_unverified",
                        auditId = auditId
                    ),
                    critical = true
                )
            }
        } catch (error: Throwable) {
            HaierAarAuditUploader.enqueue(
                HaierAarAuditEvent(
                    eventType = "AAR_DEX_ERROR",
                    sourceStack = "dynamic_dex_invoke",
                    method = method.name,
                    urlRaw = method.declaringClass.name,
                    headersRaw = "",
                    bodyRaw = arguments?.contentDeepToString().orEmpty(),
                    errorMessage = "${error.javaClass.name}: ${error.message.orEmpty()}",
                    coverage = "dynamic_dex_unverified",
                    auditId = auditId
                ),
                critical = true
            )
            throw error
        }
    }

    private fun readShadedBody(request: w): String {
        val bytes = readShadedBodyBytes(request)
        val body = request.a() ?: return ""
        val mediaType = body.b()?.toString().orEmpty()
        return if (mediaType.startsWith("text/") ||
            mediaType.contains("json") ||
            mediaType.contains("xml") ||
            mediaType.contains("form")
        ) {
            bytes.toString(Charsets.UTF_8)
        } else {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    private fun readShadedBodyBytes(request: w): ByteArray {
        val body = request.a() ?: return ByteArray(0)
        val buffer = c()
        body.a(buffer)
        return buffer.s()
    }

    private fun extractRtbUa(body: String): String {
        return runCatching { JSONObject(body).optJSONObject("device")?.optString("ua").orEmpty() }
            .getOrDefault("")
    }

    private fun writeCachedUa(value: String) {
        val prefs = preferences ?: return
        if (prefs.getString(LSAD_WEB_UA, "").orEmpty() == value) return
        if (!writingCachedUa.compareAndSet(false, true)) return
        try {
            prefs.edit().putString(LSAD_WEB_UA, value).commit()
        } finally {
            writingCachedUa.set(false)
        }
    }

    private fun auditUaEvent(eventType: String, source: String, observed: String, effective: String) {
        runCatching {
            HaierAarAuditUploader.enqueue(
                HaierAarAuditEvent(
                    eventType = eventType,
                    sourceStack = source,
                    method = "UA",
                    urlRaw = "",
                    headersRaw = "",
                    bodyRaw = JSONObject()
                        .put("observed", observed)
                        .put("effective", effective)
                        .toString()
                ),
                critical = true
            )
        }.onFailure { Log.w(TAG, "UA漂移审计失败", it) }
    }
}
