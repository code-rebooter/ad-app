package com.smart.android.ad_app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class Hq008SdkRequestReportsTest {
    private val payload = """{"request_id":"original","channel_id":"TCL_FFA","ad_version":20,"event_type":"AD_ERROR","message":"TIMEOUT"}"""

    @Test fun `REQUESTED retries share an identity but cannot replace later progress or errors`() {
        val requested = mapOf<String, Any>("request_id" to "original", "event_type" to "AD_REQUESTED",
            "message" to "REQUESTED")
        val key = Hq008SdkRequestReports.reportKey(requested)
        assertEquals(key, Hq008SdkRequestReports.reportKey(requested + ("ad_version" to 20)))
        assertNotEquals(key, Hq008SdkRequestReports.reportKey(requested +
            mapOf("event_type" to "AD_LOADED", "message" to "LOADED")))
        assertNotEquals(key, Hq008SdkRequestReports.reportKey(requested +
            mapOf("event_type" to "AD_ERROR", "message" to "TIMEOUT")))
    }

    @Test fun `failed upload keeps the original payload for restart and later acknowledgement`() = runBlocking {
        val persisted = mutableMapOf("report" to payload)
        val sent = mutableListOf<String>()
        suspend fun run(online: Boolean) = Hq008SdkRequestReports.deliver("report", persisted::get,
            acknowledge = { key, saved ->
                assertEquals(saved, persisted[key])
                persisted.remove(key)
            }) { saved -> sent += saved; online }
        assertFalse(run(false))
        assertEquals(payload, persisted["report"])
        // A new execution reads persisted data; it does not rebuild channel/version from current config.
        assertTrue(run(true))
        assertTrue(persisted.isEmpty())
        assertEquals(listOf(payload, payload), sent)
        assertTrue(run(true))
        assertEquals(2, sent.size)
    }

    @Test fun `transport exception never acknowledges or discards the pending request result`() = runBlocking {
        val persisted = mutableMapOf("report" to payload)
        try {
            Hq008SdkRequestReports.deliver("report", persisted::get,
                acknowledge = { _, _ -> fail("No acknowledgement after a transport failure") }) {
                throw IOException("offline")
            }
            fail("Transport failure must reach the retrying worker")
        } catch (_: IOException) { }
        assertEquals(payload, persisted["report"])
    }

    @Test fun `lost local acknowledgement preserves the result for an idempotent replay`() = runBlocking {
        val persisted = mutableMapOf("report" to payload)
        try {
            Hq008SdkRequestReports.deliver("report", persisted::get,
                acknowledge = { _, _ -> throw IOException("local acknowledgement interrupted") }) { true }
            fail("An interrupted acknowledgement must be retried")
        } catch (_: IOException) { }
        assertEquals(payload, persisted["report"])
        var replayed = false
        assertTrue(Hq008SdkRequestReports.deliver("report", persisted::get,
            acknowledge = { key, _ -> persisted.remove(key) }) {
            replayed = true
            assertEquals(payload, it)
            true
        })
        assertTrue(replayed)
        assertTrue(persisted.isEmpty())
    }
}
