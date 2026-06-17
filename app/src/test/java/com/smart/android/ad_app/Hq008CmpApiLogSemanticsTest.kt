package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Test

class Hq008CmpApiLogSemanticsTest {

    @Test
    fun `cmp api result log should expose raw server code and message`() {
        val result = Hq008CmpApiLogSemantics.buildResultEventMessage(
            responseBody = """{"error_code":1000,"error_msg":"没有相关记录"}""",
            fallbackCode = null,
            fallbackMessage = null,
            hasData = false
        )

        assertEquals("code=1000,message=没有相关记录,hasData=false", result)
    }

    @Test
    fun `cmp api failure log should prefer raw server response over internal fallback`() {
        val result = Hq008CmpApiLogSemantics.buildFailureEventMessage(
            responseBody = """{"code":30000,"msg":"暂无活动","data":null}""",
            fallbackErrorMessage = "missing campaign data"
        )

        assertEquals("code=30000,message=暂无活动,hasData=false", result)
    }
}
