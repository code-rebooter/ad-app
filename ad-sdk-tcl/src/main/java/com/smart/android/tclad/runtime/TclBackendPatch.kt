package com.smart.android.tclad.runtime


import okhttp3.Call
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Called on the vendor HTTP worker, never on the Android main thread. */
internal class TclBackendPatch(
    parameters: Map<String, Any>,
    private val remainingMs: () -> Long,
    private val client: GoogleGamPatchClient,
    private val enabled: Boolean,
    private val onRequest: (String) -> Unit,
    private val onReady: (String) -> Unit
) {
    private val parameters = parameters.toMap()
    private class Pending {
        val done = CountDownLatch(1)
        var call: Call? = null
        var url: String? = null
        var error: String? = null
        var delivered = false
    }
    private val pending = linkedMapOf<String, Pending>()
    private var cancelled = false

    fun resolve(url: String): String {
        if (!enabled) { synchronized(this) { checkOpen() }; return url }
        val item = synchronized(this) {
            checkOpen()
            pending[url] ?: Pending().also { request ->
                pending[url] = request
                try {
                    onRequest(url)
                    request.call = client.request(parameters + ("ad_tag_url" to url), remainingMs()) { result, error ->
                        synchronized(this) {
                            if (cancelled || request.done.count == 0L) return@request
                            request.url = result
                            request.error = error
                            request.done.countDown()
                        }
                    }
                } catch (error: Exception) {
                    request.error = error.message ?: "TCL patch request failed"
                    request.done.countDown()
                }
            }
        }
        val complete = try { item.done.await(remainingMs().coerceAtLeast(1L), TimeUnit.MILLISECONDS) }
        catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel()
            throw IOException("TCL patch interrupted", error)
        }
        return synchronized(this) {
            checkOpen()
            if (!complete) {
                item.call?.cancel()
                throw IOException("TCL patch deadline exceeded")
            }
            item.error?.let { throw IOException(it) }
            (item.url ?: throw IOException("TCL patch returned no URL")).also {
                if (!item.delivered) {
                    onReady(it)
                    item.delivered = true
                }
            }
        }
    }

    @Synchronized fun cancel() {
        cancelled = true
        pending.values.forEach {
            it.call?.cancel()
            it.error = "TCL patch cancelled"
            it.done.countDown()
        }
    }

    private fun checkOpen() {
        if (cancelled) throw IOException("TCL patch cancelled")
        if (remainingMs() <= 0L) throw IOException("TCL patch deadline exceeded")
    }
}
