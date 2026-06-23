package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Hq008ConsentLogSemanticsTest {

    @Test
    fun `localized log should translate raw server message and error code keys`() {
        val localized = invokeReporterMethod(
            methodName = "localizeEventMessage",
            parameterTypes = arrayOf(String::class.java, String::class.java),
            args = arrayOf(
                "CONSENT_STATUS_RESULT",
                "code=1000,message=没有相关记录,hasData=false,errorCode=-1000"
            )
        ) as String

        assertTrue(localized.contains("返回码=1000"))
        assertTrue(localized.contains("返回信息=没有相关记录"))
        assertTrue(localized.contains("错误码=-1000"))
        assertFalse(localized.contains("原因=执行动作异常"))
    }

    @Test
    fun `ad flow summary should prefer raw error detail over generic execution reason`() {
        val traceStepClass = Class.forName("com.smart.android.ad_app.Hq008ConsentLogReporter\$TraceStep")
        val constructor = traceStepClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }

        val steps = listOf(
            constructor.newInstance(
                1,
                0L,
                "AD_PHASE_START",
                "requestId=req-1,hidden=true",
                "请求ID=req-1，隐藏模式=是",
                null
            ),
            constructor.newInstance(
                2,
                10L,
                "AD_REQUESTED",
                "requestId=req-1,adId=req-1,hidden=true",
                "请求ID=req-1，adId=req-1，隐藏模式=是",
                null
            ),
            constructor.newInstance(
                3,
                30L,
                "AD_PHASE_ERROR",
                "requestId=req-1,adId=req-1,hidden=true,stage=sdk_callback,errorCode=-1000,reason=execution_error",
                "请求ID=req-1，adId=req-1，隐藏模式=是，阶段=SDK 回调，错误码=-1000，原因=执行动作异常",
                null
            )
        )

        val summaryStep = invokeReporterMethod(
            methodName = "buildAdFlowSummaryStep",
            parameterTypes = arrayOf(List::class.java, String::class.java),
            args = arrayOf(steps, "AD_PHASE_ERROR")
        ) ?: error("summary step should not be null")

        val summaryMessage = traceStepClass.getDeclaredMethod("getEventMessage").invoke(summaryStep) as String
        assertTrue(summaryMessage.contains("错误码=-1000"))
        assertFalse(summaryMessage.contains("原因=执行动作异常"))
    }

    @Test
    fun `cmp flow summary should preserve terminal stop reason`() {
        val traceStepClass = Class.forName("com.smart.android.ad_app.Hq008ConsentLogReporter\$TraceStep")
        val constructor = traceStepClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }

        val steps = listOf(
            constructor.newInstance(
                1,
                0L,
                "CMP_GATE_START",
                "adType=FLOATING,hidden=true,skipCmp=false,skipCmpSource=flow_control",
                "广告类型=FLOATING，隐藏模式=是，skipCmp=否，skipCmp来源=流控接口",
                null
            ),
            constructor.newInstance(
                2,
                8L,
                "CMP_GATE_STOP",
                "reason=flow_control_disabled",
                "原因=流控开关关闭",
                null
            )
        )

        val summaryStep = invokeReporterMethod(
            methodName = "buildFlowSummaryStep",
            parameterTypes = arrayOf(List::class.java, String::class.java),
            args = arrayOf(steps, "CMP_GATE_STOP")
        ) ?: error("summary step should not be null")

        val summaryMessage = traceStepClass.getDeclaredMethod("getEventMessage").invoke(summaryStep) as String
        assertTrue(summaryMessage.contains("授权结果=CMP 门禁提前结束（原因=流控开关关闭）"))
    }

    private fun invokeReporterMethod(
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Any? {
        val method = Hq008ConsentLogReporter::class.java.getDeclaredMethod(methodName, *parameterTypes)
        method.isAccessible = true
        return method.invoke(Hq008ConsentLogReporter, *args)
    }
}
