package com.smart.android.ad_app

import android.os.SystemClock
import androidx.annotation.Keep
import com.smart.android.ad_app.google.GoogleGamPatchClient
import com.speed.ext.getMacAddress
import com.tcl.ff.component.media.ima.api.AdExtra
import com.tcl.ff.component.media.ima.api.VastRequest
import com.tcl.ff.component.media.ima.constant.AdsEvent
import com.tcl.ff.component.media.ima.flat.AdsMaterialInfo
import com.tcl.ff.component.media.processor.address.data.BaseVastUrlInfo
import com.tcl.ff.component.media.processor.address.data.VastData
import com.tcl.ff.component.oversea.uniplayer.player.IPlayer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal data class TclRuntimeEvent(val type: String, val atMs: Long, val fields: Map<String, Any?>)

internal class TclRuntimeSession(
    val id: String, val channelId: String, val state: TclPlaybackState,
    val audit: TclRuntimeAuditReporter,
    val requestResultListener: (Map<String, Any?>) -> Unit = {},
    val listener: (TclRuntimeEvent) -> Unit
) {
    val sdkRequest = TclSdkRequestState()
    @Volatile var active = true
    @Volatile var deadlineMs = state.createdAtMs + state.requestTimeoutMs
    @Volatile var engine: WeakReference<Any>? = null
    @Volatile var filled = false
    @Volatile var finishing = false
    @Volatile var tagReady = false
    @Volatile var lastVast: TclVastObservation? = null
    @Volatile var lastDownloadError: String? = null
    @Volatile var patchError: String? = null
    val downloads = TclDownloadCallTracker()
    val materials = mutableMapOf<AdsMaterialInfo, Map<String, Any?>>()
    val patchedUrls = mutableSetOf<String>()
    val trackingUrls = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    val actualUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    lateinit var patch: TclBackendPatch

    fun publishRequestResult() {
        sdkRequest.takeRequestedReport()?.let(requestResultListener)
    }

    fun finishRequest(reason: String) {
        sdkRequest.finish(reason)
        publishRequestResult()
    }

    fun failureReason(loaded: Boolean, started: Boolean): String = when {
        patchError != null -> "gam_patch_failed"
        started -> "playback_failed"
        loaded -> "playback_start_failed"
        filled -> "media_load_failed"
        lastDownloadError != null -> if (tagReady) "vast_request_failed" else "tag_request_failed"
        lastVast?.parseError != null -> "vast_parse_failed"
        lastVast?.let { it.wrapperCount == 0 && !it.filled } == true -> "no_fill"
        tagReady -> "vast_unfilled"
        else -> "tag_request_failed"
    }

    fun event(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (!active) return
        val event = TclRuntimeEvent(type, SystemClock.elapsedRealtime(), fields)
        audit.record(type, event.atMs, fields)
        listener(event)
    }
}

/** Observation is scoped to pinned native requests; no global-current fallback on HTTP threads. */
@Keep
object TclRuntimeBridge {
    @Volatile internal var debugSessionObserver: ((TclRuntimeSession) -> Unit)? = null
    private val lock = Any()
    private val scope = ThreadLocal<TclRuntimeSession?>()
    private val addressScope = ThreadLocal<TclRuntimeSession?>()
    private data class Scope(val session: TclRuntimeSession?, val address: TclRuntimeSession?)
    private val engines = WeakHashMap<Any, TclRuntimeSession>()
    private val params = WeakHashMap<Any, TclRuntimeSession>()
    private val reporters = WeakHashMap<Any, TclRuntimeSession>()
    private val requests = mutableMapOf<String, TclRuntimeSession>()
    private data class Root(val session: TclRuntimeSession, val fallback: Boolean, val nativeId: String)
    private val roots = mutableMapOf<String, Root>()

