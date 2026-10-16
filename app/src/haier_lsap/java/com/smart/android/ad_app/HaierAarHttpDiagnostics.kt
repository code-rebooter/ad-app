package com.smart.android.ad_app

import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Observes OkHttp's actual call, including reads after HTTP headers. Never sends a probe request. */
internal object HaierAarHttpDiagnostics {
    private val lock = Any()
    private var requestId: String? = null
    private val calls = ArrayDeque<CallTrace>()
    private var omittedCalls = 0

    fun begin(id: String) = synchronized(lock) {
        requestId = id
        calls.clear()
        omittedCalls = 0
    }

    fun snapshot(id: String): Map<String, Any?> = synchronized(lock) {
        if (requestId != id) emptyMap() else mapOf(
            "http_calls" to org.json.JSONArray(calls.map { org.json.JSONObject(it.fields) }),
            "http_calls_omitted" to omittedCalls
        )
    }

    fun factory(original: EventListener.Factory, id: String): EventListener.Factory = EventListener.Factory { call ->
        val delegate = original.create(call)
        runCatching {
            val trace = CallTrace(id, call.request().url)
            synchronized(lock) {
                if (requestId == id) {
                    if (calls.size >= 16) { calls.removeFirst(); omittedCalls++ }
                    calls.addLast(trace)
                }
            }
            ForwardingListener(delegate, trace)
        }.getOrDefault(delegate)
    }

    private class CallTrace(val id: String, url: HttpUrl) {
        val started = System.nanoTime()
        val fields = linkedMapOf<String, Any?>(
            "url_hash" to AdPrivacySanitizer.shortHash(url.toString()),
            "host" to url.host.take(120),
            "request_kind" to when {
                url.encodedPath.endsWith("/vastTag") -> "vast_tag"
                url.encodedPath.startsWith("/adx/tag/") -> "adx_tag"
                else -> "tracking_or_other"
            },
            "started_at_ms" to System.currentTimeMillis(), "last_phase" to "created"
        )
        fun mark(phase: String, extra: Map<String, Any?> = emptyMap(), error: IOException? = null) {
            runCatching {
                synchronized(lock) {
                    if (requestId != id) return
                    fields["last_phase"] = phase
                    fields["${phase}_ms"] = (System.nanoTime() - started) / 1_000_000
                    fields.putAll(extra)
                    if (error != null) fields.putAll(HaierAarPlaybackDiagnostics.throwableDetails(error))
                }
                if (error != null || phase == "call_end") {
                    HaierAarPlaybackDiagnostics.evidence("http_call_result", synchronized(lock) { fields.toMap() }, id,
                        isFailure = phase == "call_failed" && fields["request_kind"] in setOf("vast_tag", "adx_tag"))
                }
            }
        }
    }

    /** Every callback is forwarded once to the existing listener; its behavior is preserved. */
    private class ForwardingListener(val delegate: EventListener, val trace: CallTrace) : EventListener() {
        override fun callStart(call: Call) {
            trace.mark("call_start")
            delegate.callStart(call)
        }
        override fun proxySelectStart(call: Call, url: HttpUrl) {
            trace.mark("proxy_start")
            delegate.proxySelectStart(call, url)
        }
        override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
            trace.mark("proxy_end")
            delegate.proxySelectEnd(call, url, proxies)
        }
        override fun dnsStart(call: Call, domainName: String) {
            trace.mark("dns_start")
            delegate.dnsStart(call, domainName)
        }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            trace.mark("dns_end")
            delegate.dnsEnd(call, domainName, inetAddressList)
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            trace.mark("connect_start")
            delegate.connectStart(call, inetSocketAddress, proxy)
        }
        override fun secureConnectStart(call: Call) {
            trace.mark("tls_start")
            delegate.secureConnectStart(call)
        }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            trace.mark("tls_end")
            delegate.secureConnectEnd(call, handshake)
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            trace.mark("connect_end", mapOf("protocol" to protocol?.toString()))
            delegate.connectEnd(call, inetSocketAddress, proxy, protocol)
        }
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            trace.mark("connect_failed", error = ioe)
            delegate.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        }
        override fun connectionAcquired(call: Call, connection: Connection) {
            trace.mark("connection_acquired")
            delegate.connectionAcquired(call, connection)
        }
        override fun connectionReleased(call: Call, connection: Connection) {
            trace.mark("connection_released")
            delegate.connectionReleased(call, connection)
        }
        override fun requestHeadersStart(call: Call) {
            trace.mark("request_headers_start")
            delegate.requestHeadersStart(call)
        }
        override fun requestHeadersEnd(call: Call, request: Request) {
            trace.mark("request_headers_end")
            delegate.requestHeadersEnd(call, request)
        }
        override fun requestBodyStart(call: Call) {
            trace.mark("request_body_start")
            delegate.requestBodyStart(call)
        }
        override fun requestBodyEnd(call: Call, byteCount: Long) {
            trace.mark("request_body_end")
            delegate.requestBodyEnd(call, byteCount)
        }
        override fun requestFailed(call: Call, ioe: IOException) {
            trace.mark("request_failed", error = ioe)
            delegate.requestFailed(call, ioe)
        }
        override fun responseHeadersStart(call: Call) {
            trace.mark("response_headers_start")
            delegate.responseHeadersStart(call)
        }
        override fun responseHeadersEnd(call: Call, response: Response) {
            trace.mark("response_headers_end", mapOf("response_code" to response.code, "response_content_type" to response.header("Content-Type").orEmpty().take(100), "response_declared_bytes" to response.body?.contentLength()))
            delegate.responseHeadersEnd(call, response)
        }
        override fun responseBodyStart(call: Call) {
            trace.mark("response_body_start")
            delegate.responseBodyStart(call)
        }
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            trace.mark("response_body_end", mapOf("response_bytes_read" to byteCount))
            delegate.responseBodyEnd(call, byteCount)
        }
        override fun responseFailed(call: Call, ioe: IOException) {
            trace.mark("response_failed", error = ioe)
            delegate.responseFailed(call, ioe)
        }
        override fun callEnd(call: Call) {
            trace.mark("call_end")
            delegate.callEnd(call)
        }
        override fun callFailed(call: Call, ioe: IOException) {
            trace.mark("call_failed", error = ioe)
            delegate.callFailed(call, ioe)
        }
        override fun canceled(call: Call) {
            trace.mark("canceled")
            delegate.canceled(call)
        }
        override fun satisfactionFailure(call: Call, response: Response) {
            trace.mark("cache_unsatisfied")
            delegate.satisfactionFailure(call, response)
        }
        override fun cacheHit(call: Call, response: Response) {
            trace.mark("cache_hit")
            delegate.cacheHit(call, response)
        }
        override fun cacheMiss(call: Call) {
            trace.mark("cache_miss")
            delegate.cacheMiss(call)
        }
        override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
            trace.mark("cache_conditional_hit")
            delegate.cacheConditionalHit(call, cachedResponse)
        }
    }
}
