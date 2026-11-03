package com.smart.android.ad_app

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.smart.android.ad_app.google.GoogleGamPatchClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TclBackendPatchTest {
    private val source = "https://tcl.example/ad?x=one&x=two&sig=a%2Bb%2Fc%3D&requestId=native-id"
    private val returned = "https://patched.example/ad?sig=server%2Bvalue&x=two&x=one"
    private fun metadata() = linkedMapOf<String, Any>("request_id" to "host-id",
        "channel_id" to "TCL_FFA", "rdid" to "persisted-tcl-id", "sdk_version" to "2.8.02")
    private fun client(block: Interceptor) = GoogleGamPatchClient(
        OkHttpClient.Builder().addInterceptor(block).build(), "https://backend.example/api/v2/ad/google-gam/patch")
    private fun response(chain: Interceptor.Chain, code: Int = 200) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
        .body(Gson().toJson(mapOf("code" to code, "data" to mapOf("ad_tag_url" to returned))).toResponseBody()).build()

    @Test fun `non Google TCL URL and frozen channel go through one patch and result stays exact`() {
        val count = AtomicInteger()
        val ready = AtomicInteger()
        val parameters = metadata()
        val patch = TclBackendPatch(parameters, { 5_000L }, client { chain ->
            count.incrementAndGet()
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            val json = JsonParser.parseString(buffer.readUtf8()).asJsonObject
            assertEquals(source, json["ad_tag_url"].asString)
            assertEquals("TCL_FFA", json["channel_id"].asString)
            assertEquals("persisted-tcl-id", json["rdid"].asString)
            response(chain)
        }, {}, { ready.incrementAndGet() })
        parameters["channel_id"] = "next-channel"
        assertEquals(returned, patch.resolve(source))
        assertEquals(returned, patch.resolve(source))
        assertEquals(1, count.get())
        assertEquals(1, ready.get())
    }

    @Test fun `primary and guarantee are patched separately without changing their source URLs`() {
        val seen = mutableListOf<String>()
        val patch = TclBackendPatch(metadata(), { 5_000L }, client { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            seen += JsonParser.parseString(buffer.readUtf8()).asJsonObject["ad_tag_url"].asString
            response(chain)
        }, {}, {})
        patch.resolve(source)
        patch.resolve("https://guarantee.example/fallback")
        assertEquals(listOf(source, "https://guarantee.example/fallback"), seen)
    }

    @Test fun `backend failure never returns the original advertising link`() {
        val patch = TclBackendPatch(metadata(), { 5_000L }, client { response(it, 100000) }, {}, {})
        try { patch.resolve(source); fail("Failure must stop this opportunity") }
        catch (_: IOException) { }
    }

    @Test fun `cancelling a pending patch prevents late delivery`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = AtomicInteger()
        val patch = TclBackendPatch(metadata(), { 5_000L }, client { chain ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            response(chain)
        }, {}, { ready.incrementAndGet() })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Boolean> {
                try { patch.resolve(source); false } catch (_: IOException) { true }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            patch.cancel()
            assertTrue(result.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(0, ready.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
