package com.smart.android.ad_app

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TclSdkRequestResultTest {
    private fun session(results: MutableList<Map<String, Any?>>, id: String = "request") =
        TclRuntimeSession(id, "TCL_FFA", TclPlaybackState(0L, 180_000L),
            TclRuntimeAuditReporter(id, "TCL_FFA", false, false), results::add) { }

    private fun request(session: TclRuntimeSession, url: String = "https://tcl.example/address") =
        Request.Builder().url(url).post("{}".toRequestBody())
            .tag(TclAddressRequest::class.java, TclAddressRequest(session)).build()

    private fun response(chain: Interceptor.Chain, code: Int, body: ResponseBody) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body).build()

    private fun client(handler: Interceptor) = OkHttpClient.Builder()
        .addInterceptor(TclSdkRequestInterceptor { 10L })
        .addInterceptor(handler).build()

    @Test fun `business rejection empty and malformed bodies and HTTP errors all confirm a request`() {
        for ((status, body) in listOf(
            200 to """{"code":100000,"data":{"adsUrl":"https://vast.example/ad"}}""",
            200 to """{"code":100001,"message":"no ad","data":null}""",
            204 to "",
            400 to """{"code":"BAD_REQUEST"}""",
            503 to "upstream unavailable"
        )) {
            val results = mutableListOf<Map<String, Any?>>()
            val session = session(results)
            client { response(it, status, body.toResponseBody()) }.newCall(request(session)).execute().close()
            session.finishRequest("error:no_fill_or_parse_failure")
            assertEquals("Only one result for HTTP $status", 1, results.size)
            assertEquals("REQUESTED", results.single()["sdkRequestOutcome"])
            assertEquals(true, results.single()["sdkRequestResponded"])
            assertEquals(status, results.single()["sdkRequestHttpStatus"])
            assertFalse("A request response is not fill", session.filled)
            if (body.contains("100001")) assertEquals("100001", results.single()["sdkRequestBusinessCode"])
        }
    }

    @Test fun `DNS and timeout failures preserve error diagnostics without reporting REQUESTED`() {
        for (failure in listOf(UnknownHostException("fixture DNS"), SocketTimeoutException("fixture timeout"))) {
            val results = mutableListOf<Map<String, Any?>>()
            val session = session(results)
            try {
                client { throw failure }.newCall(request(session)).execute()
                fail("Native request must retain its original error")
            } catch (error: IOException) { assertSame(failure, error) }
            assertTrue(results.isEmpty())
            session.finishRequest("error:tag_request_failed")
            session.finishRequest("timeout")
            assertTrue("No REQUESTED without an HTTP response", results.isEmpty())
            val diagnostics = session.sdkRequest.diagnostics()
            assertEquals("NO_RESPONSE", diagnostics["sdkRequestOutcome"])
            assertEquals(true, diagnostics["sdkRequestAttempted"])
            assertEquals(false, diagnostics["sdkRequestResponded"])
            assertNull(diagnostics["sdkRequestHttpStatus"])
            assertEquals(failure.message, diagnostics["sdkRequestError"])
        }
    }

    @Test fun `init container and cancellation failures retain not sent without reporting REQUESTED`() {
        for (reason in listOf("error:sdk_init_failed", "error:container_released", "timeout:sdk_init", "cancelled:replaced")) {
            val results = mutableListOf<Map<String, Any?>>()
            val session = session(results)
            session.finishRequest(reason)
            session.finishRequest("duplicate")
            assertTrue(results.isEmpty())
            val diagnostics = session.sdkRequest.diagnostics()
            assertEquals("NOT_SENT", diagnostics["sdkRequestOutcome"])
            assertEquals(false, diagnostics["sdkRequestAttempted"])
            assertEquals(reason, diagnostics["sdkRequestTerminalReason"])
            assertFalse(session.sdkRequest.begin(20L))
        }
    }

    @Test fun `a later SDK retry can confirm the request and repeated responses do not double count`() {
        val results = mutableListOf<Map<String, Any?>>()
        val session = session(results)
        var attempts = 0
        val client = client {
            attempts++
            if (attempts == 1) throw SocketTimeoutException("first attempt")
            response(it, 200, """{"code":"NO_AD"}""".toResponseBody())
        }
        try { client.newCall(request(session)).execute(); fail("First attempt must fail") }
        catch (_: IOException) { }
        assertTrue(results.isEmpty())
        client.newCall(request(session)).execute().close()
        client.newCall(request(session)).execute().close()
        session.finishRequest("error:no_fill")
        assertEquals(1, results.size)
        assertEquals("REQUESTED", results.single()["sdkRequestOutcome"])
        assertEquals(2, results.single()["sdkRequestAttempts"])
        assertEquals(3, session.sdkRequest.diagnostics()["sdkRequestAttempts"])
    }

    @Test fun `an interrupted response body still confirms the HTTP response`() {
        val results = mutableListOf<Map<String, Any?>>()
        val session = session(results)
        val brokenSource = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("body interrupted")
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = brokenSource
        }
        client { response(it, 200, body) }.newCall(request(session)).execute().close()
        session.finishRequest("error:body_read")
        assertEquals(1, results.size)
        assertEquals("REQUESTED", results.single()["sdkRequestOutcome"])
        assertTrue(results.single()["sdkRequestError"].toString().contains("body interrupted"))
    }

    @Test fun `terminal closure during body capture preserves headers and ignores late replacement results`() {
        val results = mutableListOf<Map<String, Any?>>()
        val session = session(results, "old")
        val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                session.finishRequest("cancelled:replaced")
                return super.read(sink, byteCount)
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 2L
            override fun source() = source
        }
        client { response(it, 500, body) }.newCall(request(session)).execute().close()
        assertEquals(1, results.size)
        assertEquals("REQUESTED", results.single()["sdkRequestOutcome"])
        assertEquals(500, results.single()["sdkRequestHttpStatus"])
        session.sdkRequest.responded(30, 200)
        session.publishRequestResult()
        val nextResults = mutableListOf<Map<String, Any?>>()
        val next = session(nextResults, "new")
        next.finishRequest("error:init")
        assertTrue(nextResults.isEmpty())
        assertEquals("NOT_SENT", next.sdkRequest.diagnostics()["sdkRequestOutcome"])
        assertEquals(1, results.size)
    }

    @Test fun `outer diagnostics throwing after a response cannot suppress the request result`() {
        val results = mutableListOf<Map<String, Any?>>()
        val session = session(results)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request()).close(); throw IOException("capture failed") }
            .addInterceptor(TclSdkRequestInterceptor { 10L })
            .addInterceptor { response(it, 200, "{}".toResponseBody()) }.build()
        try { client.newCall(request(session)).execute(); fail("Capture failure must propagate") }
        catch (_: IOException) { }
        session.finishRequest("error:capture")
        assertEquals(1, results.size)
        assertEquals("REQUESTED", results.single()["sdkRequestOutcome"])
    }

    @Test fun `native address ownership excludes tracking VAST initialization and unrelated POSTs`() {
        val results = mutableListOf<Map<String, Any?>>()
        val session = session(results)
        val engine = Any()
        val parameters = Any()
        val reporter = Any()
        val post = Request.Builder().url("https://tcl.example/test").post("{}".toRequestBody()).build()
        TclRuntimeBridge.starting(session) {
            TclRuntimeBridge.bindEngine(engine)
            TclRuntimeBridge.bindParams(parameters, engine)
            TclRuntimeBridge.bindReporter(reporter)
            assertNull(TclRuntimeBridge.tagRequest(post).tag(TclAddressRequest::class.java))
        }
        val address = TclRuntimeBridge.enterParams(parameters)
        try {
            assertSame(session, TclRuntimeBridge.tagRequest(post).tag(TclAddressRequest::class.java)?.session)
            val tracking = TclRuntimeBridge.enterTracking(reporter)
            try { assertNull(TclRuntimeBridge.tagRequest(post).tag(TclAddressRequest::class.java)) }
            finally { TclRuntimeBridge.leaveScope(tracking) }
            assertNotNull(TclRuntimeBridge.tagRequest(post).tag(TclAddressRequest::class.java))
            val vast = TclRuntimeBridge.enterRequest(null)
            try { assertNull(TclRuntimeBridge.tagRequest(post).tag(TclAddressRequest::class.java)) }
            finally { TclRuntimeBridge.leaveScope(vast) }
        } finally { TclRuntimeBridge.leaveScope(address) }
        client { response(it, 200, "{}".toResponseBody()) }.newCall(post).execute().close()
        session.finishRequest("error:before_address")
        assertTrue(results.isEmpty())
        assertEquals("NOT_SENT", session.sdkRequest.diagnostics()["sdkRequestOutcome"])
    }

    @Test fun `a redirect response counts even when the redirected connection fails`() {
        val results = mutableListOf<Map<String, Any?>>()
        val session = session(results)
        ServerSocket(0).use { server ->
            val unavailablePort = ServerSocket(0).use { it.localPort }
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\n" +
                        "Location: http://127.0.0.1:$unavailablePort/unavailable\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .callTimeout(3, TimeUnit.SECONDS)
                .addInterceptor(TclSdkRequestInterceptor { 10L })
                .addNetworkInterceptor(TclSdkResponseInterceptor { 11L }).build()
            try {
                client.newCall(request(session, "http://127.0.0.1:${server.localPort}/address")).execute()
                fail("Redirected connection must fail")
            } catch (_: IOException) { }
            finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
                worker.join(1_000)
            }
        }
        session.finishRequest("error:redirect")
        assertEquals(1, results.size)
        assertEquals("REQUESTED", results.single()["sdkRequestOutcome"])
        assertEquals(302, results.single()["sdkRequestHttpStatus"])
    }
}