    internal fun create(id: String, channelId: String, sound: Boolean, state: TclPlaybackState,
                        onRequestResult: (Map<String, Any?>) -> Unit = {},
                        listener: (TclRuntimeEvent) -> Unit): TclRuntimeSession {
        val session = TclRuntimeSession(id, channelId, state,
            TclRuntimeAuditReporter(id, channelId, sound, Hq008ConsentLogReporter.isFlowLoggingEnabled()),
            onRequestResult, listener)
        val parameters = linkedMapOf<String, Any>(
            "request_id" to id, "channel_id" to channelId,
            "rdid" to Hq008ApiRequestFields.readOrCreateRdid(appContext),
            "mac" to (runCatching { getMacAddress() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "00:00:00:00:00:00"), "ad_version" to BuildConfig.VERSION_CODE
        ).apply { Hq008ApiRequestFields.appendTo(this, appContext) }
        session.patch = TclBackendPatch(parameters,
            { (session.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) },
            GoogleGamPatchClient.shared,
            onRequest = { url -> session.event("patch_request", mapOf("source_url" to url)) },
            onReady = { url ->
                session.patchError = null
                synchronized(session.patchedUrls) { session.patchedUrls.add(url) }
                session.event("patch_ready", mapOf("final_url" to url))
            })
        if (BuildConfig.DEBUG) debugSessionObserver?.invoke(session)
        return session
    }

    internal fun <T> starting(session: TclRuntimeSession, action: () -> T): T {
        val previous = Scope(scope.get(), addressScope.get())
        scope.set(session)
        addressScope.remove()
        return try { action() } finally { leaveScope(previous) }
    }

    @JvmStatic fun bindEngine(engine: Any) {
        scope.get()?.takeIf { it.active }?.let { session ->
            synchronized(lock) { engines[engine] = session }
            session.engine = WeakReference(engine)
        }
    }

    @JvmStatic fun bindParams(value: Any?, engine: Any) {
        if (value == null) return
        synchronized(lock) { engines[engine]?.takeIf { it.active }?.let { params[value] = it } }
    }

    @JvmStatic fun enterParams(value: Any?): Any? {
        val previous = Scope(scope.get(), addressScope.get())
        val session = synchronized(lock) { params[value]?.takeIf { it.active } }
        scope.set(session)
        addressScope.set(session)
        return previous
    }

    @JvmStatic fun enterRequest(request: VastRequest?): Any? {
        val previous = Scope(scope.get(), addressScope.get())
        scope.set(synchronized(lock) { requests[request?.getRequestID()]?.takeIf { it.active } })
        addressScope.remove()
        return previous
    }

    @JvmStatic fun leaveScope(previous: Any?) {
        val owner = previous as? Scope
        scope.set(owner?.session)
        addressScope.set(owner?.address)
    }

    /** AddressLoader calls this on its own worker before posting the original response callback. */
    @JvmStatic fun patchAddressResponse(value: Any?): Any? {
        val session = scope.get()?.takeIf { it.active } ?: return value
        session.sdkRequest.businessResult((value as? BaseVastUrlInfo)?.getCode())
        val data = (value as? BaseVastUrlInfo)?.getData() ?: return value
        val sourceUrl = data.getAdsUrl()?.takeIf { it.isNotBlank() } ?: return value
        session.event("tag_source_ready", mapOf("source_url" to sourceUrl,
            "guarantee_url" to data.getGuaranteeLink(), "ad_platform_code" to data.getAdPlatformCode()))
        try {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "TCL patch requires address worker" }
            val patched = session.patch.resolve(sourceUrl)
            VastData::class.java.getDeclaredField("adsUrl").apply { isAccessible = true }.set(data, patched)
        } catch (error: Exception) {
            session.patchError = error.message ?: error.javaClass.simpleName
            session.event("patch_failed", mapOf("error" to error.message))
            throw IOException("TCL backend patch failed", error)
        }
        return value
    }

    @JvmStatic fun onTag(engine: Any, request: VastRequest?) {
        if (request == null) return
        val session = synchronized(lock) { engines[engine]?.takeIf { it.active } } ?: return
        session.tagReady = true
        synchronized(lock) {
            requests[request.getRequestID()] = session
            canonical(request.getVastUrl())?.let { roots[it] = Root(session, false, request.getRequestID()) }
            canonical(request.getGuaranteeLink())?.let { roots[it] = Root(session, true, request.getRequestID()) }
        }
        session.event("tag_ready", mapOf("native_request_id" to request.getRequestID(),
            "source_url" to request.getVastUrl(), "guarantee_url" to request.getGuaranteeLink(),
            "ad_unit_id" to request.getAdUnitID(), "ad_platform_code" to request.getAdPlatformCode()))
    }

