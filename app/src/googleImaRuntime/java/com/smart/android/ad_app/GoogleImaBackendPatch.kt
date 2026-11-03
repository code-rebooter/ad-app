package com.smart.android.ad_app

import android.os.SystemClock
import com.smart.android.ad_app.google.GoogleGamPatchClient
import com.smart.android.googleima.GoogleImaAdUrlResolver
import okhttp3.Call
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One patch POST shared by native HTTP and WebView fallback for this ad request. */
internal class GoogleImaBackendPatch(
    adTagUrl: String,
    parameters: Map<String, Any>,
    private val deadlineMs: Long,
    private val client: GoogleGamPatchClient = GoogleGamPatchClient.shared,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val onRequest: (String) -> Unit = {},
    private val onReady: (String) -> Unit = {},
    private val onFailure: (String) -> Unit = {}
) : GoogleImaAdUrlResolver {
    private val lock = Any()
    private val parameters = parameters.toMap()
    private val source = runCatching { URI(adTagUrl) }.getOrNull()
    private var selectedUrl: String? = null
    private var pending: Pending? = null
    private var cancelled = false

    override fun handles(url: String): Boolean = synchronized(lock) {
        val candidate = runCatching { URI(url) }.getOrNull() ?: return@synchronized false
        selectedUrl?.let { selected ->
            if (selected == url) return@synchronized true
            val previous = URI(selected)
            val correlator = query(previous, "correlator")
            val sameEndpoint = candidate.host.equals(previous.host, ignoreCase = true) &&
                candidate.rawPath == previous.rawPath || isGoogleGam(candidate) && isGoogleGam(previous)
            return@synchronized sameEndpoint && correlator.isNotEmpty() &&
                correlator == query(candidate, "correlator") &&
                query(previous, "iu") == query(candidate, "iu")
        }
        if (candidate.scheme !in listOf("http", "https")) return@synchronized false
        val sameTag = candidate.host.equals(source?.host, ignoreCase = true) &&
            candidate.rawPath == source?.rawPath
        if (!sameTag && !isGoogleGam(candidate)) return@synchronized false
        selectedUrl = url
        true
    }

    override fun resolve(url: String): String {
        val request = synchronized(lock) {
            checkOpen()
            check(handles(url)) { "Unexpected IMA ad URL" }
            pending ?: Pending().also { created ->
                pending = created
                try {
                    val completeUrl = selectedUrl!!
                    onRequest(completeUrl)
                    created.call = client.request(parameters + ("ad_tag_url" to completeUrl),
                        remainingTimeMs().coerceAtLeast(1L)) { result, error ->
                        synchronized(lock) {
                            if (cancelled || created.done.count == 0L) return@request
                            if (result != null && remainingTimeMs() > 0L) {
                                try {
                                    onReady(result)
                                    created.url = result
                                } catch (failure: Exception) {
                                    created.error = failure.message ?: "GAM patch delivery failed"
                                }
                            } else {
                                created.error = error ?: "GAM patch deadline exceeded"
                            }
                            created.done.countDown()
                        }
                    }
                } catch (error: Exception) {
                    created.error = error.message ?: "GAM patch failed"
                    created.done.countDown()
                }
            }
        }
        val completed = try {
            request.done.await(remainingTimeMs().coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel()
            throw IOException("GAM patch interrupted", error)
        }
        synchronized(lock) {
            checkOpen()
            if (!completed || remainingTimeMs() <= 0L) {
                request.call?.cancel()
                request.error = "GAM patch deadline exceeded"
            }
            request.error?.let { error ->
                if (!request.failureDelivered) {
                    request.failureDelivered = true
                    onFailure(error)
                }
                throw IOException(error)
            }
            return request.url ?: throw IOException("GAM patch returned no ad URL")
        }
    }

    override fun remainingTimeMs(): Long = (deadlineMs - clock()).coerceAtLeast(0L)

    override fun cancel() {
        synchronized(lock) {
            cancelled = true
            pending?.let {
                it.error = "GAM patch cancelled"
                it.call?.cancel()
                it.done.countDown()
            }
        }
    }

    private fun checkOpen() {
        if (cancelled) throw IOException("GAM patch cancelled")
        if (remainingTimeMs() <= 0L) throw IOException("GAM patch deadline exceeded")
    }

    private fun isGoogleGam(uri: URI): Boolean = uri.scheme in listOf("http", "https") &&
        uri.host?.lowercase() in setOf("pubads.g.doubleclick.net", "securepubads.g.doubleclick.net",
            "googleads.g.doubleclick.net", "pagead2.googlesyndication.com") &&
        uri.rawPath == "/gampad/ads"

    private fun query(uri: URI, name: String): List<String> = uri.rawQuery.orEmpty().split('&')
        .map { it.split('=', limit = 2) }
        .filter { it[0] == name }
        .map { runCatching { URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") }
            .getOrDefault(it.getOrElse(1) { "" }) }

    private class Pending {
        val done = CountDownLatch(1)
        var call: Call? = null
        var url: String? = null
        var error: String? = null
        var failureDelivered = false
    }
}
