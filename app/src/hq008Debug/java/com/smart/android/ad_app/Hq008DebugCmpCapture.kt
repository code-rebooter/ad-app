package com.smart.android.ad_app

import android.util.Log
import com.tcl.ff.component.overseahttp.http.HttpRequester
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

internal object Hq008DebugCmpCapture {
    private const val TAG = "Hq008DebugCmpHttp"
    private const val CMP_PATH = "global-consentmanage-api"
    private const val MAX_CAPTURE_BYTES = 128L * 1024L

    @Volatile
    private var installed = false

    fun install() {
        if (installed) {
            return
        }
        synchronized(this) {
            if (installed) {
                return
            }
            runCatching {
                val requester = HttpRequester.get()
                val field = findClientField(requester.javaClass)
                    ?: error("OkHttpClient field not found")
                val currentClient = field.get(requester) as? OkHttpClient
                    ?: error("OkHttpClient instance not found")
                val replacement = currentClient.newBuilder()
                    .addInterceptor(CmpResponseInterceptor)
                    .build()
                field.set(requester, replacement)
                installed = true
                Log.i(TAG, "Debug CMP response capture installed")
            }.onFailure { error ->
                Log.e(TAG, "Debug CMP response capture installation failed", error)
            }
        }
    }

    private fun findClientField(type: Class<*>): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull {
                OkHttpClient::class.java.isAssignableFrom(it.type)
            }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private object CmpResponseInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.url.encodedPath.contains(CMP_PATH)) {
                Log.i(
                    TAG,
                    "${request.method} ${request.url} code=${response.code} body=${response.peekBody(MAX_CAPTURE_BYTES).string()}"
                )
            }
            return response
        }
    }
}
