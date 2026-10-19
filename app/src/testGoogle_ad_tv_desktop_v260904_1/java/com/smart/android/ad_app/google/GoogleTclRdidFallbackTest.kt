package com.smart.android.ad_app.google

import org.junit.Assert.*
import org.junit.Test

class GoogleTclRdidFallbackTest {
    private val uid = "0d59ba0b-d4c5-4be7-bb15-60cf700da4d2"

    @Test fun `missing empty and whitespace IDs use UID and the original TCL type`() {
        for (suffix in listOf("", "&rdid=", "&rdid", "&rdid=%20+%09")) {
            val result = GoogleTclRdidFallback.apply("https://a/ads?is_lat=0&idtype=adid$suffix") { uid }
            assertTrue(result, result.contains("rdid=$uid"))
            assertTrue(result, result.contains("idtype=aaid"))
            assertFalse(result, result.contains("idtype=adid"))
        }
        assertEquals("https://a/ads?rdid=$uid&idtype=aaid#tail",
            GoogleTclRdidFallback.apply("https://a/ads#tail") { uid })
    }

    @Test fun `valid ID and explicit zero ID are never replaced or generate UID`() {
        for (id in listOf("real%2Bid", "00000000-0000-0000-0000-000000000000")) {
            val input = "https://a/ads?rdid=&rdid=$id&idtype=adid#tail"
            assertEquals(input, GoogleTclRdidFallback.apply(input) { error("must not generate UID") })
        }
    }

    @Test fun `explicit restrictions do not recreate withheld identity in either switch branch`() {
        for (flag in listOf("is_lat=1", "is%5Flat=%31", "ltd=1", "tfcd=1", "tfua=1", "dnt=true")) {
            for (enabled in listOf(false, true)) {
                val input = "https://a/ads?output=vast&rdid=&idtype=adid&$flag"
                val result = GoogleImaTclUrlPipeline.apply(input, input, enabled, false, true, "app",
                    fallbackUid = { error("must not generate UID for $flag") }, commonPatch = { it })
                assertFalse(result.url.contains(uid))
            }
        }
    }

    @Test fun `unrelated encoded signals duplicates and fragment keep exact bytes`() {
        val raw = "ms=a%2Bb%2Fc%3D&uach=real%3D&custom=one&custom=two&bad=%ZZ&is_lat=0"
        val result = GoogleTclRdidFallback.apply("https://a/ads?$raw&rdid=&idtype=adid&rdid=+&idtype=adid#x?rdid=keep") { uid }
        assertEquals("https://a/ads?$raw&rdid=$uid&idtype=aaid#x?rdid=keep", result)
    }

    @Test fun `fallback runs after common correction in both switch branches`() {
        val input = "https://a/ads?output=vast&rdid=before-common&idtype=adid&is_lat=0"
        for (enabled in listOf(false, true)) {
            var commonRan = false
            var uidCalls = 0
            val result = GoogleImaTclUrlPipeline.apply(input, input, enabled, false, true, "app",
                fallbackUid = { assertTrue(commonRan); uidCalls++; uid },
                commonPatch = { commonRan = true; it.replace("rdid=before-common", "rdid=") })
            assertEquals(1, uidCalls)
            assertTrue(result.url.contains("rdid=$uid"))
            assertTrue(result.url.contains("idtype=aaid"))
            assertTrue(result.commonPatchChanged)
        }
    }

    @Test fun `unavailable UID leaves existing URL unchanged`() {
        val input = "https://a/ads?rdid=&is_lat=0&idtype=adid"
        assertEquals(input, GoogleTclRdidFallback.apply(input) { " " })
    }
}
