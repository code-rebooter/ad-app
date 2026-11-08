package com.smart.android.tclad.ima

import com.google.gson.Gson
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class GoogleImaBackendPatchTest {
    private val seed = "https://pubads.g.doubleclick.net/gampad/ads?iu=unit"
    private val generated = "$seed&correlator=17&sdkv=h.3.539.0&rdid=gms&x=one&x=two&ms=a%2Bb%2Fc%3D"
    private val returned = "https://patched.example.test/ads?x=two&x=one&ms=server%2Bvalue#end"
    private val metadata get() = linkedMapOf<String, Any>(
        "request_id" to "authorized-request", "channel_id" to "authorized-channel",
        "rdid" to "persisted-local-id", "androidid" to "android-id", "sdk_version" to "3.35.1"
    )
    private fun client(interceptor: Interceptor) = GoogleGamPatchClient(
        OkHttpClient.Builder().addInterceptor(interceptor).build(),
        "https://backend.example.test/api/v2/ad/google-gam/patch"
    )
    private fun response(chain: Interceptor.Chain, code: Int = 200, url: String = returned) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture")
            .body(Gson().toJson(mapOf("code" to code, "data" to mapOf("ad_tag_url" to url)))
                .toResponseBody("application/json".toMediaType())).build()

    @Test fun `native and web fallback share one POST with frozen identity and the exact Core URL`() {
        val count = AtomicInteger()
        val ready = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val originalMetadata = metadata
        val patch = GoogleImaBackendPatch(seed, originalMetadata, 10_000L,
            client = client { chain ->
                count.incrementAndGet()
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                assertEquals("authorized-channel", body["channel_id"].asString)
                assertEquals("authorized-request", body["request_id"].asString)
                assertEquals("persisted-local-id", body["rdid"].asString)
                assertEquals(generated, body["ad_tag_url"].asString)
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                response(chain)
            }, clock = { 0L }, onReady = { ready.incrementAndGet() })
        originalMetadata["channel_id"] = "next-channel"
        val pool = Executors.newFixedThreadPool(2)
        try {
            val native = pool.submit<String> { patch.resolve(generated) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val web = pool.submit<String> { patch.resolve(generated) }
            release.countDown()
            assertEquals(returned, native.get(3, TimeUnit.SECONDS))
            assertEquals(returned, web.get(3, TimeUnit.SECONDS))
            assertEquals(returned, patch.resolve(generated))
            assertEquals(returned, patch.resolve(generated + "&native_retry=1"))
            assertEquals(1, count.get())
            assertEquals(1, ready.get())
        } finally { release.countDown(); patch.cancel(); pool.shutdownNow() }
    }

    @Test fun `cancellation unblocks callers and a late response cannot deliver READY`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = AtomicInteger()
        val patch = GoogleImaBackendPatch(seed, metadata, 10_000L,
            client = client { chain ->
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); response(chain)
            }, clock = { 0L }, onReady = { ready.incrementAndGet() })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Boolean> { runCatching { patch.resolve(generated) }.isFailure }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            patch.cancel()
            assertTrue(result.get(3, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(patch.handles(generated))
            assertTrue(runCatching { patch.resolve(generated) }.isFailure)
            assertEquals(0, ready.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `failed patch stays failed across fallback and never returns the local URL`() {
        val posts = AtomicInteger()
        val failures = AtomicInteger()
        val patch = GoogleImaBackendPatch(seed, metadata, 10_000L,
            client = client { chain -> posts.incrementAndGet(); response(chain, 500) },
            clock = { 0L }, onFailure = { failures.incrementAndGet() })
        repeat(2) {
            try { patch.resolve(generated); fail("must stop the advertising request") }
            catch (_: IOException) { }
        }
        assertEquals(1, posts.get())
        assertEquals(1, failures.get())
    }

    @Test fun `expired session cannot accept a successful patch response`() {
        val now = AtomicLong(0)
        val ready = AtomicInteger()
        val patch = GoogleImaBackendPatch(seed, metadata, 1000L,
            client = client { chain -> now.set(1001); response(chain) },
            clock = now::get, onReady = { ready.incrementAndGet() })
        assertTrue(runCatching { patch.resolve(generated) }.isFailure)
        assertEquals(0, ready.get())
    }

    @Test fun `only the primary ad URL is patched leaving trackers wrappers and scripts alone`() {
        val patch = GoogleImaBackendPatch(seed, metadata, 1000L, client = client { error("not requested") }, clock = { 0L })
        assertFalse(patch.handles("https://pubads.g.doubleclick.net/pcs/activeview?id=lidarv"))
        assertFalse(patch.handles("https://imasdk.googleapis.com/native/core/3.539.0/native_bridge__en.js"))
        assertTrue(patch.handles(generated))
        assertFalse(patch.handles("$seed&iu=wrapper"))
        assertTrue(patch.handles(generated))
    }
    @Test fun `authorized reported sound survives the full Core URL patch round trip`() {
        for ((mode, expected) in listOf(0 to "1", 1 to "0")) {
            val patch = GoogleImaBackendPatch(seed,
                metadata + ("reported_sound_mode" to mode), 10_000L,
                client = client { chain ->
                    val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                    val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                    assertEquals(mode, body["reported_sound_mode"].asInt)
                    assertEquals(generated, body["ad_tag_url"].asString)
                    response(chain, url = "https://patched.test/tag?ms=a%2Bb%2Fc%3D&x=one&VPMUTE=wrong&x=two#vpmute=keep")
                }, clock = { 0L })
            assertEquals("https://patched.test/tag?ms=a%2Bb%2Fc%3D&x=one&vpmute=$expected&x=two#vpmute=keep",
                patch.resolve(generated))
        }
    }

}
