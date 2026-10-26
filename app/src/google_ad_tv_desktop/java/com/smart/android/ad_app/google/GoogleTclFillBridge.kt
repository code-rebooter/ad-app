package com.smart.android.ad_app.google

import androidx.annotation.Keep
import com.smart.android.ad_app.AdLocalLog as Log
import com.tcl.ff.component.media.ima.api.VastRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Watch only this host request's real TCL fetches; Wrapper forks keep the native request ID. */
@Keep
object GoogleTclFillBridge {
    data class Evidence(val nativeRequestId: String, val responseLength: Int)
    interface Handle { fun cancel() }
    private class Watch(val root: String, val callback: (Evidence) -> Unit) : Handle {
        val active = AtomicBoolean(true)
        val reported = AtomicBoolean()
        override fun cancel() {
            synchronized(lock) {
                active.set(false)
                if (roots[root] === this) roots.remove(root)
                nativeRequests.entries.removeAll { it.value === this }
            }
        }
    }
    private val lock = Any()
    private val roots = mutableMapOf<String, Watch>()
    private val nativeRequests = mutableMapOf<String, Watch>()

    fun watch(finalUrl: String, onFilled: (Evidence) -> Unit): Handle {
        val key = canonical(finalUrl) ?: throw IllegalArgumentException("Invalid final ad URL")
        return Watch(key, onFilled).also { synchronized(lock) { roots[key] = it } }
    }

    @JvmStatic fun beforeFetch(request: VastRequest?) {
        if (request != null) bind(request.getRequestID(), request.getVastUrl())
    }
    internal fun bind(nativeId: String?, url: String?) {
        if (nativeId.isNullOrBlank() || url == null) return
        val key = canonical(url) ?: return
        synchronized(lock) {
            if (nativeRequests[nativeId]?.active?.get() == true) return
            roots[key]?.takeIf { it.active.get() }?.let { nativeRequests[nativeId] = it }
        }
    }

    @JvmStatic fun onXml(xml: String?, request: VastRequest?) {
        if (request != null) observe(xml, request.getRequestID())
    }
    internal fun observe(xml: String?, nativeId: String?) {
        val watch = synchronized(lock) { nativeRequests[nativeId] } ?: return
        if (!watch.active.get() || watch.reported.get()) return
        if (!GoogleTclVastFillDetector.hasInlineVideo(xml)) return
        if (!watch.active.get() || !watch.reported.compareAndSet(false, true)) return
        // Called synchronously before the original XmlConverter sees the response.
        try { watch.callback(Evidence(nativeId.orEmpty(), xml.orEmpty().length)) }
        catch (error: RuntimeException) { Log.e("GoogleTclFill", "填充状态上报失败", error) }
    }

    private fun canonical(url: String): String? = url.toHttpUrlOrNull()?.newBuilder()
        ?.removeAllQueryParameters("requestId")?.fragment(null)?.build()?.toString()
}
