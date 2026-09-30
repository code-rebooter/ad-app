package com.smart.android.adsdk.internal.tclcmp

import org.junit.Assert.assertEquals
import org.junit.Test

class TclCmpApiLogSemanticsTest {
    @Test
    fun nativeErrorCodeAndMessageSurviveRepositoryFallback() {
        assertEquals(
            "code=1000,message=没有相关记录,hasData=false",
            TclCmpApiLogSemantics.buildResultEventMessage(
                """{"error_code":1000,"error_msg":"没有相关记录"}""",
                null,
                null,
                false
            )
        )
    }

    @Test
    fun campaignFailureRetainsNativeNoActivityResponse() {
        assertEquals(
            "code=30000,message=暂无活动,hasData=false",
            TclCmpApiLogSemantics.buildFailureEventMessage(
                """{"code":30000,"msg":"暂无活动","data":null}""",
                "missing campaign data"
            )
        )
    }

    @Test
    fun malformedResponseUsesTransportFailure() {
        assertEquals(
            "error=connection reset",
            TclCmpApiLogSemantics.buildFailureEventMessage("not JSON", "connection reset")
        )
    }

    @Test
    fun nativeSuccessDataOverridesRepositoryFallback() {
        assertEquals(
            "code=100000,message=ok,hasData=true",
            TclCmpApiLogSemantics.buildResultEventMessage(
                """{"code":100000,"msg":"ok","data":{"tcString":"tcl-native"}}""",
                30000,
                "missing campaign data",
                false
            )
        )
    }
}