    @JvmStatic fun onXml(xml: String?, request: VastRequest?) {
        if (xml == null || request == null) return
        val session = synchronized(lock) { requests[request.getRequestID()]?.takeIf { it.active } } ?: return
        val observation = TclVastObservation.parse(xml)
        session.lastVast = observation
        observation.trackingUrls.forEach { (url, events) -> canonical(url)?.let { session.trackingUrls[it] = events } }
        val fields = mapOf("native_request_id" to request.getRequestID(),
            "sdk_request_url" to request.getVastUrl(),
            "request_url" to (canonical(request.getVastUrl())?.let { session.actualUrls[it] } ?: request.getVastUrl()))
        session.audit.vast(xml, SystemClock.elapsedRealtime(), fields, observation)
        synchronized(session) {
            if (observation.filled && !session.filled && session.active) {
                session.filled = true
                session.event("filled", fields + mapOf("response_length" to xml.length))
            }
        }
    }

    @JvmStatic fun onSdkEvent(engine: Any, request: VastRequest?, event: AdsEvent?) {
        val session = synchronized(lock) { engines[engine]?.takeIf { it.active } } ?: return
        if (request != null && synchronized(lock) { requests[request.getRequestID()] } !== session) return
        session.event("sdk_event", mapOf("native_event" to event?.toString(),
            "native_request_id" to request?.getRequestID()))
    }

    @JvmStatic fun onNativeEvent(engine: Any, event: String, material: AdsMaterialInfo?, extra: AdExtra?) {
        val session = synchronized(lock) { engines[engine]?.takeIf { it.active } } ?: return
        try {
            val fields = linkedMapOf<String, Any?>("callback" to event)
            if (material != null) {
                fields["creative_id"] = material.getCreateId()
                fields["media_url"] = material.getMediaUrl()
                fields["media_url_hash"] = AdPrivacySanitizer.shortHash(material.getMediaUrl())
                synchronized(session.materials) {
                    if (extra != null) session.materials[material] = mapOf(
                        "ad_position" to extra.getAdPosition(), "total_ads" to extra.getTotalAds(),
                        "pod_max_duration_ms" to (extra.getMaxDuration() * 1_000).toLong())
                    session.materials[material]?.let(fields::putAll)
                }
            }
            session.event(event, fields)
        } catch (error: Exception) {
            session.event("observation_error", mapOf("callback" to event, "error" to error.javaClass.simpleName))
        }
    }

    @JvmStatic fun bindReporter(reporter: Any) {
        scope.get()?.takeIf { it.active }?.let { synchronized(lock) { reporters[reporter] = it } }
    }

    @JvmStatic fun onPlayerError(engine: Any, result: Any?, frameworkError: Int, extraError: Int) {
        val session = synchronized(lock) { engines[engine]?.takeIf { it.active } } ?: return
        session.event("native_player_error_detail", mapOf("result_code" to result?.toString(),
            "framework_error" to frameworkError, "extra_error" to extraError))
    }

    @JvmStatic fun enterTracking(reporter: Any): Any? {
        val previous = Scope(scope.get(), addressScope.get())
        scope.set(synchronized(lock) { reporters[reporter]?.takeIf { it.active } })
        addressScope.remove()
        return previous
    }

    @JvmStatic fun tagRequest(request: Request): Request = scope.get()?.takeIf { it.active }?.let {
        request.newBuilder().tag(TclRuntimeSession::class.java, it)
            .tag(TclAddressRequest::class.java, if (addressScope.get() === it) TclAddressRequest(it) else null)
            .build()
    } ?: request

    internal fun snapshot(session: TclRuntimeSession): Map<String, Any?> {
        val engine = session.engine?.get() ?: return mapOf("player_available" to false)
        return try {
            val base = Class.forName("com.tcl.ff.component.vastad.core.d.a")
            val player = base.getDeclaredField("f").apply { isAccessible = true }.get(engine) as? IPlayer
                ?: return mapOf("player_available" to false)
            val metadata = synchronized(session.materials) {
                session.materials.entries.firstOrNull {
                    (it.value["ad_position"] as? Number)?.toInt() == player.getCurMediaInfoIndex() + 1
                }?.let { (material, extra) -> extra + mapOf("creative_id" to material.getCreateId(),
                    "media_url_hash" to AdPrivacySanitizer.shortHash(material.getMediaUrl())) }
            }.orEmpty()
            linkedMapOf("player_available" to true, "position_ms" to player.getCurrentPosition(),
                "duration_ms" to player.getDuration(), "is_playing" to player.isPlaying(),
                "is_preparing" to player.isPrepareing(), "media_index" to player.getCurMediaInfoIndex(),
                "media_count" to player.getMediaInfoCount(),
                "configured_volume_percentage" to runCatching {
                    engine.javaClass.getMethod("getVolume").invoke(engine)
                }.getOrNull()) + metadata
        } catch (error: Exception) {
            mapOf("player_available" to false, "snapshot_error" to error.javaClass.simpleName)
        }
    }

