package com.smart.android.ad_app.google

import com.google.gson.JsonParser
import com.smart.android.ad_app.Hq008ConsentLogReporter
import com.smart.android.ad_app.Hq008ConsentTraceSession
import org.junit.Assert.*
import org.junit.Test

class GoogleTclBatchLogTest {
    @Test fun `full final URL waits in the existing batch until its only terminal upload`() {
        val session=Hq008ConsentTraceSession()
        val url="https://ads.example.com/ads?ms="+"abc%2Bdef".repeat(120)+"&uach=real%3D"
        fun record(event: String, log: String?=null)=session.record(100L,event,"requestId=req-filled","test",log)
        assertNull(record("CMP_GATE_START"))
        assertNull(record("AD_PHASE_START"))
        assertNull(record("AD_SDK_REQUEST",GoogleTclFinalUrlLog.json("req-filled",url,true)))
        assertNull(record("AD_UNUSED_DIAGNOSTIC", "x".repeat(270_000)))
        assertNull(record("AD_FILLED"))
        assertNull(record("AD_LOADED"))
        val snapshot=record("AD_PHASE_ERROR")!!
        val build=Hq008ConsentLogReporter::class.java.getDeclaredMethod("buildUploadPayload",Hq008ConsentTraceSession.TraceSnapshot::class.java).apply { isAccessible=true }
        val payload=build.invoke(Hq008ConsentLogReporter,snapshot)
        val getter=payload.javaClass.getDeclaredMethod("getAdLog").apply { isAccessible=true }
        val json=JsonParser.parseString(getter.invoke(payload) as String).asJsonObject
        val sdk=json.getAsJsonArray("steps").map { it.asJsonObject }.single { it.get("eventType").asString=="AD_SDK_REQUEST" }
        assertEquals(url,sdk.getAsJsonObject("adLog").get("final_url").asString)
        assertEquals("req-filled",sdk.getAsJsonObject("adLog").get("request_id").asString)
        assertEquals("AD_PHASE_ERROR",json.get("finalEventType").asString)
        assertEquals("critical_ad_log_only",json.get("traceCompactedMode").asString)
        assertFalse(session.hasPendingSteps())
        assertNull(record("AD_PHASE_ERROR"))
    }
}
