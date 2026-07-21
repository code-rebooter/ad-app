package com.smart.android.ad_app

import android.util.Log
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object HaierAarOkHttpAudit {
    private const val TAG = "HaierAarOkHttp"
    private const val REPORT_PATH = "/api/v2/ad/report"

    fun newCall(client: OkHttpClient, request: Request): Call {
        val effectiveUa = HaierAarRuntimeBridge.currentEffectiveUa()
        val prepared = prepareRequest(request).newBuilder()
            .header("User-Agent", effectiveUa)
            .build()
        val auditedClient = client.newBuilder()
            .addNetworkInterceptor(FinalNetworkInterceptor)
            .build()
        return auditedClient.newCall(prepared)
    }

    private object FinalNetworkInterceptor : Interceptor {
        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            val incoming = chain.request()
            if (incoming.url.encodedPath == REPORT_PATH) {
                return chain.proceed(incoming)
            }

            val auditId = UUID.randomUUID().toString()
            val startedAtNs = System.nanoTime()
            val effectiveUa = HaierAarRuntimeBridge.currentEffectiveUa()
            val originalUa = incoming.header("User-Agent").orEmpty()
            val normalizedAtFinalBoundary = prepareRequest(incoming)
            val outgoing = normalizedAtFinalBoundary.newBuilder()
                .header("User-Agent", effectiveUa)
                .build()
            val capturedBody = captureRequestBody(outgoing)
            val requestEvent = HaierAarAuditEvent(
                eventType = "AAR_HTTP_AUDIT",
                sourceStack = "okhttp3_final_network_interceptor",
                method = outgoing.method,
                urlRaw = outgoing.url.toString(),
                headersRaw = outgoing.headers.toString(),
                bodyRaw = capturedBody.raw,
                contentType = outgoing.body?.contentType()?.toString().orEmpty(),
                bodyEncoding = capturedBody.encoding,
                bodyLength = capturedBody.length,
                bodySha256 = capturedBody.sha256,
                auditId = auditId,
                extra = mapOf(
                    "header_ua_observed" to originalUa,
                    "header_ua_final" to outgoing.header("User-Agent").orEmpty(),
                    "body_unavailable_reason" to capturedBody.unavailableReason
                )
            )
            HaierAarAuditUploader.enqueue(requestEvent, critical = isCritical(outgoing))

            return try {
                val response = chain.proceed(outgoing)
                HaierAarAuditUploader.enqueue(
                    HaierAarAuditEvent(
                        eventType = "AAR_HTTP_RESPONSE",
                        sourceStack = "okhttp3_final_network_interceptor",
                        method = response.request.method,
                        urlRaw = response.request.url.toString(),
                        headersRaw = response.request.headers.toString(),
                        bodyRaw = capturedBody.raw,
                        contentType = response.request.body?.contentType()?.toString().orEmpty(),
                        bodyEncoding = capturedBody.encoding,
                        bodyLength = capturedBody.length,
                        bodySha256 = capturedBody.sha256,
                        responseCode = response.code,
                        responseHeadersRaw = response.headers.toString(),
                        durationMs = elapsedMs(startedAtNs),
                        auditId = auditId,
                        extra = mapOf(
                            "header_ua_final" to response.request.header("User-Agent").orEmpty(),
                            "response_protocol" to response.protocol.toString(),
                            "response_message" to response.message
                        )
                    ),
                    critical = isCritical(response.request)
                )
                response
            } catch (error: Throwable) {
                HaierAarAuditUploader.enqueue(
                    HaierAarAuditEvent(
                        eventType = "AAR_HTTP_ERROR",
                        sourceStack = "okhttp3_final_network_interceptor",
                        method = outgoing.method,
                        urlRaw = outgoing.url.toString(),
                        headersRaw = outgoing.headers.toString(),
                        bodyRaw = capturedBody.raw,
                        contentType = outgoing.body?.contentType()?.toString().orEmpty(),
                        bodyEncoding = capturedBody.encoding,
                        bodyLength = capturedBody.length,
                        bodySha256 = capturedBody.sha256,
                        errorMessage = "${error.javaClass.name}: ${error.message.orEmpty()}",
                        durationMs = elapsedMs(startedAtNs),
                        auditId = auditId
                    ),
                    critical = true
                )
                throw error
            }
        }
    }

    private fun captureRequestBody(request: Request): HaierCapturedBody {
        val body = request.body ?: return captureAarBytes(ByteArray(0), null)
        val knownLength = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (runCatching { body.isDuplex() }.getOrDefault(false)) {
            return unavailableAarBody("okhttp_duplex_body", knownLength)
        }
        if (runCatching { body.isOneShot() }.getOrDefault(false)) {
            return unavailableAarBody("okhttp_one_shot_body", knownLength)
        }
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            captureAarBytes(buffer.readByteArray(), body.contentType()?.toString())
        }.onFailure {
            Log.w(TAG, "读取 OkHttp 请求体失败，不影响原广告请求", it)
        }.getOrElse {
            unavailableAarBody("okhttp_body_capture_failed:${it.javaClass.name}", knownLength)
        }
    }

    private fun prepareRequest(request: Request): Request {
        val normalizedUrl = normalizeAarUrlUa(request.url.toString())
        val builder = request.newBuilder().url(normalizedUrl)
        val body = request.body ?: return builder.build()
        val knownLength = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (runCatching { body.isDuplex() || body.isOneShot() }.getOrDefault(false)) {
            return builder.build()
        }
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            val bytes = buffer.readByteArray()
            val contentType = body.contentType()
            val original = bytes.toString(Charsets.UTF_8)
            val normalized = normalizeAarPayloadUa(original, contentType?.toString())
            if (normalized != original) {
                builder.method(request.method, normalized.toRequestBody(contentType))
            }
            builder.build()
        }.onFailure {
            Log.w(TAG, "规范化 OkHttp UA 参数失败，不影响原广告请求，length=$knownLength", it)
        }.getOrElse { request.newBuilder().url(normalizedUrl).build() }
    }

    private fun isCritical(request: Request): Boolean {
        val path = request.url.encodedPath
        return path.contains("/rtb/bid") || path.contains("/vastTag")
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs)
    }
}