    internal fun stopNetwork(session: TclRuntimeSession) {
        session.finishing = true
        session.patch.cancel()
        session.downloads.cancelAll()
    }

    internal fun close(session: TclRuntimeSession, reason: String, playback: Map<String, Any?>) {
        // Independent of active/current-request guards and of the detailed logging switch.
        session.finishRequest(reason)
        stopNetwork(session)
        session.active = false
        synchronized(lock) {
            engines.entries.removeAll { it.value === session }
            params.entries.removeAll { it.value === session }
            reporters.entries.removeAll { it.value === session }
            requests.entries.removeAll { it.value === session }
            roots.entries.removeAll { it.value.session === session }
        }
        session.audit.finish(reason, playback)?.let { summary ->
            Hq008ConsentLogReporter.report("TCL_RUNTIME_AUDIT", "requestId=${session.id},terminalReason=$reason",
                summary, session.id)
        }
    }

    val httpInterceptor = Interceptor { chain ->
        val original = chain.request()
        val tagged = original.tag(TclRuntimeSession::class.java)
        if (tagged != null && !tagged.active) throw IOException("Expired TCL HTTP owner")
        val scoped = tagged?.takeIf { it.active }
            ?: scope.get()?.takeIf { it.active }
        val nativeId = original.url.queryParameter("requestId")
        val root = canonical(original.url.toString())?.let { synchronized(lock) { roots[it] } }
            ?.takeIf { it.session === scoped || (nativeId != null && nativeId == it.nativeId) }
        val session = root?.session?.takeIf { it.active } ?: scoped
        if (session == null) return@Interceptor chain.proceed(original)
        val kind = when {
            root != null -> if (root.fallback) "guarantee" else "vast_root"
            original.tag(TclAddressRequest::class.java)?.session === session -> "address"
            original.header("Content-Type")?.startsWith("application/xml") == true -> "vast_wrapper"
            else -> "tracking"
        }
        val download = root != null || kind == "address" || kind == "vast_wrapper"
        val proceed = {
            val startedAt = SystemClock.elapsedRealtime()
            var actualUrl = original.url.toString()
            try {
                if (session.finishing && download) throw IOException("TCL request closed")
                val alreadyPatched = root != null && synchronized(session.patchedUrls) {
                    session.patchedUrls.any { canonical(it) == canonical(actualUrl) }
                }
                val request = if (root != null && !alreadyPatched) {
                    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "TCL patch requires HTTP worker" }
                    try {
                        actualUrl = session.patch.resolve(actualUrl)
                        original.newBuilder().url(actualUrl).build()
                    } catch (error: Exception) {
                        session.patchError = error.message ?: error.javaClass.simpleName
                        session.event("patch_failed", mapOf("error" to error.message))
                        throw IOException("TCL backend patch failed", error)
                    }
                } else original
                val response = chain.proceed(request)
                canonical(original.url.toString())?.let { session.actualUrls[it] = request.url.toString() }
                if (download) session.lastDownloadError = if (response.isSuccessful) null else "HTTP ${response.code}"
                session.event("http_result", mapOf("kind" to kind, "method" to original.method,
                    "request_url" to actualUrl, "http_status" to response.code,
                    "response_url" to response.request.url.toString(),
                    "declared_tracking_events" to canonical(actualUrl)?.let { session.trackingUrls[it] },
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                    "content_type" to response.header("Content-Type"),
                    "response_body" to if (kind == "address") response.peekBody(256L * 1024L).string() else null))
                response
            } catch (error: Exception) {
                if (download) session.lastDownloadError = error.message ?: error.javaClass.simpleName
                session.event("http_error", mapOf("kind" to kind, "request_url" to actualUrl,
                    "error" to error.message, "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt))
                throw error
            }
        }
        if (download) session.downloads.execute(chain.call(), proceed) else proceed()
    }

    private fun canonical(url: String?): String? = url?.toHttpUrlOrNull()?.newBuilder()
        ?.removeAllQueryParameters("requestId")?.fragment(null)?.build()?.toString()
}
