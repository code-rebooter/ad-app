package com.smart.android.ad_app

import android.content.Context
import android.os.SystemClock
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.logging.PropertyLog
import com.tcl.ff.component.overseahttp.http.HttpRequester
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only observation. Requests and response bodies remain available to the native engine. */
internal object GoogleTclNetworkCapture {
    private var installed = false

    @Synchronized fun install(context: Context) {
        if (installed || !PropertyLog.isEnabled()) return
        try {
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir,
                "google-tcl-capture/run-${System.currentTimeMillis()}").apply { mkdirs() }
            val field = HttpRequester::class.java.getDeclaredField("c").apply { isAccessible = true }
            val requester = HttpRequester.get()
            val original = field.get(requester) as OkHttpClient
            field.set(requester, original.newBuilder().addInterceptor(Capture(directory)).build())
            installed = true
            Log.i("GoogleTclCapture", "Debug 原厂网络核验已开启，directory=${directory.absolutePath}")
        } catch (error: Exception) {
            Log.e("GoogleTclCapture", "Debug 原厂网络核验初始化失败", error)
        }
    }

    private class Capture(private val directory: File) : Interceptor {
        private val sequence = AtomicInteger()
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val index = sequence.incrementAndGet()
            val start = SystemClock.elapsedRealtime()
            val kind = if (request.header("Content-Type")?.startsWith("application/xml") == true) "vast" else "tracking"
            record(index, "request", kind, request.url.toString(), 0, "", 0)
            try {
                val response = chain.proceed(request)
                var filename = ""
                if (kind == "vast" && response.body != null) {
                    filename = "vast-%03d.xml".format(index)
                    try {
                        // peekBody observes a copy; it does not consume the response used by TCL.
                        File(directory, filename).writeBytes(response.peekBody(1_048_576).bytes())
                    } catch (error: IOException) { Log.w("GoogleTclCapture", "保存 VAST 观察副本失败", error) }
                }
                record(index, "response", kind, response.request.url.toString(), response.code,
                    filename, SystemClock.elapsedRealtime() - start)
                return response
            } catch (error: IOException) {
                record(index, "failure", kind, request.url.toString(), 0, error.toString(),
                    SystemClock.elapsedRealtime() - start)
                throw error
            }
        }
        @Synchronized private fun record(index: Int, phase: String, kind: String, url: String,
            status: Int, detail: String, elapsed: Long) {
            try {
                val row = JSONObject().put("timeMs", System.currentTimeMillis()).put("id", index)
                    .put("phase", phase).put("kind", kind).put("url", url).put("status", status)
                    .put("detail", detail).put("elapsedMs", elapsed)
                File(directory, "events.jsonl").appendText(row.toString() + "\n")
                if (phase != "request") Log.i("GoogleTclCapture", "phase=$phase,kind=$kind,status=$status,id=$index")
            } catch (error: IOException) { Log.w("GoogleTclCapture", "保存 Debug 网络核验记录失败", error) }
        }
    }
}
