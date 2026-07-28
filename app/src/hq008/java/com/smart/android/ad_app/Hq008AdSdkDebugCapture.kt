package com.smart.android.ad_app

import com.smart.android.ad_app.AdLocalLog as Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tcl.ff.component.overseahttp.http.HttpRequester
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal object Hq008AdSdkDebugCapture {
    private const val TAG = "Hq008AdSdkDebug"
    private const val MAX_HTTP_CAPTURE_BYTES = 256L * 1024L
    private const val MAX_CAPTURED_REQUEST_LENGTH = 8_000
    private const val MAX_CAPTURED_RESPONSE_LENGTH = 2_000

    private val installed = AtomicBoolean(false)
    private val installLock = Any()

    private object AdSdkHttpCaptureInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val requestUrl = request.url.toString()
            val requestBody = readRequestBodySafely(request)
            return try {
                val response = chain.proceed(request)
                captureIfNeeded(
                    requestUrl = requestUrl,
                    requestBody = requestBody,
                    responseBody = response.peekBody(MAX_HTTP_CAPTURE_BYTES).string(),
                    failureMessage = null
                )
                response
            } catch (error: Throwable) {
                captureIfNeeded(
                    requestUrl = requestUrl,
                    requestBody = requestBody,
                    responseBody = null,
                    failureMessage = error.message ?: error.javaClass.simpleName
                )
                throw error
            }
        }
    }

    fun ensureInstalled(): Boolean {
        prepopulateSdkGlobalContext()
        if (installed.get()) {
            return true
        }
        synchronized(installLock) {
            if (installed.get()) {
                return true
            }
            return runCatching {
                val httpRequester = HttpRequester.get()
                val clientField = findHttpClientField(httpRequester.javaClass)
                    ?: error("missing OkHttpClient field on HttpRequester")
                val currentClient = clientField.get(httpRequester) as? OkHttpClient
                    ?: error("missing OkHttpClient instance on HttpRequester")
                if (currentClient.interceptors.any { it === AdSdkHttpCaptureInterceptor }) {
                    installed.set(true)
                    return@runCatching true
                }
                val replacedClient = currentClient.newBuilder()
                    .addInterceptor(AdSdkHttpCaptureInterceptor)
                    .build()
                clientField.set(httpRequester, replacedClient)
                installed.set(true)
                Log.i(TAG, "广告 SDK 抓包：已注入 HttpRequester 原始请求拦截器")
                true
            }.getOrElse { error ->
                Log.e(TAG, "广告 SDK 抓包：注入 HttpRequester 原始请求拦截器失败", error)
                false
            }
        }
    }

    fun isSdkVerboseLogEnabled(): Boolean {
        return true
    }

    private fun prepopulateSdkGlobalContext() {
        runCatching {
            com.tcl.ff.component.overseabase.base.util.GlobalContext.setAppContext(appContext)
        }.onFailure { error ->
            Log.w(TAG, "广告 SDK 抓包：预填充 GlobalContext 失败，后续将继续尝试注入", error)
        }
    }

    private fun readRequestBodySafely(request: okhttp3.Request): String? {
        val requestBody = request.body ?: return null
        val unsafeToReplay = runCatching {
            val bodyClass = requestBody.javaClass
            val isOneShot = bodyClass.methods.firstOrNull {
                it.name == "isOneShot" && it.parameterTypes.isEmpty()
            }?.invoke(requestBody) as? Boolean ?: false
            val isDuplex = bodyClass.methods.firstOrNull {
                it.name == "isDuplex" && it.parameterTypes.isEmpty()
            }?.invoke(requestBody) as? Boolean ?: false
            isOneShot || isDuplex
        }.getOrDefault(false)
        if (unsafeToReplay) {
            return null
        }
        return runCatching {
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        }.getOrNull()
    }

    private fun findHttpClientField(targetClass: Class<*>): java.lang.reflect.Field? {
        var current: Class<*>? = targetClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull {
                OkHttpClient::class.java.isAssignableFrom(it.type)
            }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun captureIfNeeded(
        requestUrl: String,
        requestBody: String?,
        responseBody: String?,
        failureMessage: String?
    ) {
        if (!Hq008ConsentLogReporter.hasActiveFlow()) {
            return
        }
        val payload = parseAdSdkPayload(requestBody) ?: return
        val gdprConsent = payload.extractString("gdprConsent")
        val requestBodyForLog = requestBody?.take(MAX_CAPTURED_REQUEST_LENGTH)
        val responseBodyForLog = responseBody?.take(MAX_CAPTURED_RESPONSE_LENGTH)
        Log.i(
            TAG,
            "广告 SDK 抓包：已捕获最终广告请求，gdprConsentPresent=${!gdprConsent.isNullOrBlank()}，gdprConsentLength=${gdprConsent?.length ?: 0}，url=$requestUrl"
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_SDK_HTTP_CAPTURE",
            eventMessage = buildString {
                append("requestUrl=$requestUrl")
                append(",gdprConsentPresent=${!gdprConsent.isNullOrBlank()}")
                append(",gdprConsentLength=${gdprConsent?.length ?: 0}")
                append(",payloadLength=${requestBody?.length ?: 0}")
                append(",responseLength=${responseBody?.length ?: 0}")
                failureMessage?.takeIf { it.isNotBlank() }?.let {
                    append(",error=$it")
                }
            },
            adLog = JSONObject()
                .put("requestUrl", requestUrl)
                .put("requestBody", requestBodyForLog)
                .put("requestBodyLength", requestBody?.length ?: 0)
                .put("responseBody", responseBodyForLog)
                .put("responseBodyLength", responseBody?.length ?: 0)
                .put("failureMessage", failureMessage)
                .put("gdprConsentPresent", !gdprConsent.isNullOrBlank())
                .put("gdprConsentLength", gdprConsent?.length ?: 0)
                .put("gdprConsentPreview", gdprConsent?.let(::buildConsentPreview))
                .put("gdprConsentSuffix", gdprConsent?.takeLast(32))
                .toString()
        )
    }

    private fun parseAdSdkPayload(requestBody: String?): JsonObject? {
        if (requestBody.isNullOrBlank()) {
            return null
        }
        val payload = runCatching { JsonParser.parseString(requestBody).asJsonObject }.getOrNull() ?: return null
        val hasAdSdkShape = payload.has("application") &&
            payload.has("playerWidth") &&
            payload.has("playerHeight") &&
            payload.has("placement") &&
            payload.has("appPackage")
        return payload.takeIf { hasAdSdkShape }
    }

    private fun JsonObject.extractString(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) {
            return null
        }
        return runCatching { element.asString }.getOrNull()
    }

    private fun buildConsentPreview(consent: String): String {
        return if (consent.length <= 32) {
            consent
        } else {
            "${consent.take(16)}...${consent.takeLast(16)}"
        }
    }
}
