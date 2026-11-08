package com.smart.android.tclad.runtime

import android.os.SystemClock
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Only native AddressLoader calls receive this tag, not initialization, tracking or VAST. */
internal class TclAddressRequest(val session: TclRuntimeSession)

/** Runs inside the existing capture interceptors, so body-read failures cannot hide a response. */
internal class TclSdkRequestInterceptor(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = request.tag(TclAddressRequest::class.java)?.session
            ?: return chain.proceed(request)
        if (!session.active || session.finishing || !session.sdkRequest.begin(nowMs())) {
            throw IOException("TCL request closed")
        }
        try {
            val response = chain.proceed(request)
            // Record headers before reading JSON: empty, malformed and interrupted bodies still count.
            session.sdkRequest.responded(nowMs(), response.code)
            try {
                val body = response.peekBody(64L * 1024L).string()
                val code = runCatching {
                    JsonParser.parseString(body).asJsonObject.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                }.getOrNull()
                session.sdkRequest.businessResult(code)
            } catch (error: Exception) {
                session.sdkRequest.failed("response_body: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                session.publishRequestResult()
            }
            return response
        } catch (error: Exception) {
            session.sdkRequest.failed(error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            // A redirect may have responded before a subsequent connection failed.
            session.publishRequestResult()
        }
    }
}

/** Observe each real response before redirects, body reads and outer capture can fail. */
internal class TclSdkResponseInterceptor(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request()).also {
        chain.request().tag(TclAddressRequest::class.java)?.session?.sdkRequest?.responded(nowMs(), it.code)
    }
}
