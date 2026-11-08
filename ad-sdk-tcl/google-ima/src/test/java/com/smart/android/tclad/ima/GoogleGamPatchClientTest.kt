package com.smart.android.tclad.ima

import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GoogleGamPatchClientTest {
    private val localUrl = "https://ads.example.test/ads?rdid=gms-id&ms=a%2Bb%2Fc%3D&x=one&x=two#tail"
    private val returnedUrl = "https://ads.example.test/ads?ua=backend%20value&ms=server%2Bvalue&x=two&x=one#server"
    private fun parameters() = linkedMapOf<String, Any>(
        "request_id" to "authorize-request", "channel_id" to "authorized-channel",
        "ad_tag_url" to localUrl, "rdid" to "stored-local-uid", "mac" to "00:11:22:33:44:55",
        "androidid" to "actual-android-id", "ad_version_name" to "host-version", "sdk_version" to "3.35.1"
    )
    private fun response(url: String = returnedUrl) = com.google.gson.Gson().toJson(
        mapOf("code" to 200, "msg" to "success", "data" to mapOf("ad_tag_url" to url))
    )
    private fun client(interceptor: Interceptor) = GoogleGamPatchClient(
        OkHttpClient.Builder().addInterceptor(interceptor).build(),
        "https://own-backend.example.test/api/v2/ad/google-gam/patch"
    )
    private fun http(chain: Interceptor.Chain, code: Int, body: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun `POST sends local URL and a separate stored UID and preserves the final server URL`() {
        val inspected = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val client = client { chain ->
            try {
                val request = chain.request()
                assertEquals("POST", request.method)
                assertEquals("/api/v2/ad/google-gam/patch", request.url.encodedPath)
                assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                val data = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                for ((key, value) in parameters()) assertEquals(value, data.get(key).asString)
                assertEquals(localUrl, data.get("ad_tag_url").asString)
                assertEquals("stored-local-uid", data.get("rdid").asString)
                assertFalse(data.has("ima_tcl_url_patch"))
            } catch (error: Throwable) { failure.set(error) }
            inspected.countDown()
            http(chain, 200, response())
        }
        val done = CountDownLatch(1)
        var actualUrl: String? = null
        var actualError: String? = null
        val call = client.request(parameters(), 2500L) { url, error -> actualUrl = url; actualError = error; done.countDown() }
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2500), call.timeout().timeoutNanos())
        assertTrue(inspected.await(3, TimeUnit.SECONDS))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
        assertNull(actualError)
        assertEquals(returnedUrl, actualUrl)
        assertFalse(actualUrl!!.contains("stored-local-uid"))
    }

    @Test fun `HTTP and API errors never return the original local URL`() {
        for ((httpCode, body) in listOf(
            400 to """{"code":400,"msg":"missing parameter","data":{"ad_tag_url":""}}""",
            200 to """{"code":400,"msg":"invalid","data":{"ad_tag_url":"https://unused.test/ads"}}""",
            200 to """{"code":100000,"data":{"ad_tag_url":"https://unused.test/ads"}}""",
            200 to """{"code":200,"data":{"ad_tag_url":" "}}""",
            200 to """{"code":200,"data":{"ad_tag_url":7}}""",
            200 to "not-json"
        )) {
            val client = client { chain -> http(chain, httpCode, body) }
            val done = CountDownLatch(1)
            var url: String? = "uninitialized"
            var error: String? = null
            client.request(parameters(), 1000L) { result, failure -> url = result; error = failure; done.countDown() }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(url)
            assertFalse(error.isNullOrBlank())
        }
    }

    @Test fun `cancellation exposes no usable final URL`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val client = client { chain ->
            entered.countDown()
            if (!release.await(3, TimeUnit.SECONDS)) throw IOException("fixture deadline")
            http(chain, 200, response())
        }
        var finalUrl: String? = "uninitialized"
        val call = client.request(parameters(), 2000L) { url, _ -> finalUrl = url; done.countDown() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        call.cancel()
        release.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(call.isCanceled())
        assertNull(finalUrl)
    }

    @Test fun `mandatory request identity URL and UID must be present`() {
        for (key in listOf("request_id", "channel_id", "ad_tag_url", "rdid")) {
            try {
                GoogleGamPatchProtocol.requestJson(parameters().apply { this[key] = " " })
                fail("must reject missing $key")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains(key))
            }
        }
    }

    @Test fun `blank missing and malformed server response fields fail`() {
        for (body in listOf("{}", "[]", "null", """{"code":200}""", """{"code":200,"data":null}""",
            """{"code":200,"data":[]}""", """{"code":200,"data":{"ad_tag_url":null}}""",
            """{"code":200.5,"data":{"ad_tag_url":"https://unused.test/ads"}}""")) {
            try {
                GoogleGamPatchProtocol.responseUrl(200, body)
                fail("must reject $body")
            } catch (_: IOException) { }
        }
    }
}
